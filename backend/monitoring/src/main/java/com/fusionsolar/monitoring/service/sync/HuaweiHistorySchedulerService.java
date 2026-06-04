package com.fusionsolar.monitoring.service.sync;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class HuaweiHistorySchedulerService {

    private final HuaweiYearEnergySyncService huaweiYearEnergySyncService;

    @Value("${app.huawei-sync.year-energy-enabled:false}")
    private boolean yearEnergyEnabled;

    /*
     * Sync annuel Huawei progressif.
     *
     * Pendant le backfill historique, il est conseillé de désactiver ce scheduler,
     * car SiteHistoryBackfillService charge déjà les historiques MONTH/YEAR.
     *
     * Si ce service reste actif en même temps que le backfill, les deux peuvent appeler
     * /thirdData/getKpiStationMonth et provoquer ACCESS_FREQUENCY_IS_TOO_HIGH.
     */
    @Scheduled(
            initialDelayString = "${app.huawei-sync.year-energy-initial-delay-ms:900000}",
            fixedDelayString = "${app.huawei-sync.year-energy-fixed-delay-ms:3600000}"
    )
    public void syncHuaweiYearEnergyBatch() {
        if (!yearEnergyEnabled) {
            log.debug("Huawei year energy scheduled batch skipped: disabled.");
            return;
        }

        try {
            huaweiYearEnergySyncService.syncDueCurrentYearEnergyBatch();
        } catch (Exception e) {
            log.warn("Huawei year energy scheduled batch failed: {}", e.getMessage());
        }
    }
}