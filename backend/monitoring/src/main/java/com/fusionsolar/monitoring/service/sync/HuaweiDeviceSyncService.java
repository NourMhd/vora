package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.client.FusionSolarStationClient;
import com.fusionsolar.monitoring.dto.DeviceItemDto;
import com.fusionsolar.monitoring.dto.DeviceListResponse;
import com.fusionsolar.monitoring.dto.SiteDevicesResponseDto;
import com.fusionsolar.monitoring.persistence.document.SiteDeviceDocument;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.document.SyncRunDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.enums.SyncStatus;
import com.fusionsolar.monitoring.persistence.repositories.SiteDeviceRepository;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import com.fusionsolar.monitoring.persistence.repositories.SyncRunRepository;
import com.fusionsolar.monitoring.provider.mapper.SiteMongoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;



@Slf4j
@Service
@RequiredArgsConstructor
public class HuaweiDeviceSyncService {

    private static final Duration DEVICE_CACHE_TTL = Duration.ofHours(12);
    private static final Duration DEVICE_RATE_LIMIT_COOLDOWN = Duration.ofHours(6);

    /**
     * Guard against repeated getDevList calls after Huawei returns 407/429.
     * getDevList is a slow cache refresh job, not a live dependency.
     */
    private volatile Instant nextAllowedDeviceSyncAt = Instant.EPOCH;

    private final SiteDeviceRepository siteDeviceRepository;
    private final SiteRepository siteRepository;
    private final SyncRunRepository syncRunRepository;
    private final FusionSolarStationClient stationClient;
    private final SiteMongoMapper siteMongoMapper;


    @Value("${app.huawei-sync.batch-size:100}")
private int batchSize;

@Value("${app.huawei-sync.delay-between-batches-ms:15000}")
private long delayBetweenBatchesMs;

    public SiteDevicesResponseDto getSiteDevices(SiteDocument site) {
        SiteDeviceDocument cached = siteDeviceRepository
                .findByBrandAndProviderAccountGroupAndSiteCode(
                        site.getBrand(),
                        accountGroupOf(site),
                        site.getSiteCode()
                )
                .orElseGet(() -> siteDeviceRepository.findByBrandAndSiteCode(site.getBrand(), site.getSiteCode()).orElse(null));

        if (cached != null && cached.getDevices() != null) {
            return siteMongoMapper.toSiteDevicesResponseDto(cached);
        }

        // Do not call Huawei getDevList from a user request.
        // The device cache is refreshed by the slow scheduled job only.
        log.info("Device cache miss for site {}. Returning empty cached response; scheduled device sync will fill it.", site.getSiteCode());
        return new SiteDevicesResponseDto(site.getSiteCode(), List.of());
    }

