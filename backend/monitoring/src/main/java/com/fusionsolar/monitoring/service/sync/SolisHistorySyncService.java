package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.document.SiteHistoryDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.enums.HistoryGranularity;
import com.fusionsolar.monitoring.persistence.repositories.SiteHistoryRepository;
import com.fusionsolar.monitoring.provider.mapper.SiteMongoMapper;
import com.fusionsolar.monitoring.provider.solis.SolisMonitoringProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "providers.solis", name = "enabled", havingValue = "true")
public class SolisHistorySyncService {

    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");

    private static final long CURRENT_DAY_HISTORY_TTL_MS = 15 * 60 * 1000L;
    private static final long CURRENT_MONTH_HISTORY_TTL_MS = 60 * 60 * 1000L;
    private static final long CURRENT_YEAR_HISTORY_TTL_MS = 6 * 60 * 60 * 1000L;
    private static final long YEARLY_HISTORY_TTL_MS = 24 * 60 * 60 * 1000L;

    private final SiteHistoryRepository siteHistoryRepository;
    private final SolisMonitoringProvider solisMonitoringProvider;
    private final SiteMongoMapper siteMongoMapper;

    @Value("${app.solis-sync.min-history-provider-delay-ms:1300}")
    private long minHistoryProviderDelayMs;

    @Value("${app.history-backfill.max-lookback-months:120}")
    private int maxLookbackMonths;

    @Value("${app.history-backfill.incomplete-month-refresh-ms:21600000}")
    private long incompleteMonthRefreshMs;

    @Value("${app.history-backfill.month-finalization-grace-hours:6}")
    private int monthFinalizationGraceHours;

    private final Object historyProviderLock = new Object();
    private Instant lastHistoryProviderCallAt = Instant.EPOCH;

    public List<HistoryPointDto> getHistory(SiteDocument site, String type, String value) {
        return getHistory(site, type, value, false);
    }

    public List<HistoryPointDto> getHistory(SiteDocument site, String type, String value, boolean forceRefresh) {
        String key = "Solis|" + site.getProviderAccountGroup() + "|" + site.getSiteCode() + "|" + type + "|" + value;
        return HistorySingleFlight.execute(key, () -> loadHistory(site, type, value, forceRefresh));
    }

