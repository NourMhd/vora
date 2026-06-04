package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.document.SyncRunDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.enums.SyncStatus;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import com.fusionsolar.monitoring.persistence.repositories.SyncRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "providers.solis", name = "enabled", havingValue = "true")
public class SolisHistoryWarmupService {

    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");

    private final SiteRepository siteRepository;
    private final SyncRunRepository syncRunRepository;
    private final SolisHistorySyncService solisHistorySyncService;

    /*
     * Protection globale:
     * current history et yearly history ne doivent pas tourner en même temps.
     * Sinon SolisCloud reçoit des appels proches et peut retourner 429.
     */
    private final AtomicBoolean anyWarmupRunning = new AtomicBoolean(false);

    private final AtomicBoolean currentWarmupRunning = new AtomicBoolean(false);
    private final AtomicBoolean yearlyWarmupRunning = new AtomicBoolean(false);

    private final AtomicInteger currentCursor = new AtomicInteger(0);
    private final AtomicInteger yearlyCursor = new AtomicInteger(0);

    /*
     * Phase current history:
     * 0 => day
     * 1 => month
     * 2 => year
     *
     * Donc on ne fait jamais day + month + year dans le même cycle.
     */
    private final AtomicInteger currentHistoryPhase = new AtomicInteger(0);

    @Value("${app.solis-sync.history-batch-size:1}")
    private int batchSize;

    @Value("${app.solis-sync.history-delay-between-sites-ms:15000}")
    private long delayBetweenSitesMs;

    public void warmupCurrentHistoriesForNextSolisSites() {
        if (!currentWarmupRunning.compareAndSet(false, true)) {
            log.info("Solis current history warmup skipped: current warmup already running.");
            return;
        }

        if (!anyWarmupRunning.compareAndSet(false, true)) {
            currentWarmupRunning.set(false);
            log.info("Solis current history warmup skipped: another Solis history warmup is already running.");
            return;
        }

        SyncRunDocument run = startRun(
                "SOLIS_HISTORY_CURRENT",
                "Solis current history warmup started"
        );

        int success = 0;
        int failed = 0;
        int selected = 0;

        String type = null;
        String value = null;

        try {
            List<SiteDocument> sites = siteRepository.findAllByBrandOrderBySiteNameAsc(Brand.SOLIS)
        .stream()
        .filter(SiteSyncPolicy::isVisibleSite)
        .toList();

            if (sites == null || sites.isEmpty()) {
                finishRun(
                        run,
                        SyncStatus.SUCCESS,
                        "Solis current history warmup finished: no Solis sites",
                        Map.of(
                                "totalSites", 0,
                                "selectedSites", 0,
                                "success", 0,
                                "failed", 0
                        )
                );
                return;
            }

            List<SiteDocument> batch = nextBatch(sites, currentCursor);

            String today = LocalDate.now(TUNIS_ZONE).toString();
            String currentMonth = YearMonth.now(TUNIS_ZONE).toString();
            String currentYear = String.valueOf(LocalDate.now(TUNIS_ZONE).getYear());

            int phase = Math.floorMod(currentHistoryPhase.getAndIncrement(), 3);

            switch (phase) {
                case 0 -> {
                    type = "day";
                    value = today;
                }
                case 1 -> {
                    type = "month";
                    value = currentMonth;
                }
                default -> {
                    type = "year";
                    value = currentYear;
                }
            }

            selected = batch.size();

            log.info(
                    "Solis current history warmup started. type={}, value={}, selectedSites={}, totalSites={}",
                    type,
                    value,
                    selected,
                    sites.size()
            );

            for (SiteDocument site : batch) {
                try {
                    solisHistorySyncService.getHistory(site, type, value);
                    success++;
                } catch (Exception e) {
                    failed++;
                    log.warn(
                            "Solis current history warmup failed for site={}, type={}, value={}: {}",
                            site.getSiteCode(),
                            type,
                            value,
                            e.getMessage()
                    );
                }

                sleep(delayBetweenSitesMs);
            }

            finishRun(
                    run,
                    resolveStatus(success, failed),
                    "Solis current history warmup finished",
                    Map.of(
                            "totalSites", sites.size(),
                            "selectedSites", selected,
                            "historyType", type,
                            "historyValue", value,
                            "success", success,
                            "failed", failed
                    )
            );

        } catch (Exception e) {
            failRun(run, e);
        } finally {
            anyWarmupRunning.set(false);
            currentWarmupRunning.set(false);
        }
    }

