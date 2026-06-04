package com.fusionsolar.monitoring.service.sync;

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
import com.fusionsolar.monitoring.provider.solis.SolisMonitoringProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "providers.solis", name = "enabled", havingValue = "true")
public class SolisDeviceSyncService {

    private static final Duration DEVICE_CACHE_TTL = Duration.ofHours(12);

    private final SiteDeviceRepository siteDeviceRepository;
    private final SiteRepository siteRepository;
    private final SyncRunRepository syncRunRepository;
    private final SiteMongoMapper siteMongoMapper;
    private final SolisMonitoringProvider solisMonitoringProvider;

    public synchronized void syncAllDevices(boolean force) {
        List<SiteDocument> allSites = siteRepository.findAllByBrandOrderBySiteNameAsc(Brand.SOLIS);
        if (allSites.isEmpty()) {
            log.info("Solis device sync skipped: no Solis sites in Mongo.");
            return;
        }

        if (!force && isDeviceCacheFreshEnough()) {
            log.info("Solis device sync skipped: cached Solis devices are still fresh.");
            return;
        }

        SyncRunDocument run = SyncRunDocument.builder()
                .brand(Brand.SOLIS)
                .type("DEVICES")
                .status(SyncStatus.RUNNING)
                .message("Solis device sync started")
                .startedAt(Instant.now())
                .build();
        run = syncRunRepository.save(run);

        int savedSites = 0;
        int receivedDevices = 0;
        int failedSites = 0;
        Instant now = Instant.now();

        try {
            for (SiteDocument site : allSites) {
                try {
                    SiteDevicesResponseDto siteDevices = solisMonitoringProvider.getSiteDevices(site.getSiteCode());
                    receivedDevices += siteDevices.getDevices() == null ? 0 : siteDevices.getDevices().size();

                    SiteDeviceDocument existing = siteDeviceRepository
                            .findByBrandAndSiteCode(Brand.SOLIS, site.getSiteCode())
                            .orElse(null);

                    siteDeviceRepository.save(siteMongoMapper.toSiteDeviceDocument(
                            Brand.SOLIS,
                            site.getSiteCode(),
                            siteDevices.getDevices(),
                            existing,
                            now
                    ));
                    savedSites++;
                } catch (Exception e) {
                    failedSites++;
                    log.warn("Unable to sync Solis devices for site {}: {}", site.getSiteCode(), e.getMessage());
                }
            }

            Map<String, Object> stats = new HashMap<>();
            stats.put("totalSites", allSites.size());
            stats.put("savedSites", savedSites);
            stats.put("receivedDevices", receivedDevices);
            stats.put("failedSites", failedSites);

            run.setStatus(failedSites == 0 ? SyncStatus.SUCCESS : (savedSites > 0 ? SyncStatus.PARTIAL_SUCCESS : SyncStatus.FAILED));
            run.setMessage(failedSites == 0 ? "Solis device sync finished" : "Solis device sync partially failed");
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

    private boolean isDeviceCacheFreshEnough() {
        List<SiteDeviceDocument> cached = siteDeviceRepository.findAllByBrand(Brand.SOLIS);
        long siteCount = siteRepository.countByBrand(Brand.SOLIS);
        if (siteCount == 0 || cached.size() < siteCount) {
            return false;
        }
        Instant minAccepted = Instant.now().minus(DEVICE_CACHE_TTL);
        return cached.stream().allMatch(doc -> doc.getLastSyncAt() != null && !doc.getLastSyncAt().isBefore(minAccepted));
    }
}
