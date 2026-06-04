package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.dto.SiteSummaryDto;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.document.SyncRunDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.enums.SyncStatus;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import com.fusionsolar.monitoring.persistence.repositories.SyncRunRepository;
import com.fusionsolar.monitoring.provider.huawei.HuaweiMonitoringProvider;
import com.fusionsolar.monitoring.provider.mapper.SiteMongoMapper;
import com.fusionsolar.monitoring.service.HuaweiLiveStationService;
import com.fusionsolar.monitoring.service.HuaweiLiveStationService.AccountSitesSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
public class HuaweiSyncService implements MonitoringSitesSyncService {

    private final HuaweiMonitoringProvider huaweiMonitoringProvider;
    private final SiteRepository siteRepository;
    private final SyncRunRepository syncRunRepository;
    private final SiteMongoMapper siteMongoMapper;
    private final HuaweiLiveStationService huaweiLiveStationService;

    private final AtomicBoolean summarySyncRunning = new AtomicBoolean(false);

    @Value("${app.huawei-sync.min-summary-refresh-interval-ms:285000}")
    private long minSummaryRefreshIntervalMs;

    private volatile Instant lastSummarySyncStartedAt = Instant.EPOCH;

    @Override
    public Brand getBrand() {
        return Brand.HUAWEI;
    }