    public synchronized void syncAllDevices(boolean force) {
    List<SiteDocument> allSites = siteRepository.findAllByBrandOrderBySiteNameAsc(Brand.HUAWEI);
    if (allSites.isEmpty()) {
        log.warn("Device sync skipped: no Huawei sites in Mongo.");
        return;
    }

    Instant nowCheck = Instant.now();
    if (!force && nowCheck.isBefore(nextAllowedDeviceSyncAt)) {
        log.warn("Device sync skipped: Huawei getDevList is cooling down until {}", nextAllowedDeviceSyncAt);
        return;
    }

    if (!force && isDeviceCacheFreshEnough()) {
        log.info("Device sync skipped: cached Huawei devices are still fresh.");
        return;
    }

    List<String> cachedSiteIdentities = siteDeviceRepository.findAllByBrand(Brand.HUAWEI).stream()
            .filter(doc -> doc.getSiteCode() != null && !doc.getSiteCode().isBlank())
            .map(doc -> identity(doc.getProviderAccountGroup(), doc.getProviderAccountKey(), doc.getSiteCode()))
            .toList();

    List<SiteDocument> sites = allSites.stream()
            .filter(site -> site.getSiteCode() != null && !site.getSiteCode().isBlank())
            .filter(site -> force || !cachedSiteIdentities.contains(identity(site.getProviderAccountGroup(), site.getProviderAccountKey(), site.getSiteCode())))
            .limit(batchSize)
            .toList();

    if (sites.isEmpty()) {
        log.info("Device sync skipped: all Huawei sites already have cached devices.");
        return;
    }

    SyncRunDocument run = syncRunRepository.save(SyncRunDocument.builder()
            .brand(Brand.HUAWEI)
            .type("HUAWEI_DEVICES")
            .status(SyncStatus.RUNNING)
            .message("Huawei device sync started")
            .startedAt(Instant.now())
            .build());

    int savedSites = 0;
    int receivedDevices = 0;
    int failedBatches = 0;
    Instant now = Instant.now();

    try {
        Map<String, List<SiteDocument>> sitesByAccount = sites.stream()
                .collect(Collectors.groupingBy(this::accountKeyOf));

        for (Map.Entry<String, List<SiteDocument>> entry : sitesByAccount.entrySet()) {
            String accountKey = entry.getKey();
            List<SiteDocument> accountSites = entry.getValue();
            String stationCodes = accountSites.stream()
                    .map(SiteDocument::getSiteCode)
                    .distinct()
                    .collect(Collectors.joining(","));

            try {
                DeviceListResponse response = stationClient.getDevicesByStationCodes(accountKey, stationCodes);
                List<DeviceItemDto> devices = response != null && response.getData() != null
                        ? response.getData()
                        : List.of();

                receivedDevices += devices.size();

                Map<String, List<DeviceItemDto>> devicesByStation = devices.stream()
                        .filter(device -> device.getStationCode() != null && !device.getStationCode().isBlank())
                        .collect(Collectors.groupingBy(DeviceItemDto::getStationCode));

                for (SiteDocument site : accountSites) {
                    String accountGroup = accountGroupOf(site);
                    List<DeviceItemDto> siteDevices = devicesByStation.getOrDefault(site.getSiteCode(), List.of());

                    SiteDeviceDocument existing = siteDeviceRepository
                            .findByBrandAndProviderAccountGroupAndSiteCode(Brand.HUAWEI, accountGroup, site.getSiteCode())
                            .orElse(null);

                    SiteDeviceDocument document = siteMongoMapper.toSiteDeviceDocument(
                            Brand.HUAWEI,
                            site.getSiteCode(),
                            siteDevices,
                            existing,
                            now
                    );
                    document.setProviderAccountKey(accountKeyOf(site));
                    document.setProviderAccountGroup(accountGroup);

                    siteDeviceRepository.save(document);
                    savedSites++;
                }

                log.info("Huawei device sync saved missing batch account={}, sites={}, devices={}",
                        accountKey, accountSites.size(), devices.size());

            } catch (Exception e) {
                failedBatches++;
                log.warn("Huawei device sync failed for account {}: {}", accountKey, e.getMessage());

                if (isRateLimit(e)) {
                    nextAllowedDeviceSyncAt = Instant.now().plus(DEVICE_RATE_LIMIT_COOLDOWN);
                }
            }
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalSites", allSites.size());
        stats.put("selectedSites", sites.size());
        stats.put("savedSites", savedSites);
        stats.put("receivedDevices", receivedDevices);
        stats.put("failedBatches", failedBatches);
        stats.put("rateLimited", failedBatches > 0);
        stats.put("remainingSites", Math.max(0, allSites.size() - siteDeviceRepository.findAllByBrand(Brand.HUAWEI).size()));

        run.setStatus(failedBatches == 0 ? SyncStatus.SUCCESS : (savedSites > 0 ? SyncStatus.PARTIAL_SUCCESS : SyncStatus.FAILED));
        run.setMessage(failedBatches == 0 ? "Huawei device sync finished" : "Huawei device sync partially failed");
        run.setStats(stats);
        run.setFinishedAt(Instant.now());
        syncRunRepository.save(run);

    } catch (Exception e) {
        run.setStatus(SyncStatus.FAILED);
        run.setMessage(e.getMessage());
        run.setFinishedAt(Instant.now());
        syncRunRepository.save(run);
        throw e;
    }
}

    public List<SiteDeviceDocument> getCachedHuaweiDeviceDocuments() {
        return siteDeviceRepository.findAllByBrand(Brand.HUAWEI);
    }

    private String accountKeyOf(SiteDocument site) {
        if (site != null && site.getProviderAccountKey() != null && !site.getProviderAccountKey().isBlank()) {
            return site.getProviderAccountKey().trim();
        }
        return "primary";
    }

    private String accountGroupOf(SiteDocument site) {
        if (site != null && site.getProviderAccountGroup() != null && !site.getProviderAccountGroup().isBlank()) {
            return site.getProviderAccountGroup().trim();
        }
        if (site != null && site.getProviderAccountKey() != null && !site.getProviderAccountKey().isBlank()) {
            return site.getProviderAccountKey().trim();
        }
        return "primary";
    }

    private String identity(String providerAccountGroup, String providerAccountKey, String siteCode) {
        String group = providerAccountGroup != null && !providerAccountGroup.isBlank()
                ? providerAccountGroup.trim()
                : (providerAccountKey != null && !providerAccountKey.isBlank() ? providerAccountKey.trim() : "primary");
        return group + "|" + siteCode.trim();
    }

    private boolean isDeviceCacheFreshEnough() {
        List<SiteDeviceDocument> cached = siteDeviceRepository.findAllByBrand(Brand.HUAWEI);
        long siteCount = siteRepository.countByBrand(Brand.HUAWEI);
        if (siteCount == 0 || cached.size() < siteCount) {
            return false;
        }
        Instant minAccepted = Instant.now().minus(DEVICE_CACHE_TTL);
        return cached.stream().allMatch(doc -> doc.getLastSyncAt() != null && !doc.getLastSyncAt().isBefore(minAccepted));
    }

    private boolean isRateLimit(Exception e) {
        String text = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return text.contains("rate limit") || text.contains("407") || text.contains("429") || text.contains("access_frequency");
    }

   
}
