package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.client.FusionSolarStationClient;
import com.fusionsolar.monitoring.dto.StationRealKpiResponse;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.document.SyncRunDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.enums.SyncStatus;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import com.fusionsolar.monitoring.persistence.repositories.SyncRunRepository;
import com.fusionsolar.monitoring.service.huawei.HuaweiAccountDirectory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Updates only observed states; does not alter inventory, ownership, swap dates or energy. */
@Service @Slf4j @RequiredArgsConstructor
public class HuaweiStateSyncService {
    private final SiteRepository sites;
    private final SyncRunRepository runs;
    private final FusionSolarStationClient client;
    private final HuaweiAccountDirectory accounts;
    private final AtomicBoolean running = new AtomicBoolean();

    @Scheduled(initialDelayString = "${app.huawei-sync.state-initial-delay-ms:15000}", fixedDelayString = "${app.huawei-sync.state-retry-check-ms:15000}", scheduler = "stateScheduler")
    public void syncStates() {
        if (!running.compareAndSet(false, true)) return;
        SyncRunDocument run = null;
        int updated = 0, failed = 0, expected = 0, energyUpdated = 0;
        try {
            Instant started = Instant.now();
            run = runs.save(SyncRunDocument.builder().brand(Brand.HUAWEI).type("STATES")
                    .status(SyncStatus.RUNNING).message("Huawei state sync started").startedAt(started).build());
            Set<String> enabled = new HashSet<>();
            accounts.getEnabledAccounts().forEach(a -> enabled.add(a.key()));
            Map<String, List<SiteDocument>> byAccount = new LinkedHashMap<>();
            List<SiteDocument> active = sites.findAllByBrandOrderBySiteNameAsc(Brand.HUAWEI).stream()
                    .filter(s -> !Boolean.FALSE.equals(s.getActive())).toList();
            expected = active.size();
            for (SiteDocument site : active) {
                if (!enabled.contains(site.getProviderAccountKey())) { failed++; continue; }
                byAccount.computeIfAbsent(site.getProviderAccountKey(), k -> new ArrayList<>()).add(site);
            }
            // Oldest observations first: deferred batches remain first in the next cycle.
            for (var entry : byAccount.entrySet()) {
                entry.getValue().sort(Comparator.comparing(SiteDocument::getStatusLastSyncAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
            }
            List<Map.Entry<String, List<SiteDocument>>> sources = new ArrayList<>(byAccount.entrySet());
            sources.sort(Comparator.comparing(e -> e.getValue().get(0).getStatusLastSyncAt(),
                    Comparator.nullsFirst(Comparator.naturalOrder())));
            for (var entry : sources) {
                List<SiteDocument> pending = entry.getValue().stream().filter(site -> site.getStatusLastSyncAt() == null ||
                        site.getStatusLastSyncAt().isBefore(Instant.now().minusSeconds(300))).toList();
                for (int offset = 0; offset < pending.size(); offset += 100) {
                    List<SiteDocument> batch = pending.subList(offset, Math.min(offset + 100, pending.size()));
                    try {
                        // Non-blocking existing guard: no reduced intervals, no waiting for history.
                        if (batch.stream().anyMatch(site -> site.getStatusLastSyncAt() == null ||
                                site.getStatusLastSyncAt().isBefore(Instant.now().minusSeconds(900))))
                            client.prioritizeStateCollection(entry.getKey());
                        StationRealKpiResponse response = client.getStationRealKpi(entry.getKey(),
                                batch.stream().map(SiteDocument::getSiteCode).toList());
                        Map<String, StationRealKpiResponse.DataItem> observations = new HashMap<>();
                        if (response != null && response.getData() != null)
                            for (var item : response.getData()) if (item != null) observations.put(item.getStationCode(), item);
                        for (SiteDocument site : batch) {
                            var item = observations.get(site.getSiteCode());
                            Object raw = item == null || item.getDataItemMap() == null ? null : item.getDataItemMap().get("real_health_state");
                            String status = decodeState(raw);
                            Instant observedAt = Instant.now();
                            Map<String, Object> values = item == null || item.getDataItemMap() == null
                                    ? Map.of() : item.getDataItemMap();
                            if (status != null) {
                                updateObservedState(site.getId(), status, observedAt);
                                site.setStatusLastSyncAt(observedAt);
                                updated++;
                            }
                            // Energy remains usable even when the state field is absent.
                            if (updateObservedEnergy(site.getId(), values, observedAt)) energyUpdated++;
                        }
                    } catch (Exception e) {
                        log.info("Huawei states deferred/incomplete account={} batch={} cause={}", entry.getKey(), batch.size(), e.getMessage());
                        break;
                    }
                }
            }
            long fresh = active.stream().filter(s -> s.getStatusLastSyncAt() != null &&
                    s.getStatusLastSyncAt().isAfter(Instant.now().minusSeconds(900))).count();
            run.setStatus(expected > 0 && updated == expected ? SyncStatus.SUCCESS : SyncStatus.PARTIAL_SUCCESS);
            log.info("Huawei state coverage active={} updatedThisPass={} freshWithin15Minutes={} stale={}", expected, updated, fresh, expected-fresh);
            run.setStats(Map.of("freshWithin15Minutes", fresh, "staleBeyond15Minutes", expected-fresh));
            run.setMessage("Huawei state cycle finished; missing states retain their previous observation");
        } catch (Exception e) {
            if (run != null) { run.setStatus(SyncStatus.FAILED); run.setMessage(e.getMessage()); }
            log.warn("Huawei state cycle failed", e);
        } finally {
            try {
                if (run != null) {
                    run.setFinishedAt(Instant.now());
                    Map<String, Object> stats = new HashMap<>();
                    if (run.getStats() != null) stats.putAll(run.getStats());
                    stats.put("energyUpdated", energyUpdated); stats.put("expected", expected); stats.put("liveKpiUpdated", updated);
                    stats.put("statesNotRefreshed", Math.max(0, expected-updated)); stats.put("invalidOwner", failed);
                    run.setStats(stats);
                    runs.save(run);
                }
            } finally { running.set(false); }
        }
    }

    private final org.springframework.data.mongodb.core.MongoTemplate mongo;
    @org.springframework.beans.factory.annotation.Autowired
    private com.fusionsolar.monitoring.planning.SiteTrackingService siteTracking;
    protected void updateObservedState(String id, String status, Instant observedAt) {
        var persisted = mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").is(id).and("statusLastSyncAt").not().gt(observedAt)),
                new org.springframework.data.mongodb.core.query.Update().set("status", com.fusionsolar.monitoring.persistence.enums.SiteStatus.valueOf(status.toUpperCase(java.util.Locale.ROOT)))
                        .set("statusLastSyncAt", observedAt), SiteDocument.class);
        if (persisted.getMatchedCount() > 0 && siteTracking != null) siteTracking.captureById(id, "HUAWEI_STATES");
    }
    protected boolean updateObservedEnergy(String id, Map<String, Object> values, Instant observedAt) {
        var update = energyUpdate(values, observedAt);
        if (update == null) return false;
        return mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").is(id)
                        .and("energyLastSyncAt").not().gt(observedAt)), update, SiteDocument.class)
                .getMatchedCount() > 0;
    }

    static org.springframework.data.mongodb.core.query.Update energyUpdate(Map<String, Object> values, Instant observedAt) {
        var update = new org.springframework.data.mongodb.core.query.Update();
        boolean found = false;
        String[][] fields = {{"day_power", "dayEnergyKwh"}, {"month_power", "monthEnergyKwh"},
                {"total_power", "totalEnergyKwh"}};
        for (String[] field : fields) {
            Double value = nonNegativeEnergy(values.get(field[0]));
            if (value != null) { update.set(field[1], value); found = true; }
        }
        if (!found) return null;
        if (nonNegativeEnergy(values.get("day_power")) != null)
            update.set("energyDate", java.time.LocalDate.ofInstant(observedAt, java.time.ZoneId.of("Africa/Tunis")));
        if (nonNegativeEnergy(values.get("month_power")) != null) update.set("monthEnergyLastSyncAt", observedAt);
        return update.set("energyLastSyncAt", observedAt);
    }

    static Double nonNegativeEnergy(Object raw) {
        if (raw == null) return null;
        try {
            double value = Double.parseDouble(raw.toString());
            return Double.isFinite(value) && value >= 0 ? value : null;
        } catch (RuntimeException invalid) { return null; }
    }

    static String decodeState(Object raw) {
        if (raw == null) return null;
        try {
            java.math.BigDecimal value = new java.math.BigDecimal(raw.toString());
            return switch (value.intValueExact()) {
                case 1 -> "disconnected"; case 2 -> "faulty"; case 3 -> "healthy"; default -> null;
            };
        } catch (RuntimeException invalid) { return null; }
    }
}