    private List<HistoryPointDto> loadHistory(SiteDocument site, String type, String value, boolean forceRefresh) {
        HistoryGranularity granularity = siteMongoMapper.toGranularity(type);

        SiteHistoryDocument cached = siteHistoryRepository
                .findByBrandAndSiteCodeAndGranularityAndBucket(
                        Brand.SOLIS,
                        site.getSiteCode(),
                        granularity,
                        value
                )
                .orElse(null);

        if (!forceRefresh && isHistoryFresh(site, cached, granularity, value)) {
            return clampMonthPoints(
                    site,
                    granularity,
                    value,
                    safePoints(cached)
            );
        }

        try {
            waitForHistoryProviderSlot();

            List<HistoryPointDto> loadedPoints = solisMonitoringProvider.getHistory(
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

            SiteHistoryDocument document = siteMongoMapper.toSiteHistoryDocument(
                    Brand.SOLIS,
                    site.getSiteCode(),
                    granularity,
                    value,
                    pointsToSave,
                    cached,
                    Instant.now()
            );
            document.setProviderAccountKey(site.getProviderAccountKey());
            document.setProviderAccountGroup(site.getProviderAccountGroup());
            if (loadedPoints != null && !loadedPoints.isEmpty()) {
                HistoryCollectionStateSupport.markSuccessful(document, Instant.now());
            }
            siteHistoryRepository.save(document);

            logMonthSaveStatus(site, granularity, value, loadedPoints, pointsToSave, document);

            return pointsToSave;

        } catch (Exception e) {
            if (forceRefresh) {
                throw new RuntimeException(
                        "Solis history refresh failed for site="
                                + site.getSiteCode()
                                + ", type="
                                + type
                                + ", value="
                                + value,
                        e
                );
            }

            if (cached != null) {
                log.warn(
                        "Solis history refresh failed for site={}, type={}, value={}. Returning cached data. Cause={}",
                        site.getSiteCode(),
                        type,
                        value,
                        e.getMessage()
                );

                return clampMonthPoints(
                        site,
                        granularity,
                        value,
                        safePoints(cached)
                );
            }

            log.warn(
                    "Solis history unavailable for site={}, type={}, value={}. Returning empty data to keep UI stable. Cause={}",
                    site.getSiteCode(),
                    type,
                    value,
                    e.getMessage()
            );
            return List.of();
        }
    }

    public List<HistoryPointDto> getYearlyHistory(SiteDocument site) {
        String bucket = "all";

        SiteHistoryDocument cached = siteHistoryRepository
                .findByBrandAndSiteCodeAndGranularityAndBucket(
                        Brand.SOLIS,
                        site.getSiteCode(),
                        HistoryGranularity.YEARLY,
                        bucket
                )
                .orElse(null);

        if (isYearlyHistoryFresh(cached)) {
            return safePoints(cached);
        }

        try {
            waitForHistoryProviderSlot();

            List<HistoryPointDto> loadedPoints =
                    solisMonitoringProvider.getYearlyHistory(site.getSiteCode());

            List<HistoryPointDto> pointsToSave = HistoryBackfillCompletenessSupport.mergeHistoryPoints(
                    cached != null ? cached.getPoints() : List.of(),
                    loadedPoints,
                    HistoryGranularity.YEARLY,
                    TUNIS_ZONE
            );

            SiteHistoryDocument document = siteMongoMapper.toSiteHistoryDocument(
                    Brand.SOLIS,
                    site.getSiteCode(),
                    HistoryGranularity.YEARLY,
                    bucket,
                    pointsToSave,
                    cached,
                    Instant.now()
            );
            document.setProviderAccountKey(site.getProviderAccountKey());
            document.setProviderAccountGroup(site.getProviderAccountGroup());
            if (loadedPoints != null && !loadedPoints.isEmpty()) {
                HistoryCollectionStateSupport.markSuccessful(document, Instant.now());
            }
            siteHistoryRepository.save(document);

            return pointsToSave;

        } catch (Exception e) {
            if (cached != null) {
                log.warn(
                        "Solis yearly history refresh failed for site={}. Returning cached data. Cause={}",
                        site.getSiteCode(),
                        e.getMessage()
                );
                return safePoints(cached);
            }

            log.warn(
                    "Solis yearly history unavailable for site={}. Returning empty data to keep UI stable. Cause={}",
                    site.getSiteCode(),
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
                "Solis MONTH bucket saved site={} bucket={} loadedPoints={} savedPoints={} status={} collected={}/{} present={} missingDays={} zeroOrNullDays={} missingOrInvalid={} expectedRange={}..{}",
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
            Instant now = Instant.now();
            long elapsedMs = Duration.between(lastHistoryProviderCallAt, now).toMillis();
            long waitMs = minHistoryProviderDelayMs - elapsedMs;

            if (waitMs > 0) {
                sleep(waitMs);
            }

            lastHistoryProviderCallAt = Instant.now();
        }
    }

    private boolean isHistoryFresh(
            SiteDocument site,
            SiteHistoryDocument cached,
            HistoryGranularity granularity,
            String bucket
    ) {
        if (cached == null || cached.getLastSyncAt() == null) {
            return false;
        }

        if (granularity == HistoryGranularity.MONTH) {
            return !HistoryBackfillCompletenessSupport.isMonthHistoryDue(
                    site,
                    cached,
                    bucket,
                    LocalDate.now(TUNIS_ZONE),
                    maxLookbackMonths,
                    86_400_000L,
                    incompleteMonthRefreshMs,
                    incompleteMonthRefreshMs,
                    CURRENT_MONTH_HISTORY_TTL_MS,
                    monthFinalizationGraceHours,
                    TUNIS_ZONE
            );
        }

        if (!isCurrentBucket(granularity, bucket)) {
            return cached.getPoints() != null;
        }

        long ttlMs = resolveTtlMs(granularity);
        Instant minAcceptedInstant = Instant.now().minusMillis(ttlMs);

        return !cached.getLastSyncAt().isBefore(minAcceptedInstant);
    }

    private boolean isYearlyHistoryFresh(SiteHistoryDocument cached) {
        if (cached == null || cached.getLastSyncAt() == null) {
            return false;
        }

        Instant minAcceptedInstant = Instant.now().minusMillis(YEARLY_HISTORY_TTL_MS);
        return !cached.getLastSyncAt().isBefore(minAcceptedInstant);
    }

    private boolean isCurrentBucket(HistoryGranularity granularity, String bucket) {
        String today = LocalDate.now(TUNIS_ZONE).toString();
        String currentMonth = YearMonth.now(TUNIS_ZONE).toString();
        String currentYear = String.valueOf(LocalDate.now(TUNIS_ZONE).getYear());

        return switch (granularity) {
            case DAY -> today.equals(bucket);
            case MONTH -> currentMonth.equals(bucket);
            case YEAR -> currentYear.equals(bucket);
            case YEARLY -> true;
        };
    }

    private long resolveTtlMs(HistoryGranularity granularity) {
        return switch (granularity) {
            case DAY -> CURRENT_DAY_HISTORY_TTL_MS;
            case MONTH -> CURRENT_MONTH_HISTORY_TTL_MS;
            case YEAR -> CURRENT_YEAR_HISTORY_TTL_MS;
            case YEARLY -> YEARLY_HISTORY_TTL_MS;
        };
    }

    private List<HistoryPointDto> safePoints(SiteHistoryDocument cached) {
        return cached != null && cached.getPoints() != null ? cached.getPoints() : List.of();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