    @Override
    public void syncSitesSummary() {
        Instant requestedAt = Instant.now();

        if (!summarySyncRunning.compareAndSet(false, true)) {
            log.info("Huawei sites sync skipped: another sync is already running.");
            return;
        }

        try {
            long elapsedMs = requestedAt.toEpochMilli() - lastSummarySyncStartedAt.toEpochMilli();

            if (elapsedMs >= 0 && elapsedMs < minSummaryRefreshIntervalMs) {
                long waitMs = minSummaryRefreshIntervalMs - elapsedMs;

                log.info(
                        "Huawei sites sync skipped: last FusionSolar poll started {} ms ago. Next safe poll in about {} ms.",
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
                .brand(Brand.HUAWEI)
                .type("SITES_SUMMARY")
                .status(SyncStatus.RUNNING)
                .message("Huawei sync started")
                .startedAt(startedAt)
                .build();

        run = syncRunRepository.save(run);

        int receivedCount = 0;
        int savedCount = 0;
        int failedSaveCount = 0;
        int reactivatedCount = 0;
        int deactivatedCount = 0;
        int invalidCount = 0;
        int failedGroups = 0;
        int liveKpiUpdatedCount = 0;

        try {
            List<AccountSitesSummary> groupedSummaries = huaweiMonitoringProvider.getSitesSummaryByAccountGroup();
            Instant now = Instant.now();

            Set<String> receivedSiteIdentities = new HashSet<>();
            Set<String> completedGroups = new HashSet<>();

            for (AccountSitesSummary accountSites : groupedSummaries) {
                String accountKey = normalizeAccountKey(accountSites.accountKey());
                String accountGroup = normalizeAccountGroup(accountSites.accountGroup(), accountKey);
                List<SiteSummaryDto> summaries = accountSites.sites() != null ? accountSites.sites() : List.of();

                receivedCount += summaries.size();

                if (summaries.isEmpty()) {
                    failedGroups++;
                    log.warn("Huawei account group {} returned no site summary.", accountGroup);
                    continue;
                }

                completedGroups.add(accountGroup);

                for (SiteSummaryDto summary : summaries) {
                    if (summary == null || isBlank(summary.getSiteCode())) {
                        invalidCount++;
                        continue;
                    }

                    String siteCode = summary.getSiteCode().trim();
                    receivedSiteIdentities.add(identity(accountGroup, siteCode));

                    try {
                        SiteDocument existing = findExistingSite(accountGroup, siteCode);
                        boolean wasInactive = existing != null && Boolean.FALSE.equals(existing.getActive());

                        SiteDocument document = siteMongoMapper.toSiteDocument(
                                Brand.HUAWEI,
                                summary,
                                existing,
                                now
                        );

                        document.setProviderAccountKey(accountKey);
                        document.setProviderAccountGroup(accountGroup);
                        document.setActive(true);
                        document.setMissingSince(null);
                        document.setDeletedAt(null);
                        document.setUpdatedAt(now);

                        siteRepository.save(document);
                        savedCount++;

                        if (wasInactive) {
                            reactivatedCount++;
                        }

                        log.debug(
                                "Saving Huawei site group={} code={} -> day={}, month={}, year={}, total={}",
                                accountGroup,
                                siteCode,
                                summary.getDayEnergyKwh(),
                                summary.getMonthEnergyKwh(),
                                summary.getYearEnergyKwh(),
                                summary.getTotalEnergyKwh()
                        );

                    } catch (Exception e) {
                        failedSaveCount++;
                        log.warn("Unable to save Huawei site group={} code={}: {}", accountGroup, siteCode, e.getMessage());
                    }
                }

                // Après l'insertion rapide des sites, on enrichit le même groupe avec
                // /thirdData/getStationRealKpi. Le rate limiter par group attendra
                // automatiquement le bon délai Huawei avant cet appel.
                liveKpiUpdatedCount += enrichGroupWithLiveKpis(accountKey, accountGroup);
            }

            deactivatedCount = deactivateMissingSites(
                    Brand.HUAWEI,
                    receivedSiteIdentities,
                    completedGroups,
                    now
            );

            Map<String, Object> stats = new HashMap<>();
            stats.put("received", receivedCount);
            stats.put("validReceived", receivedSiteIdentities.size());
            stats.put("completedGroups", completedGroups);
            stats.put("invalid", invalidCount);
            stats.put("saved", savedCount);
            stats.put("failedToSave", failedSaveCount);
            stats.put("failedGroups", failedGroups);
            stats.put("liveKpiUpdated", liveKpiUpdatedCount);
            stats.put("reactivated", reactivatedCount);
            stats.put("deactivated", deactivatedCount);
            stats.put("minimumRefreshIntervalMs", minSummaryRefreshIntervalMs);
            stats.put("pollStartedAt", startedAt.toString());

            boolean fullSuccess = failedSaveCount == 0 && invalidCount == 0 && failedGroups == 0;

            run.setStatus(fullSuccess ? SyncStatus.SUCCESS : SyncStatus.PARTIAL_SUCCESS);
            run.setMessage("Huawei sync completed");
            run.setStats(stats);
            run.setFinishedAt(Instant.now());

            syncRunRepository.save(run);

            log.info(
                    "Huawei sites sync finished. received={}, saved={}, liveKpiUpdated={}, reactivated={}, deactivated={}, failed={}",
                    receivedCount,
                    savedCount,
                    liveKpiUpdatedCount,
                    reactivatedCount,
                    deactivatedCount,
                    failedSaveCount
            );

        } catch (Exception e) {
            Map<String, Object> stats = new HashMap<>();
            stats.put("received", receivedCount);
            stats.put("saved", savedCount);
            stats.put("failedToSave", failedSaveCount);
            stats.put("reactivated", reactivatedCount);
            stats.put("deactivated", deactivatedCount);
            stats.put("minimumRefreshIntervalMs", minSummaryRefreshIntervalMs);
            stats.put("pollStartedAt", startedAt.toString());

            run.setStatus(savedCount > 0 ? SyncStatus.PARTIAL_SUCCESS : SyncStatus.FAILED);
            run.setMessage(e.getMessage() != null ? e.getMessage() : "Huawei sync failed");
            run.setStats(stats);
            run.setFinishedAt(Instant.now());

            syncRunRepository.save(run);

            throw e;
        }
    }

    private int enrichGroupWithLiveKpis(String accountKey, String accountGroup) {
        if (isBlank(accountKey) || isBlank(accountGroup)) {
            return 0;
        }

        try {
            List<SiteSummaryDto> liveSummaries = huaweiLiveStationService.getSitesSummary(accountKey);
            int updated = 0;

            for (SiteSummaryDto summary : liveSummaries != null ? liveSummaries : List.<SiteSummaryDto>of()) {
                if (summary == null || isBlank(summary.getSiteCode())) {
                    continue;
                }

                String siteCode = summary.getSiteCode().trim();

                if (!hasLiveKpiValues(summary)) {
                    continue;
                }

                SiteDocument existing = findExistingSite(accountGroup, siteCode);

                if (existing == null) {
                    continue;
                }

                Instant enrichmentTime = Instant.now();
                SiteDocument document = siteMongoMapper.toSiteDocument(
                        Brand.HUAWEI,
                        summary,
                        existing,
                        enrichmentTime
                );

                document.setProviderAccountKey(accountKey);
                document.setProviderAccountGroup(accountGroup);
                document.setActive(true);
                document.setMissingSince(null);
                document.setDeletedAt(null);
                document.setUpdatedAt(enrichmentTime);

                siteRepository.save(document);
                updated++;
            }

            log.info(
                    "Huawei live KPI enrichment finished group={} account={} received={} updated={}",
                    accountGroup,
                    accountKey,
                    liveSummaries != null ? liveSummaries.size() : 0,
                    updated
            );

            return updated;

        } catch (Exception e) {
            log.warn(
                    "Huawei live KPI enrichment skipped group={} account={}: {}",
                    accountGroup,
                    accountKey,
                    e.getMessage()
            );
            return 0;
        }
    }


    private boolean hasLiveKpiValues(SiteSummaryDto summary) {
        if (summary == null) {
            return false;
        }

        String status = summary.getStatus();
        if (status != null && !status.isBlank() && !"unknown".equalsIgnoreCase(status.trim())) {
            return true;
        }

        return summary.getDayEnergyKwh() != null
                || summary.getMonthEnergyKwh() != null
                || summary.getYearEnergyKwh() != null
                || summary.getTotalEnergyKwh() != null;
    }

    private SiteDocument findExistingSite(String accountGroup, String siteCode) {
        SiteDocument existing = siteRepository
                .findByBrandAndProviderAccountGroupAndSiteCode(Brand.HUAWEI, accountGroup, siteCode)
                .orElse(null);

        if (existing != null) {
            return existing;
        }

        // Compatibilité anciennes données avant l'ajout de providerAccountGroup.
        return siteRepository.findByBrandAndSiteCode(Brand.HUAWEI, siteCode).orElse(null);
    }

    private int deactivateMissingSites(
            Brand brand,
            Set<String> receivedSiteIdentities,
            Set<String> completedGroups,
            Instant now
    ) {
        if (receivedSiteIdentities == null || receivedSiteIdentities.isEmpty() || completedGroups == null || completedGroups.isEmpty()) {
            log.warn("Huawei missing-site deactivation skipped because provider returned no valid site identities.");
            return 0;
        }

        List<SiteDocument> existingSites = siteRepository.findAllByBrandOrderBySiteNameAsc(brand);

        int deactivated = 0;

        for (SiteDocument site : existingSites) {
            if (site == null || isBlank(site.getSiteCode())) {
                continue;
            }

            String siteGroup = normalizeAccountGroup(site.getProviderAccountGroup(), site.getProviderAccountKey());
            String siteCode = site.getSiteCode().trim();

            if (!completedGroups.contains(siteGroup)) {
                continue;
            }

            if (receivedSiteIdentities.contains(identity(siteGroup, siteCode))) {
                continue;
            }

            if (Boolean.FALSE.equals(site.getActive())) {
                continue;
            }

            site.setActive(false);
            site.setSwapDate(null);
            site.setMissingSince(site.getMissingSince() != null ? site.getMissingSince() : now);
            site.setDeletedAt(now);
            site.setUpdatedAt(now);

            siteRepository.save(site);
            deactivated++;

            log.warn(
                    "Huawei site group={} code={} marked inactive because it is no longer returned by FusionSolar.",
                    siteGroup,
                    siteCode
            );
        }

        return deactivated;
    }

    @Override
    public void bootstrapIfEmpty() {
        boolean hasVisibleHuaweiSites = siteRepository.findAllByBrandOrderBySiteNameAsc(Brand.HUAWEI)
                .stream()
                .anyMatch(SiteSyncPolicy::isVisibleSite);

        if (!hasVisibleHuaweiSites) {
            log.info("Mongo Huawei visible sites collection is empty. Starting bootstrap sync.");
            syncSitesSummary();
        }
    }

    private String identity(String accountGroup, String siteCode) {
        return normalizeAccountGroup(accountGroup, null) + "|" + siteCode.trim();
    }

    private String normalizeAccountKey(String value) {
        return isBlank(value) ? "primary" : value.trim();
    }

    private String normalizeAccountGroup(String value, String fallback) {
        if (!isBlank(value)) {
            return value.trim();
        }
        if (!isBlank(fallback)) {
            return fallback.trim();
        }
        return "primary";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
