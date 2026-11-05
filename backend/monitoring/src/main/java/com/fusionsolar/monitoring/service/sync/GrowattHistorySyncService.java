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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "providers.growatt", name = "enabled", havingValue = "true")
public class GrowattHistorySyncService {

    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");

    private final SiteHistoryRepository siteHistoryRepository;
    private final GrowattMonitoringProvider growattMonitoringProvider;
    private final SiteMongoMapper siteMongoMapper;

    @Value("${app.growatt-sync.min-history-provider-delay-ms:30000}")
    private long minHistoryProviderDelayMs;

    @Value("${app.history-backfill.max-lookback-months:120}")
    private int maxLookbackMonths;

    private final Object historyProviderLock = new Object();
    private Instant lastHistoryProviderCallAt = Instant.EPOCH;

    public List<HistoryPointDto> getHistory(
            SiteDocument site,
            String type,
            String value,
            boolean forceRefresh
    ) {
        String key = "Growatt|" + site.getProviderAccountGroup() + "|" + site.getSiteCode() + "|" + type + "|" + value;
        return HistorySingleFlight.execute(key, () -> loadHistory(site, type, value, forceRefresh));
    }

    private List<HistoryPointDto> loadHistory(
            SiteDocument site,
            String type,
            String value,
            boolean forceRefresh
    ) {
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
                        .findByBrandAndSiteCodeAndGranularityAndBucket(
                                Brand.GROWATT,
                                site.getSiteCode(),
                                granularity,
                                value
                        )
                        .orElse(null));

        try {
            waitForHistoryProviderSlot();

            List<HistoryPointDto> loadedPoints = growattMonitoringProvider.getHistory(
                    site.getSiteCode(),
                    type,
                    value
            );

            List<HistoryPointDto> mergedPoints = HistoryBackfillCompletenessSupport.mergeHistoryPoints(
                    cached != null ? cached.getPoints() : List.of(),
                    loadedPoints,
                    granularity,
                    TUNIS_ZONE
            );

            List<HistoryPointDto> pointsToSave = clampMonthPoints(
                    site,
                    granularity,
                    value,
                    mergedPoints
            );

            SiteHistoryDocument doc = siteMongoMapper.toSiteHistoryDocument(
                    Brand.GROWATT,
                    site.getSiteCode(),
                    granularity,
                    value,
                    pointsToSave,
                    cached,
                    Instant.now()
            );

            doc.setProviderAccountKey(site.getProviderAccountKey());
            doc.setProviderAccountGroup(accountGroupOf(site));

            if (loadedPoints != null && !loadedPoints.isEmpty()) {
                HistoryCollectionStateSupport.markSuccessful(doc, Instant.now());
            }

            siteHistoryRepository.save(doc);

            logMonthSaveStatus(
                    site,
                    granularity,
                    value,
                    loadedPoints,
                    pointsToSave,
                    doc
            );

            return pointsToSave;

        } catch (Exception e) {
            if (forceRefresh) {
                throw new RuntimeException(
                        "Growatt history refresh failed for site="
                                + site.getSiteCode()
                                + ", type="
                                + type
                                + ", value="
                                + value,
                        e
                );
            }

            if (cached != null && cached.getPoints() != null) {
                log.warn(
                        "Growatt history refresh failed for site={}, type={}, value={}. Returning cached data. Cause={}",
                        site.getSiteCode(),
                        type,
                        value,
                        e.getMessage()
                );

                return clampMonthPoints(
                        site,
                        granularity,
                        value,
                        cached.getPoints()
                );
            }

            log.warn(
                    "Growatt history unavailable for site={}, type={}, value={}. Returning empty data. Cause={}",
                    site.getSiteCode(),
                    type,
                    value,
                    e.getMessage()
            );

            return List.of();
        }
    }

    private List<HistoryPointDto> clampMonthPoints(
            SiteDocument site,
            HistoryGranularity granularity,
            String bucket,
            List<HistoryPointDto> points
    ) {
        List<HistoryPointDto> safe = points != null ? points : List.of();

        if (granularity != HistoryGranularity.MONTH) {
            return safe;
        }

        return HistoryBackfillCompletenessSupport.clampMonthPointsToExpectedWindow(
                site,
                bucket,
                safe,
                LocalDate.now(TUNIS_ZONE),
                maxLookbackMonths,
                TUNIS_ZONE
        );
    }

    private void logMonthSaveStatus(
            SiteDocument site,
            HistoryGranularity granularity,
            String bucket,
            List<HistoryPointDto> loadedPoints,
            List<HistoryPointDto> savedPoints,
            SiteHistoryDocument document
    ) {
        if (granularity != HistoryGranularity.MONTH) {
            return;
        }

        HistoryBackfillCompletenessSupport.MonthCompleteness completeness =
                HistoryBackfillCompletenessSupport.inspectMonth(
                        site,
                        document,
                        bucket,
                        LocalDate.now(TUNIS_ZONE),
                        maxLookbackMonths,
                        TUNIS_ZONE
                );

        log.info(
                "Growatt MONTH bucket saved site={} bucket={} loadedPoints={} savedPoints={} status={} collected={}/{} present={} missingDays={} zeroOrNullDays={} missingOrInvalid={} expectedRange={}..{}",
                site.getSiteCode(),
                bucket,
                loadedPoints == null ? 0 : loadedPoints.size(),
                savedPoints == null ? 0 : savedPoints.size(),
                HistoryBackfillCompletenessSupport.monthStatusLabel(completeness),
                completeness.collectedDays(),
                completeness.expectedDays(),
                completeness.presentDays(),
                completeness.missingDays(),
                completeness.invalidValueDays(),
                completeness.missingOrInvalidDays(),
                completeness.expectedStart(),
                completeness.expectedEnd()
        );
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
        if (site != null
                && site.getProviderAccountGroup() != null
                && !site.getProviderAccountGroup().isBlank()) {
            return site.getProviderAccountGroup();
        }

        if (site != null
                && site.getProviderAccountKey() != null
                && !site.getProviderAccountKey().isBlank()) {
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
