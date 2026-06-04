package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class HuaweiHistoryWarmupService {

    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");

    private final SiteRepository siteRepository;
    private final HuaweiHistorySyncService huaweiHistorySyncService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public void warmupCurrentHistoriesForAllHuaweiSites() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Huawei history warmup already running. Skipping this execution.");
            return;
        }

        try {
           List<SiteDocument> sites = siteRepository.findAllByBrandOrderBySiteNameAsc(Brand.HUAWEI)
        .stream()
        .filter(SiteSyncPolicy::isVisibleSite)
        .toList();

            if (sites.isEmpty()) {
                log.warn("No Huawei sites found for history warmup.");
                return;
            }

           
            String currentYear = String.valueOf(LocalDate.now(TUNIS_ZONE).getYear());

            int success = 0;
            int failed = 0;
for (SiteDocument site : sites) {
    try {
        huaweiHistorySyncService.getHistory(site, "year", currentYear);
        huaweiHistorySyncService.getYearlyHistory(site);

        success++;

        sleepQuietly(2000);

    } catch (Exception e) {
        failed++;

        log.warn(
                "Unable to warmup Huawei histories for site {}. Error={}",
                site.getSiteCode(),
                e.getMessage()
        );
    }
}

            log.info(
                    "Huawei history warmup finished. sites={}, success={}, failed={}",
                    sites.size(),
                    success,
                    failed
            );

        } finally {
            running.set(false);
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}