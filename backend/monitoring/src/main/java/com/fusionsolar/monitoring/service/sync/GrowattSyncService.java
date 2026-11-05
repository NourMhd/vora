package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.dto.SiteSummaryDto;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.document.SyncRunDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.enums.SyncStatus;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import com.fusionsolar.monitoring.persistence.repositories.SyncRunRepository;
import com.fusionsolar.monitoring.provider.growatt.GrowattMonitoringProvider;
import com.fusionsolar.monitoring.provider.mapper.SiteMongoMapper;
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
@ConditionalOnProperty(prefix = "providers.growatt", name = "enabled", havingValue = "true")
public class GrowattSyncService implements MonitoringSitesSyncService {

    private final GrowattMonitoringProvider growattMonitoringProvider;
    private final SiteRepository siteRepository;
    private final SyncRunRepository syncRunRepository;
    private final SiteMongoMapper siteMongoMapper;

    private final AtomicBoolean summarySyncRunning = new AtomicBoolean(false);

    @Value("${app.growatt-sync.min-summary-refresh-interval-ms:600000}")
    private long minSummaryRefreshIntervalMs;

    private volatile Instant lastSummarySyncStartedAt = Instant.EPOCH;

    @Override
    public Brand getBrand() {
        return Brand.GROWATT;
    }

    @Override
    public void syncSitesSummary() {
        Instant requestedAt = Instant.now();
        if (!summarySyncRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            long elapsedMs = requestedAt.toEpochMilli() - lastSummarySyncStartedAt.toEpochMilli();
            if (elapsedMs >= 0 && elapsedMs < minSummaryRefreshIntervalMs) {
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
                .brand(Brand.GROWATT)
                .type("SITES_SUMMARY")
                .status(SyncStatus.RUNNING)
                .message("Growatt sync started")
                .startedAt(startedAt)
                .build();
        run = syncRunRepository.save(run);

        int savedCount = 0;
        int failedSaveCount = 0;
        int invalidCount = 0;
        int statesNotRefreshed = 0;
        int deactivatedCount = 0;
        Set<String> receivedSiteCodes = new HashSet<>();

        try {
            log.info("Growatt sites sync started");
            GrowattMonitoringProvider.InventorySnapshot inventory = growattMonitoringProvider.getSitesInventory();
            List<SiteSummaryDto> summaries = inventory.sites();
            Set<String> unsafeAccounts = new HashSet<>();
            Instant now = Instant.now();
            for (SiteSummaryDto summary : summaries) {
                if (summary == null || summary.getSiteCode() == null || summary.getSiteCode().isBlank()) {
                    invalidCount++;
                    continue;
                }
                if (summary.getStatus() == null || "unknown".equalsIgnoreCase(summary.getStatus()) || summary.getStatus().isBlank()) statesNotRefreshed++;
                String siteCode = summary.getSiteCode().trim();
                summary.setSiteCode(siteCode);
                receivedSiteCodes.add(siteCode);
                try {
                    SiteDocument existing = siteRepository.findByBrandAndSiteCode(Brand.GROWATT, siteCode).orElse(null);
                    SiteDocument document = siteMongoMapper.toSiteDocument(Brand.GROWATT, summary, existing, now);
                    document.setProviderAccountKey(growattMonitoringProvider.accountKeyFromSiteCode(siteCode));
                    document.setProviderAccountGroup(growattMonitoringProvider.accountGroupFromSiteCode(siteCode));
                    document.setProviderSiteId(siteCode.substring(siteCode.lastIndexOf('_') + 1));
                    document.setActive(true);
                    document.setMissingSince(null);
                    document.setMissingConfirmations(0);
                    document.setDeletedAt(null);
                    document.setUpdatedAt(now);
                    siteRepository.save(document);
                    savedCount++;
                } catch (Exception e) {
                    failedSaveCount++;
                    unsafeAccounts.add(growattMonitoringProvider.inventoryAccountGroup(siteCode));
                    log.warn("Unable to save Growatt site {}: {}", siteCode, e.getMessage());
                }
            }
            Set<String> reconciledAccounts = new HashSet<>(inventory.completedAccountGroups());
            reconciledAccounts.removeAll(unsafeAccounts);
            deactivatedCount = invalidCount == 0
                    ? deactivateMissingSites(receivedSiteCodes, reconciledAccounts, now) : 0;
            Map<String, Object> stats = new HashMap<>();
            stats.put("completedAccounts", inventory.completedAccountGroups());
            stats.put("failedAccounts", inventory.failedAccountGroups());
            stats.put("reconciledAccounts", reconciledAccounts);
            stats.put("receivedFromProvider", summaries.size());
            stats.put("validReceived", receivedSiteCodes.size());
            stats.put("invalid", invalidCount);
            stats.put("statesNotRefreshed", statesNotRefreshed);
            stats.put("saved", savedCount);
            stats.put("failedToSave", failedSaveCount);
            stats.put("deactivated", deactivatedCount);
            log.info("Growatt sites sync completed received={} saved={} invalid={} failed={} deactivated={}", summaries.size(), savedCount, invalidCount, failedSaveCount, deactivatedCount);
            run.setStatus(inventory.completedAccountGroups().isEmpty() ? SyncStatus.FAILED
                    : failedSaveCount == 0 && invalidCount == 0 && statesNotRefreshed == 0
                    && inventory.failedAccountGroups().isEmpty() ? SyncStatus.SUCCESS : SyncStatus.PARTIAL_SUCCESS);
            run.setMessage("Growatt sync completed");
            run.setStats(stats);
            run.setFinishedAt(Instant.now());
            syncRunRepository.save(run);
        } catch (Exception e) {
            run.setStatus(savedCount > 0 ? SyncStatus.PARTIAL_SUCCESS : SyncStatus.FAILED);
            run.setMessage(e.getMessage());
            run.setFinishedAt(Instant.now());
            syncRunRepository.save(run);
            throw e;
        }
    }

    private int deactivateMissingSites(Set<String> receivedSiteCodes, Set<String> reconciledAccounts, Instant now) {
        if (receivedSiteCodes == null) {
            log.warn("Growatt missing-site deactivation skipped because provider returned no valid site codes.");
            return 0;
        }
        int deactivated = 0;
        for (SiteDocument site : siteRepository.findAllByBrandOrderBySiteNameAsc(Brand.GROWATT)) {
            if (site == null || site.getSiteCode() == null || receivedSiteCodes.contains(site.getSiteCode())) {
                continue;
            }
            // Never infer deletion from an inaccessible, disabled or unconfigured account.
            try {
                if (!reconciledAccounts.contains(growattMonitoringProvider.inventoryAccountGroup(site.getSiteCode()))) continue;
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (Boolean.FALSE.equals(site.getActive())) {
                continue;
            }
            boolean confirmed = SiteMissingPolicy.confirmMissing(site, now);
            siteRepository.save(site);
            if (confirmed) deactivated++;
        }
        return deactivated;
    }

    @Override
    public void bootstrapIfEmpty() {
        boolean hasVisibleGrowattSites = siteRepository.findAllByBrandOrderBySiteNameAsc(Brand.GROWATT)
                .stream()
                .anyMatch(SiteSyncPolicy::isVisibleSite);
        if (!hasVisibleGrowattSites) {
            syncSitesSummary();
        }
    }
}
