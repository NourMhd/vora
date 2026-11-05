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
import com.fusionsolar.monitoring.service.huawei.HuaweiAccountDirectory;
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
    private final HuaweiAccountDirectory accountDirectory;

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
        Set<String> stateUpdatedIdentities = new HashSet<>();

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

                if (accountSites.complete()) completedGroups.add(accountGroup);
                else failedGroups++;

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

                        document.setProviderAccountKey(huaweiLiveStationService.inventoryAccountForSite(accountGroup, siteCode, accountKey));
                        document.setProviderAccountGroup(accountGroup);
                        document.setActive(true);
                        document.setMissingSince(null);
                        document.setMissingConfirmations(0);
                        document.setDeletedAt(null);
                        document.setUpdatedAt(now);

                        persistInventory(document, existing);
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

                // Live states are collected independently by HuaweiStateSyncService.

            }

            deactivatedCount = failedSaveCount == 0 && invalidCount == 0 ? deactivateMissingSites(
                    Brand.HUAWEI,
                    receivedSiteIdentities,
                    completedGroups,
                    now
            ) : 0;

            Set<String> missingGroups = new HashSet<>(accountDirectory.getEnabledAccountsByGroup().keySet());
            missingGroups.removeAll(completedGroups);
            failedGroups = Math.max(failedGroups, missingGroups.size());
            Map<String, Object> stats = new HashMap<>();
            stats.put("inventoryOnly", true);
            stats.put("missingOrIncompleteGroups", missingGroups);
            stats.put("inventoryOnlyStatesNotRefreshed", Math.max(0, savedCount - liveKpiUpdatedCount));
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
            run.setMessage("Huawei inventory sync completed; see STATES for live coverage");
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
            stats.put("inventoryOnly", true);
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

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.data.mongodb.core.MongoTemplate mongo;

    @org.springframework.beans.factory.annotation.Autowired
    private com.fusionsolar.monitoring.planning.SiteTrackingService siteTracking;

    protected void persistInventory(SiteDocument document, SiteDocument existing) {
        if (existing == null) { siteRepository.insert(document); return; }
        var update = inventoryUpdate(document);
        mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").is(existing.getId())), update, SiteDocument.class);
        if (siteTracking != null) siteTracking.captureById(existing.getId(), "HUAWEI_INVENTORY");
    }

    static org.springframework.data.mongodb.core.query.Update inventoryUpdate(SiteDocument d) {
        return new org.springframework.data.mongodb.core.query.Update()
                .set("providerAccountKey", d.getProviderAccountKey()).set("providerAccountGroup", d.getProviderAccountGroup())
                .set("siteCode", d.getSiteCode()).set("providerSiteId", d.getProviderSiteId())
                .set("siteName", d.getSiteName()).set("installedPowerKw", d.getInstalledPowerKw())
                .set("gridConnectionDate", d.getGridConnectionDate()).set("active", true)
                .set("missingSince", null).set("missingConfirmations", 0).set("deletedAt", null)
                .set("lastSyncAt", d.getLastSyncAt()).set("updatedAt", d.getUpdatedAt());
    }

    protected void persistMissing(SiteDocument site) {
        mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").is(site.getId())),
                new org.springframework.data.mongodb.core.query.Update().set("active", site.getActive())
                        .set("missingSince", site.getMissingSince()).set("missingConfirmations", site.getMissingConfirmations())
                        .set("deletedAt", site.getDeletedAt()).set("updatedAt", site.getUpdatedAt()), SiteDocument.class);
        if (siteTracking != null) siteTracking.captureById(site.getId(), "HUAWEI_MEMBERSHIP");
    }

    private SiteDocument findExistingSite(String accountGroup, String siteCode) {
        SiteDocument existing = siteRepository
                .findByBrandAndProviderAccountGroupAndSiteCode(Brand.HUAWEI, accountGroup, siteCode)
                .orElse(null);

        if (existing != null) {
            return existing;
        }

        // Compatibilité anciennes données avant l'ajout de providerAccountGroup.
        SiteDocument legacy = siteRepository.findByBrandAndSiteCode(Brand.HUAWEI, siteCode).orElse(null);
        return legacy != null && isBlank(legacy.getProviderAccountGroup()) ? legacy : null;
    }

    private int deactivateMissingSites(
            Brand brand,
            Set<String> receivedSiteIdentities,
            Set<String> completedGroups,
            Instant now
    ) {
        if (receivedSiteIdentities == null || completedGroups == null || completedGroups.isEmpty()) {
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

            boolean confirmed = SiteMissingPolicy.confirmMissing(site, now);
            persistMissing(site);
            if (confirmed) deactivated++;

            if (confirmed) log.warn(
                    "Huawei site group={} code={} marked inactive after two complete inventories without it.",
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
