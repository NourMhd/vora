package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.document.SiteHistoryDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.enums.HistoryGranularity;
import com.fusionsolar.monitoring.persistence.repositories.SiteHistoryRepository;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Normalise les KPI énergie affichés par le dashboard.
 *
 * Règle importante:
 * - pour Huawei et autres providers, les historiques Mongo peuvent corriger month/year;
 * - pour Solis, on ne remplace pas les KPI live par l'historique cache,
 *   car certains historiques Solis peuvent être incohérents avec la plateforme Solis.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiteEnergyKpiReconciliationService {

    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");
    private static final double EPSILON = 0.000001;

    private final SiteRepository siteRepository;
    private final SiteHistoryRepository siteHistoryRepository;

    public List<SiteDocument> reconcileAllFromCachedHistories(List<SiteDocument> sites) {
        if (sites == null || sites.isEmpty()) {
            return List.of();
        }

        List<SiteDocument> reconciled = new ArrayList<>(sites.size());

        for (SiteDocument site : sites) {
            reconciled.add(reconcileCurrentKpisFromCache(site));
        }

        return reconciled;
    }

    public SiteDocument reconcileCurrentKpisFromCache(SiteDocument site) {
        if (site == null) {
            return null;
        }

        /*
         * Important:
         * Pour SOLIS, les KPI affichés dans la page Sites doivent rester cohérents
         * avec les valeurs live venant de Solis. On ne les écrase donc pas avec
         * les historiques Mongo, qui peuvent parfois contenir des valeurs trop grandes.
         */
        if (site.getBrand() == Brand.SOLIS) {
            return site;
        }

        String today = LocalDate.now(TUNIS_ZONE).toString();
        String currentMonth = YearMonth.now(TUNIS_ZONE).toString();
        String currentYear = String.valueOf(LocalDate.now(TUNIS_ZONE).getYear());

        boolean changed = false;

        Optional<Double> dayFromHistory = sumCachedHistory(site, HistoryGranularity.DAY, today);
        if (dayFromHistory.isPresent()) {
            changed |= setDayEnergy(site, dayFromHistory.get());
        }

        Optional<Double> monthFromHistory = sumCachedHistory(site, HistoryGranularity.MONTH, currentMonth);
        if (monthFromHistory.isPresent()) {
            changed |= setMonthEnergy(site, monthFromHistory.get());
        }

        Optional<Double> yearFromHistory = sumCachedHistory(site, HistoryGranularity.YEAR, currentYear);
        if (yearFromHistory.isPresent()) {
            changed |= setYearEnergy(site, yearFromHistory.get());
        }

        changed |= enforceCurrentYearLowerBound(site);

        if (!changed) {
            return site;
        }

        site.setUpdatedAt(Instant.now());
        return siteRepository.save(site);
    }

    public SiteDocument reconcileAfterHistoryLoad(
            SiteDocument site,
            String type,
            String value,
            List<HistoryPointDto> points
    ) {
        if (site == null || type == null || value == null) {
            return site;
        }

        /*
         * Même règle que reconcileCurrentKpisFromCache:
         * on ne laisse pas l'historique Solis écraser les KPI live du site.
         */
        if (site.getBrand() == Brand.SOLIS) {
            return site;
        }

        String normalizedType = type.trim().toLowerCase();
        String today = LocalDate.now(TUNIS_ZONE).toString();
        String currentMonth = YearMonth.now(TUNIS_ZONE).toString();
        String currentYear = String.valueOf(LocalDate.now(TUNIS_ZONE).getYear());

        double sum = sumPoints(points);
        boolean changed = false;

        switch (normalizedType) {
            case "day" -> {
                if (today.equals(value)) {
                    changed |= setDayEnergy(site, sum);
                    changed |= enforceCurrentYearLowerBound(site);
                }
            }
            case "month" -> {
                if (currentMonth.equals(value)) {
                    changed |= setMonthEnergy(site, sum);
                    changed |= enforceCurrentYearLowerBound(site);
                }
            }
            case "year" -> {
                if (currentYear.equals(value)) {
                    changed |= setYearEnergy(site, sum);
                    changed |= enforceCurrentYearLowerBound(site);
                }
            }
            default -> {
                return site;
            }
        }

        if (!changed) {
            return site;
        }

        site.setUpdatedAt(Instant.now());
        return siteRepository.save(site);
    }

    private Optional<Double> sumCachedHistory(
            SiteDocument site,
            HistoryGranularity granularity,
            String bucket
    ) {
        Optional<SiteHistoryDocument> grouped = siteHistoryRepository
                .findByBrandAndProviderAccountGroupAndSiteCodeAndGranularityAndBucket(
                        site.getBrand(),
                        accountGroupOf(site),
                        site.getSiteCode(),
                        granularity,
                        bucket
                );

        Optional<SiteHistoryDocument> history = grouped.isPresent()
                ? grouped
                : siteHistoryRepository.findByBrandAndSiteCodeAndGranularityAndBucket(
                        site.getBrand(),
                        site.getSiteCode(),
                        granularity,
                        bucket
                );

        return history
                .filter(document -> document.getPoints() != null && !document.getPoints().isEmpty())
                .map(SiteHistoryDocument::getPoints)
                .map(this::sumPoints);
    }

    private String accountGroupOf(SiteDocument site) {
        if (site != null && site.getProviderAccountGroup() != null && !site.getProviderAccountGroup().isBlank()) {
            return site.getProviderAccountGroup().trim();
        }
        if (site != null && site.getProviderAccountKey() != null && !site.getProviderAccountKey().isBlank()) {
            return site.getProviderAccountKey().trim();
        }
        return "primary";
    }

    private double sumPoints(List<HistoryPointDto> points) {
        if (points == null || points.isEmpty()) {
            return 0.0;
        }

        return round2(
                points.stream()
                        .filter(point -> point != null && point.getValue() != null)
                        .mapToDouble(HistoryPointDto::getValue)
                        .filter(value -> value >= 0)
                        .sum()
        );
    }

    private boolean setDayEnergy(SiteDocument site, double value) {
        return setIfDifferent(site.getDayEnergyKwh(), round2(value), site::setDayEnergyKwh);
    }

    private boolean setMonthEnergy(SiteDocument site, double value) {
        return setIfDifferent(site.getMonthEnergyKwh(), round2(value), site::setMonthEnergyKwh);
    }

    private boolean setYearEnergy(SiteDocument site, double value) {
        return setIfDifferent(site.getYearEnergyKwh(), round2(value), site::setYearEnergyKwh);
    }

    private boolean enforceCurrentYearLowerBound(SiteDocument site) {
        double month = nullToZero(site.getMonthEnergyKwh());
        double year = nullToZero(site.getYearEnergyKwh());

        if (month <= 0 || year + EPSILON >= month) {
            return false;
        }

        log.debug(
                "Reconciling current-year lower bound for site {} {}: year={} < month={}",
                site.getBrand(),
                site.getSiteCode(),
                year,
                month
        );

        site.setYearEnergyKwh(round2(month));
        return true;
    }

    private boolean setIfDifferent(
            Double currentValue,
            double newValue,
            java.util.function.Consumer<Double> setter
    ) {
        double current = nullToZero(currentValue);

        if (Math.abs(current - newValue) <= EPSILON) {
            return false;
        }

        setter.accept(newValue);
        return true;
    }

    private double nullToZero(Double value) {
        return value != null ? value : 0.0;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}