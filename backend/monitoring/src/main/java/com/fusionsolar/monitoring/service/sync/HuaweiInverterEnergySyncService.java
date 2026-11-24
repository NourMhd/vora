package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.client.FusionSolarStationClient;
import com.fusionsolar.monitoring.dto.DeviceItemDto;
import com.fusionsolar.monitoring.persistence.document.SiteDeviceDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.repositories.SiteDeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** SmartPVMS 25.4.0, section 5.1.2.2: total_cap (kWh), types 1/38,
 * maximum 100 devices of the same type per request, minimum interval 5 minutes.
 * Never invoked by a report request. Existing provider guards remain in force. */
@Service @RequiredArgsConstructor @Slf4j
public class HuaweiInverterEnergySyncService {
    private final SiteDeviceRepository devices;
    private final FusionSolarStationClient client;
    private final MongoTemplate mongo;
    private final Map<String, Instant> attempts = new HashMap<>();

    @Scheduled(initialDelayString = "${app.huawei-sync.inverter-initial-delay-ms:120000}",
            fixedDelayString = "${app.huawei-sync.inverter-check-ms:60000}")
    public synchronized void sync() {
        Map<String, Batch> groups = new LinkedHashMap<>();
        for (var document : devices.findAllByBrand(Brand.HUAWEI)) {
            if (document.getProviderAccountKey() == null || document.getDevices() == null) continue;
            for (var device : inverters(document)) {
                String key = document.getProviderAccountKey() + ":" + device.getDevTypeId();
                groups.computeIfAbsent(key, k -> new Batch(document.getProviderAccountKey(), device.getDevTypeId(), new LinkedHashMap<>()))
                        .owners().computeIfAbsent(device.getId(), k -> new ArrayList<>()).add(document);
            }
        }
        List<Task> tasks = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            var group = entry.getValue();
            List<String> ids = new ArrayList<>(group.owners().keySet());
            for (int offset = 0; offset < ids.size(); offset += 100)
                tasks.add(new Task(entry.getKey() + ":" + offset, group,
                        List.copyOf(ids.subList(offset, Math.min(ids.size(), offset + 100)))));
        }
        // Order every batch, not just every type: otherwise the first 100 devices
        // could consume every endpoint slot and starve subsequent batches.
        tasks.sort(Comparator.comparing(task -> attempts.getOrDefault(task.key(), Instant.EPOCH)));
        for (var task : tasks) {
            var group = task.group();
            var batch = task.ids();
            Instant now = Instant.now();
            if (attempts.getOrDefault(task.key(), Instant.EPOCH).isAfter(now.minusSeconds(300))) continue;
            try {
                var response = client.getDeviceRealKpi(group.account(), batch, group.type());
                attempts.put(task.key(), now);
                if (response == null || !Boolean.TRUE.equals(response.getSuccess()) || response.getData() == null) continue;
                Set<String> seen = new HashSet<>();
                for (var item : response.getData()) {
                    if (item == null || !batch.contains(item.getDevId()) || !seen.add(item.getDevId())) continue;
                    Double total = item.getDataItemMap() == null ? null
                            : HuaweiStateSyncService.nonNegativeEnergy(item.getDataItemMap().get("total_cap"));
                    if (total == null || !item.getDevId().matches("[a-zA-Z0-9_-]+")) continue;
                    for (var owner : group.owners().get(item.getDevId())) {
                        mongo.updateFirst(Query.query(Criteria.where("_id").is(owner.getId())),
                                new Update().set("inverterTotalKwh." + item.getDevId(), total)
                                        .set("inverterTotalObservedAt." + item.getDevId(), now), SiteDeviceDocument.class);
                    }
                }
            } catch (RuntimeException deferred) {
                // Deferred requests did not consume a slot; keep their queue position.
                log.info("Huawei inverter energy deferred account={} type={}: {}", group.account(), group.type(), deferred.getMessage());
            }
        }
    }

    static List<DeviceItemDto> inverters(SiteDeviceDocument document) {
        if (document == null || document.getDevices() == null) return List.of();
        Map<String, DeviceItemDto> unique = new LinkedHashMap<>();
        for (var device : document.getDevices()) {
            if (device != null && device.getId() != null && !device.getId().isBlank()
                    && (Integer.valueOf(1).equals(device.getDevTypeId()) || Integer.valueOf(38).equals(device.getDevTypeId())))
                unique.putIfAbsent(device.getId(), device);
        }
        return List.copyOf(unique.values());
    }

    /** A partial sum must never replace the station's existing calculation. */
    public static Double completeTotal(SiteDeviceDocument document, Instant now) {
        var inverters = inverters(document);
        if (inverters.isEmpty() || document.getInverterTotalKwh() == null || document.getInverterTotalObservedAt() == null) return null;
        double total = 0;
        for (var inverter : inverters) {
            Double value = document.getInverterTotalKwh().get(inverter.getId());
            Instant at = document.getInverterTotalObservedAt().get(inverter.getId());
            if (value == null || !Double.isFinite(value) || value < 0 || at == null
                    || at.isBefore(now.minusSeconds(1200)) || at.isAfter(now)) return null;
            total += value;
        }
        return Double.isFinite(total) ? total : null;
    }
    private record Task(String key, Batch group, List<String> ids) {}
    private record Batch(String account, Integer type, Map<String, List<SiteDeviceDocument>> owners) {}
}
