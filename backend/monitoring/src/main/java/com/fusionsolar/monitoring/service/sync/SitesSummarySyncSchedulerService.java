package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.persistence.enums.Brand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SitesSummarySyncSchedulerService {

    private final MonitoringSitesSyncRegistry monitoringSitesSyncRegistry;
    private final HuaweiDeviceSyncService huaweiDeviceSyncService;
    private final Optional<SolisDeviceSyncService> solisDeviceSyncService;
    private final Optional<SolisHistoryWarmupService> solisHistoryWarmupService;
    private final Optional<GrowattDeviceSyncService> growattDeviceSyncService;
    private final Optional<GrowattSyncService> growattSyncService;

    @Value("${app.solis-sync.history-warmup-enabled:false}")
    private boolean solisHistoryWarmupEnabled;

    @Scheduled(cron = "${app.huawei-sync.summary-cron}", zone = "Africa/Tunis")
    public void scheduledSyncAllBrandsSitesSummary() {
        for (Brand brand : monitoringSitesSyncRegistry.getAvailableBrands()) {
            try {
                monitoringSitesSyncRegistry
                        .getRequiredSyncService(brand)
                        .syncSitesSummary();
            } catch (Exception e) {
                log.warn("Sites summary sync failed for brand {}: {}", brand, e.getMessage());
            }
        }
    }


    @Scheduled(
            initialDelayString = "${app.growatt-sync.summary-initial-delay-ms:15000}",
            fixedDelayString = "${app.growatt-sync.summary-fixed-delay-ms:600000}"
    )
    public void scheduledGrowattSitesSummarySync() {
        growattSyncService.ifPresent(service -> {
            try {
                service.syncSitesSummary();
            } catch (Exception e) {
                log.warn("Growatt sites summary sync failed: {}", e.getMessage());
            }
        });
    }

    @Scheduled(
            initialDelayString = "${app.huawei-sync.device-sync-initial-delay-ms}",
            fixedDelayString = "${app.huawei-sync.device-sync-fixed-delay-ms}"
    )
    public void scheduledHuaweiDeviceCacheSync() {
        try {
            huaweiDeviceSyncService.syncAllDevices(false);
        } catch (Exception e) {
            log.warn("Huawei device cache sync failed: {}", e.getMessage());
        }
    }

    @Scheduled(
            initialDelayString = "${app.solis-sync.device-sync-initial-delay-ms:90000}",
            fixedDelayString = "${app.solis-sync.device-sync-fixed-delay-ms:43200000}"
    )
    public void scheduledSolisDeviceCacheSync() {
        solisDeviceSyncService.ifPresent(service -> {
            try {
                service.syncAllDevices(false);
            } catch (Exception e) {
                log.warn("Solis device cache sync failed: {}", e.getMessage());
            }
        });
    }



    @Scheduled(
            initialDelayString = "${app.growatt-sync.device-sync-initial-delay-ms:120000}",
            fixedDelayString = "${app.growatt-sync.device-sync-fixed-delay-ms:43200000}"
    )
    public void scheduledGrowattDeviceCacheSync() {
        growattDeviceSyncService.ifPresent(service -> {
            try {
                service.syncAllDevices(false);
            } catch (Exception e) {
                log.warn("Growatt device cache sync failed: {}", e.getMessage());
            }
        });
    }

    @Scheduled(
            initialDelayString = "${app.solis-sync.history-current-initial-delay-ms:180000}",
            fixedDelayString = "${app.solis-sync.history-current-fixed-delay-ms:900000}"
    )
    public void scheduledSolisCurrentHistoryWarmup() {
        if (!solisHistoryWarmupEnabled) {
            return;
        }

        solisHistoryWarmupService.ifPresent(service -> {
            try {
                service.warmupCurrentHistoriesForNextSolisSites();
            } catch (Exception e) {
                log.warn("Solis current history warmup failed: {}", e.getMessage());
            }
        });
    }

    @Scheduled(
            initialDelayString = "${app.solis-sync.history-yearly-initial-delay-ms:600000}",
            fixedDelayString = "${app.solis-sync.history-yearly-fixed-delay-ms:21600000}"
    )
    public void scheduledSolisYearlyHistoryWarmup() {
        if (!solisHistoryWarmupEnabled) {
            return;
        }

        solisHistoryWarmupService.ifPresent(service -> {
            try {
                service.warmupYearlyHistoryForNextSolisSites();
            } catch (Exception e) {
                log.warn("Solis yearly history warmup failed: {}", e.getMessage());
            }
        });
    }
}