package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.document.SiteHistoryDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.enums.HistoryGranularity;
import com.fusionsolar.monitoring.persistence.repositories.SiteHistoryRepository;
import com.fusionsolar.monitoring.provider.growatt.GrowattMonitoringProvider;
import com.fusionsolar.monitoring.provider.mapper.SiteMongoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "providers.growatt", name = "enabled", havingValue = "true")
public class GrowattHistorySyncService {

    private final SiteHistoryRepository siteHistoryRepository;
    private final GrowattMonitoringProvider growattMonitoringProvider;
    private final SiteMongoMapper siteMongoMapper;

    @Value("${app.growatt-sync.min-history-provider-delay-ms:30000}")
    private long minHistoryProviderDelayMs;

    private final Object historyProviderLock = new Object();
    private Instant lastHistoryProviderCallAt = Instant.EPOCH;

    public List<HistoryPointDto> getHistory(SiteDocument site, String type, String value, boolean forceRefresh) {
        HistoryGranularity granularity = siteMongoMapper.toGranularity(type);
        SiteHistoryDocument cached = siteHistoryRepository
                .findByBrandAndProviderAccountGroupAndSiteCodeAndGranularityAndBucket(
                        Brand.GROWATT,
                        accountGroupOf(site),
                        site.getSiteCode(),
                        granularity,
                        value
                )
                .orElseGet(() -> siteHistoryRepository
                        .findByBrandAndSiteCodeAndGranularityAndBucket(Brand.GROWATT, site.getSiteCode(), granularity, value)
                        .orElse(null));

        try {
            waitForHistoryProviderSlot();
            List<HistoryPointDto> points = growattMonitoringProvider.getHistory(site.getSiteCode(), type, value);
            SiteHistoryDocument doc = siteMongoMapper.toSiteHistoryDocument(
                    Brand.GROWATT,
                    site.getSiteCode(),
                    granularity,
                    value,
                    points,
                    cached,
                    Instant.now()
            );
            doc.setProviderAccountKey(site.getProviderAccountKey());
            doc.setProviderAccountGroup(accountGroupOf(site));
            siteHistoryRepository.save(doc);
            return points;
        } catch (Exception e) {
            if (forceRefresh) {
                throw e;
            }
            if (cached != null && cached.getPoints() != null) {
                return cached.getPoints();
            }
            log.warn("Growatt history unavailable for site={}, type={}, value={}: {}", site.getSiteCode(), type, value, e.getMessage());
            return List.of();
        }
    }

    private void waitForHistoryProviderSlot() {
        if (minHistoryProviderDelayMs <= 0) {
            return;
        }
        synchronized (historyProviderLock) {
            long elapsedMs = Duration.between(lastHistoryProviderCallAt, Instant.now()).toMillis();
            long waitMs = minHistoryProviderDelayMs - elapsedMs;
            if (waitMs > 0) {
                sleep(waitMs);
            }
            lastHistoryProviderCallAt = Instant.now();
        }
    }

    private String accountGroupOf(SiteDocument site) {
        if (site != null && site.getProviderAccountGroup() != null && !site.getProviderAccountGroup().isBlank()) {
            return site.getProviderAccountGroup();
        }
        if (site != null && site.getProviderAccountKey() != null && !site.getProviderAccountKey().isBlank()) {
            return site.getProviderAccountKey();
        }
        return "primary";
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
