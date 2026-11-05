package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.dto.SiteSummaryDto;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.document.SyncRunDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.enums.SyncStatus;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import com.fusionsolar.monitoring.persistence.repositories.SyncRunRepository;
import com.fusionsolar.monitoring.provider.mapper.SiteMongoMapper;
import com.fusionsolar.monitoring.provider.solis.SolisMonitoringProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "providers.solis", name = "enabled", havingValue = "true")
public class SolisSyncService implements MonitoringSitesSyncService {

    private final SolisMonitoringProvider solisMonitoringProvider;
    private final SiteRepository siteRepository;
    private final SyncRunRepository syncRunRepository;
    private final SiteMongoMapper siteMongoMapper;

    private final AtomicBoolean summarySyncRunning = new AtomicBoolean(false);

    @Value("${app.solis-sync.min-summary-refresh-interval-ms:285000}")
    private long minSummaryRefreshIntervalMs;

    private volatile Instant lastSummarySyncStartedAt = Instant.EPOCH;

    @Override
    public Brand getBrand() {
        return Brand.SOLIS;
    }

    @Override
    public void syncSitesSummary() {
        Instant requestedAt = Instant.now();

        if (!summarySyncRunning.compareAndSet(false, true)) {
            log.info("Solis sites sync skipped: another sync is already running.");
            return;
        }

        try {
            long elapsedMs = requestedAt.toEpochMilli() - lastSummarySyncStartedAt.toEpochMilli();

            if (elapsedMs >= 0 && elapsedMs < minSummaryRefreshIntervalMs) {
                long waitMs = minSummaryRefreshIntervalMs - elapsedMs;

                log.info(
                        "Solis sites sync skipped: last SolisCloud poll started {} ms ago. Next safe poll in about {} ms.",
                        elapsedMs,
                        waitMs
                );

                return;
            }

            lastSummarySyncStartedAt = requestedAt;
            runSitesSummarySync(requestedAt);

        } finally {
            summarySyncRunning.set(false);
        }
    }