    public void warmupYearlyHistoryForNextSolisSites() {
        if (!yearlyWarmupRunning.compareAndSet(false, true)) {
            log.info("Solis yearly history warmup skipped: yearly warmup already running.");
            return;
        }

        if (!anyWarmupRunning.compareAndSet(false, true)) {
            yearlyWarmupRunning.set(false);
            log.info("Solis yearly history warmup skipped: another Solis history warmup is already running.");
            return;
        }

        SyncRunDocument run = startRun(
                "SOLIS_HISTORY_YEARLY",
                "Solis yearly history warmup started"
        );

        int success = 0;
        int failed = 0;
        int selected = 0;

        try {
            List<SiteDocument> sites = siteRepository.findAllByBrandOrderBySiteNameAsc(Brand.SOLIS)
        .stream()
        .filter(SiteSyncPolicy::isVisibleSite)
        .toList();

            if (sites == null || sites.isEmpty()) {
                finishRun(
                        run,
                        SyncStatus.SUCCESS,
                        "Solis yearly history warmup finished: no Solis sites",
                        Map.of(
                                "totalSites", 0,
                                "selectedSites", 0,
                                "success", 0,
                                "failed", 0
                        )
                );
                return;
            }

            List<SiteDocument> batch = nextBatch(sites, yearlyCursor);
            selected = batch.size();

            log.info(
                    "Solis yearly history warmup started. selectedSites={}, totalSites={}",
                    selected,
                    sites.size()
            );

            for (SiteDocument site : batch) {
                try {
                    solisHistorySyncService.getYearlyHistory(site);
                    success++;
                } catch (Exception e) {
                    failed++;
                    log.warn(
                            "Solis yearly history warmup failed for site={}: {}",
                            site.getSiteCode(),
                            e.getMessage()
                    );
                }

                sleep(delayBetweenSitesMs);
            }

            finishRun(
                    run,
                    resolveStatus(success, failed),
                    "Solis yearly history warmup finished",
                    Map.of(
                            "totalSites", sites.size(),
                            "selectedSites", selected,
                            "historyType", "yearly",
                            "historyValue", "all",
                            "success", success,
                            "failed", failed
                    )
            );

        } catch (Exception e) {
            failRun(run, e);
        } finally {
            anyWarmupRunning.set(false);
            yearlyWarmupRunning.set(false);
        }
    }

    private List<SiteDocument> nextBatch(
            List<SiteDocument> sites,
            AtomicInteger cursor
    ) {
        if (sites == null || sites.isEmpty()) {
            return List.of();
        }

        int safeBatchSize = Math.max(1, batchSize);

        int start = Math.floorMod(
                cursor.getAndAdd(safeBatchSize),
                sites.size()
        );

        int end = Math.min(start + safeBatchSize, sites.size());

        return sites.subList(start, end);
    }

    private SyncRunDocument startRun(String type, String message) {
        return syncRunRepository.save(
                SyncRunDocument.builder()
                        .brand(Brand.SOLIS)
                        .type(type)
                        .status(SyncStatus.RUNNING)
                        .message(message)
                        .startedAt(Instant.now())
                        .stats(new HashMap<>())
                        .build()
        );
    }

    private void finishRun(
            SyncRunDocument run,
            SyncStatus status,
            String message,
            Map<String, Object> stats
    ) {
        run.setStatus(status);
        run.setMessage(message);
        run.setStats(stats);
        run.setFinishedAt(Instant.now());

        syncRunRepository.save(run);
    }

    private void failRun(SyncRunDocument run, Exception e) {
        run.setStatus(SyncStatus.FAILED);
        run.setMessage(e.getMessage());
        run.setFinishedAt(Instant.now());

        syncRunRepository.save(run);
    }

    private SyncStatus resolveStatus(int success, int failed) {
        if (failed == 0) {
            return SyncStatus.SUCCESS;
        }

        if (success > 0) {
            return SyncStatus.PARTIAL_SUCCESS;
        }

        return SyncStatus.FAILED;
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }

        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Solis history warmup sleep interrupted");
        }
    }
}