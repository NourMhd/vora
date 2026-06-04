package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class HuaweiYearEnergySyncService {

    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");

    private final SiteRepository siteRepository;
    private final HuaweiHistorySyncService huaweiHistorySyncService;

    @Value("${app.huawei-sync.year-energy-batch-size:2}")
    private int yearEnergyBatchSize;

    @Value("${app.huawei-sync.year-energy-max-age-ms:21600000}")
    private long yearEnergyMaxAgeMs;

    @Value("${app.huawei-sync.year-energy-delay-between-sites-ms:15000}")
    private long yearEnergyDelayBetweenSitesMs;

    @Value("${app.huawei-sync.year-energy-enabled:false}")
private boolean yearEnergyEnabled;

    public synchronized void syncDueCurrentYearEnergyBatch() {
        Instant now = Instant.now();
        String currentYear = String.valueOf(LocalDate.now(TUNIS_ZONE).getYear());

        List<SiteDocument> dueSites = findDueHuaweiSites(now);

        if (dueSites.isEmpty()) {
            log.debug("No Huawei sites due for current year energy sync.");
            return;
        }

        int updated = 0;
        int skipped = 0;
        int failed = 0;

        log.info(
                "Starting Huawei current year energy batch. year={}, batchSize={}, dueSites={}",
                currentYear,
                yearEnergyBatchSize,
                dueSites.size()
        );

        for (int i = 0; i < dueSites.size(); i++) {
            SiteDocument site = dueSites.get(i);

            try {
                boolean changed = syncCurrentYearEnergy(site, currentYear, Instant.now());

                if (changed) {
                    updated++;
                } else {
                    skipped++;
                }

            } catch (Exception e) {
                failed++;

                log.warn(
                        "Unable to sync Huawei year energy for site {}. Keeping previous value={}. Error={}",
                        site.getSiteCode(),
                        site.getYearEnergyKwh(),
                        e.getMessage()
                );
            }

            if (i < dueSites.size() - 1) {
                sleepBetweenSitesIfNeeded();

                if (Thread.currentThread().isInterrupted()) {
                    log.warn("Huawei year energy batch interrupted.");
                    break;
                }
            }
        }

        log.info(
                "Huawei current year energy batch finished. updated={}, skipped={}, failed={}",
                updated,
                skipped,
                failed
        );
    }

    /*
     * Usage manuel/admin uniquement.
     * Même cette méthode respecte le délai entre les sites pour éviter Huawei 407.
     */
    public synchronized void syncCurrentYearEnergyForAllSites() {
       List<SiteDocument> sites = siteRepository.findAllByBrandOrderBySiteNameAsc(Brand.HUAWEI)
        .stream()
        .filter(SiteSyncPolicy::isVisibleSite)
        .toList();

        if (sites.isEmpty()) {
            log.warn("No Huawei sites found for year energy sync.");
            return;
        }

        String currentYear = String.valueOf(LocalDate.now(TUNIS_ZONE).getYear());

        int updated = 0;
        int skipped = 0;
        int failed = 0;

        log.warn(
                "Manual Huawei full year energy sync started. sites={}. This should not be scheduled frequently.",
                sites.size()
        );

        for (int i = 0; i < sites.size(); i++) {
            SiteDocument site = sites.get(i);

            try {
                boolean changed = syncCurrentYearEnergy(site, currentYear, Instant.now());

                if (changed) {
                    updated++;
                } else {
                    skipped++;
                }

            } catch (Exception e) {
                failed++;

                log.warn(
                        "Unable to sync Huawei year energy for site {}. Keeping previous value={}. Error={}",
                        site.getSiteCode(),
                        site.getYearEnergyKwh(),
                        e.getMessage()
                );
            }

            if (i < sites.size() - 1) {
                sleepBetweenSitesIfNeeded();

                if (Thread.currentThread().isInterrupted()) {
                    log.warn("Manual Huawei year energy sync interrupted.");
                    break;
                }
            }
        }

        log.info(
                "Manual Huawei full year energy sync finished. sites={}, updated={}, skipped={}, failed={}",
                sites.size(),
                updated,
                skipped,
                failed
        );
    }

    private List<SiteDocument> findDueHuaweiSites(Instant now) {
        long safeMaxAgeMs = Math.max(yearEnergyMaxAgeMs, 60_000L);
        int safeBatchSize = Math.max(yearEnergyBatchSize, 1);

        Instant staleBefore = now.minusMillis(safeMaxAgeMs);

        Comparator<SiteDocument> byLastYearSync = Comparator.comparing(
                SiteDocument::getYearEnergyLastSyncAt,
                Comparator.nullsFirst(Comparator.naturalOrder())
        );

        return siteRepository.findAllByBrandOrderBySiteNameAsc(Brand.HUAWEI)
                .stream()
                .filter(site -> site.getYearEnergyLastSyncAt() == null
                        || !site.getYearEnergyLastSyncAt().isAfter(staleBefore))
                .sorted(
                        byLastYearSync
                                .thenComparing(site -> safeString(site.getSiteName()))
                                .thenComparing(site -> safeString(site.getSiteCode()))
                )
                .limit(safeBatchSize)
                .toList();
    }

    private boolean syncCurrentYearEnergy(
            SiteDocument site,
            String currentYear,
            Instant now
    ) {
        List<HistoryPointDto> points =
                huaweiHistorySyncService.getHistory(site, "year", currentYear, true);

        double historyYearEnergy = sumPoints(points);
        double monthEnergy = nullSafeDouble(site.getMonthEnergyKwh());
        double previousYearEnergy = nullSafeDouble(site.getYearEnergyKwh());

        if ((points == null || points.isEmpty()) && monthEnergy > 0) {
            /*
             * On ne met surtout pas yearEnergyKwh à 0 si le mois courant produit déjà.
             * Mais on marque une tentative pour ne pas marteler Huawei en boucle.
             */
            site.setYearEnergyLastSyncAt(now);
            site.setUpdatedAt(now);
            siteRepository.save(site);

            log.warn(
                    "Huawei year history is empty for site {} while monthEnergy={} kWh. Previous yearEnergy={} kWh kept.",
                    site.getSiteCode(),
                    monthEnergy,
                    previousYearEnergy
            );

            return false;
        }

        double nextYearEnergy = Math.max(historyYearEnergy, monthEnergy);
        nextYearEnergy = round2(nextYearEnergy);

        site.setYearEnergyKwh(nextYearEnergy);
        site.setYearEnergyLastSyncAt(now);
        site.setUpdatedAt(now);

        siteRepository.save(site);

        log.info(
                "Huawei year energy synced for site {}. historyYear={} kWh, month={} kWh, saved={} kWh",
                site.getSiteCode(),
                round2(historyYearEnergy),
                round2(monthEnergy),
                nextYearEnergy
        );

        return true;
    }

    private double sumPoints(List<HistoryPointDto> points) {
        if (points == null || points.isEmpty()) {
            return 0.0;
        }

        return points.stream()
                .filter(point -> point != null && point.getValue() != null)
                .mapToDouble(HistoryPointDto::getValue)
                .sum();
    }

    private void sleepBetweenSitesIfNeeded() {
        if (yearEnergyDelayBetweenSitesMs <= 0) {
            return;
        }

        try {
            Thread.sleep(yearEnergyDelayBetweenSitesMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private double nullSafeDouble(Double value) {
        return value != null ? value : 0.0;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String safeString(String value) {
        return value != null ? value : "";
    }
}