  private void runSitesSummarySync(Instant startedAt) {
    SyncRunDocument run = SyncRunDocument.builder()
            .brand(Brand.SOLIS)
            .type("SITES_SUMMARY")
            .status(SyncStatus.RUNNING)
            .message("Solis sync started")
            .startedAt(startedAt)
            .build();

    run = syncRunRepository.save(run);

    int savedCount = 0;
    int failedSaveCount = 0;
    int reactivatedCount = 0;
    int deactivatedCount = 0;
    int invalidCount = 0;
    int statesNotRefreshed = 0;

    try {
        List<SiteSummaryDto> summaries = solisMonitoringProvider.getSitesSummary();
        Instant now = Instant.now();

        Set<String> receivedSiteCodes = new HashSet<>();

        for (SiteSummaryDto summary : summaries) {
            if (summary == null || isBlank(summary.getSiteCode())) {
                invalidCount++;
                continue;
            }

            if (summary.getStatus() == null || "unknown".equalsIgnoreCase(summary.getStatus()) || summary.getStatus().isBlank()) statesNotRefreshed++;
            String siteCode = summary.getSiteCode().trim();
            summary.setSiteCode(siteCode);

            receivedSiteCodes.add(siteCode);

            try {
                SiteDocument existing = siteRepository.findByBrandAndSiteCode(
                        Brand.SOLIS,
                        siteCode
                ).orElse(null);

                boolean wasInactive = existing != null && Boolean.FALSE.equals(existing.getActive());

                SiteDocument document = siteMongoMapper.toSiteDocument(
                        Brand.SOLIS,
                        summary,
                        existing,
                        now
                );

                document.setActive(true);
                document.setMissingSince(null);
                document.setMissingConfirmations(0);
                document.setDeletedAt(null);
                document.setUpdatedAt(now);

                siteRepository.save(document);
                savedCount++;

                if (wasInactive) {
                    reactivatedCount++;
                }

            } catch (Exception e) {
                failedSaveCount++;
                log.warn("Unable to save Solis site {}: {}", siteCode, e.getMessage());
            }
        }

        deactivatedCount = failedSaveCount == 0 && invalidCount == 0 ? deactivateMissingSites(
                Brand.SOLIS,
                receivedSiteCodes,
                now
        ) : 0;

        Map<String, Object> stats = new HashMap<>();
        stats.put("receivedFromProvider", summaries.size());
        stats.put("validReceived", receivedSiteCodes.size());
        stats.put("invalid", invalidCount);
        stats.put("statesNotRefreshed", statesNotRefreshed);
        stats.put("saved", savedCount);
        stats.put("failedToSave", failedSaveCount);
        stats.put("reactivated", reactivatedCount);
        stats.put("deactivated", deactivatedCount);
        stats.put("minimumRefreshIntervalMs", minSummaryRefreshIntervalMs);
        stats.put("pollStartedAt", startedAt.toString());

        boolean fullSuccess = failedSaveCount == 0 && invalidCount == 0 && statesNotRefreshed == 0;

        run.setStatus(fullSuccess ? SyncStatus.SUCCESS : SyncStatus.PARTIAL_SUCCESS);
        run.setMessage("Solis sync completed");
        run.setStats(stats);
        run.setFinishedAt(Instant.now());

        syncRunRepository.save(run);

        log.info(
                "Solis sites sync finished. received={}, saved={}, reactivated={}, deactivated={}, failed={}",
                summaries.size(),
                savedCount,
                reactivatedCount,
                deactivatedCount,
                failedSaveCount
        );

    } catch (Exception e) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("saved", savedCount);
        stats.put("failedToSave", failedSaveCount);
        stats.put("reactivated", reactivatedCount);
        stats.put("deactivated", deactivatedCount);
        stats.put("minimumRefreshIntervalMs", minSummaryRefreshIntervalMs);
        stats.put("pollStartedAt", startedAt.toString());

        run.setStatus(savedCount > 0 ? SyncStatus.PARTIAL_SUCCESS : SyncStatus.FAILED);
        run.setMessage(e.getMessage() != null ? e.getMessage() : "Solis sync failed");
        run.setStats(stats);
        run.setFinishedAt(Instant.now());

        syncRunRepository.save(run);

        throw e;
    }
}

    private int deactivateMissingSites(
            Brand brand,
            Set<String> receivedSiteCodes,
            Instant now
    ) {
        // Called only after the strict provider inventory completed successfully.
        if (receivedSiteCodes == null) {
            log.warn("Solis missing-site deactivation skipped because provider returned no valid site codes.");
            return 0;
        }

        List<SiteDocument> existingSites = siteRepository.findAllByBrandOrderBySiteNameAsc(brand);

        int deactivated = 0;

        for (SiteDocument site : existingSites) {
            if (site == null || isBlank(site.getSiteCode())) {
                continue;
            }

            String siteCode = site.getSiteCode().trim();

            if (receivedSiteCodes.contains(siteCode)) {
                continue;
            }

            if (Boolean.FALSE.equals(site.getActive())) {
                continue;
            }

            boolean confirmed = SiteMissingPolicy.confirmMissing(site, now);
            siteRepository.save(site);
            if (confirmed) deactivated++;

            if (confirmed) log.warn(
                    "Solis site {} marked inactive after two complete inventories without it.",
                    siteCode
            );
        }

        return deactivated;
    }

   @Override
public void bootstrapIfEmpty() {
    boolean hasVisibleSolisSites = siteRepository.findAllByBrandOrderBySiteNameAsc(Brand.SOLIS)
            .stream()
            .anyMatch(SiteSyncPolicy::isVisibleSite);

    if (!hasVisibleSolisSites) {
        log.info("Mongo Solis visible sites collection is empty. Starting bootstrap sync.");
        syncSitesSummary();
    }
}

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}