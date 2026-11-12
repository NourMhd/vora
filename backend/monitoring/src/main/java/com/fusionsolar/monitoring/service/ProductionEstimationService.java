package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class ProductionEstimationService {

    public static final double EPSILON = 0.000001;
    static final int FULL_SITE_CONFIDENCE_DAYS = 30;
    static final int MIN_RELIABLE_PEER_DAYS = 20;
    static final double MAX_PEER_TO_THEORETICAL_RATIO = 1.20;
    private static final List<String> GOVERNORATE_CODES = List.of(
            "ARI", "BAR", "MAN", "JEN", "ZAG", "BIZ", "TUN", "NAB", "BEJ", "KEF", "SIL",
            "KAI", "SOU", "MAH", "KAS", "MON", "SBO", "SFA",
            "MED", "TAT", "GAB", "TOZ", "GAF", "KEB"
    );

    private static final Map<String, List<String>> ZONE_GOVERNORATES = Map.of(
            // Nord: valeurs théoriques colonne NORD du fichier fourni.
            "Zone A", List.of("ARI", "BAR", "MAN", "JEN", "ZAG", "BIZ", "TUN", "NAB", "BEJ", "KEF", "SIL"),
            // Centre: valeurs théoriques colonne Centre.
            "Zone B", List.of("KAI", "SOU", "MAH", "KAS", "MON", "SBO", "SFA"),
            // Sud: valeurs théoriques colonne SUD.
            "Zone C", List.of("MED", "TAT", "GAB", "TOZ", "GAF", "KEB")
    );

    /*
     * Rendement théorique kWh/kWc/jour, par zone, mois et tranche:
     * tranche 1 = jours 1-10, tranche 2 = jours 11-20, tranche 3 = jours 21-fin du mois.
     * Source: fichier "Tableau Rendement Théorique Par Période.xlsx".
     */
    private static final Map<String, double[][]> THEORETICAL_DAILY_YIELD = Map.of(
            "Zone A", new double[][]{
                    {2.2, 2.4, 2.5},
                    {2.8, 3.3, 3.5},
                    {3.8, 4.2, 4.5},
                    {4.8, 5.2, 5.6},
                    {5.9, 6.1, 6.2},
                    {6.3, 6.4, 6.5},
                    {6.6, 6.7, 6.5},
                    {6.3, 6.0, 5.8},
                    {5.5, 5.0, 4.8},
                    {4.4, 4.0, 3.8},
                    {3.0, 2.8, 2.5},
                    {2.4, 2.3, 2.0}
            },
            "Zone B", new double[][]{
                    {2.266, 2.472, 2.575},
                    {2.884, 3.399, 3.605},
                    {3.914, 4.326, 4.635},
                    {4.944, 5.356, 5.768},
                    {6.077, 6.283, 6.386},
                    {6.489, 6.592, 6.695},
                    {6.798, 6.901, 6.695},
                    {6.489, 6.180, 5.974},
                    {5.665, 5.150, 4.944},
                    {4.532, 4.120, 3.914},
                    {3.090, 2.884, 2.575},
                    {2.480, 2.380, 2.060}
            },
            "Zone C", new double[][]{
                    {2.33398, 2.54616, 2.65225},
                    {2.97052, 3.50097, 3.71315},
                    {4.03142, 4.45578, 4.77405},
                    {5.09232, 5.51668, 5.94104},
                    {6.25931, 6.47149, 6.57758},
                    {6.68367, 6.78976, 6.89585},
                    {7.00194, 7.10803, 6.89585},
                    {6.68367, 6.36540, 6.15322},
                    {5.83495, 5.30450, 5.09232},
                    {4.66796, 4.24360, 4.03142},
                    {3.18270, 2.97052, 2.62650},
                    {2.52960, 2.42760, 2.10120}
            }
    );

    /**
     * Variante sans liste de sites comparables, conservée pour les consommateurs
     * historiques. Avec 30 jours réels dans le mois, la moyenne du site suffit.
     * En dessous de 30 jours, le théorique journalier est le repli sûr.
     */
    public DailyProductionDecision decideDailyProduction(
            DailySiteProduction target,
            LocalDate day,
            Map<YearMonth, Double> monthlyRealAverages
    ) {
        if (target == null || target.document() == null) {
            return DailyProductionDecision.unavailable("SITE_NOT_FOUND");
        }

        if (!isProductionDay(target, day)) {
            return DailyProductionDecision.unavailable("NOT_SWAPPED_YET");
        }

        Double rawValue = valueForDay(target, day);
        if (rawValue != null && rawValue > EPSILON) {
            return DailyProductionDecision.monitored(round2(rawValue));
        }

        Double theoretical = theoreticalDailyKwh(target, day);
        if (theoretical == null) {
            return DailyProductionDecision.unavailable("NO_ESTIMATION_BASIS");
        }

        Double monthAverage = monthlyRealAverages == null ? null : monthlyRealAverages.get(YearMonth.from(day));
        int monitoredDays = monitoredStats(target, YearMonth.from(day).atDay(1),
                YearMonth.from(day).atEndOfMonth()).days();
        if (monthAverage != null && monthAverage > EPSILON && monitoredDays >= FULL_SITE_CONFIDENCE_DAYS) {
            return DailyProductionDecision.estimated(
                    round2(monthAverage),
                    missingStatus(target, day, "SITE_HISTORY_ESTIMATED")
            );
        }

        return DailyProductionDecision.estimated(
                round2(theoretical),
                missingStatus(target, day, "THEORETICAL_FALLBACK_ESTIMATED")
        );
    }

    /**
     * Variante utilisée par le tableau journalier.
     * - 30 jours réels ou plus: moyenne réelle du site;
     * - 1 à 29 jours: moyenne site/référence régionale pondérée par jours/30;
     * - 0 jour: référence régionale;
     * - aucune référence fiable: théorique journalier.
     */
    public DailyProductionDecision decideDailyProduction(
            DailySiteProduction target,
            List<DailySiteProduction> allSites,
            LocalDate day
    ) {
        if (target == null || target.document() == null) {
            return DailyProductionDecision.unavailable("SITE_NOT_FOUND");
        }
        if (day == null || !isProductionDay(target, day)) {
            return DailyProductionDecision.unavailable("NOT_SWAPPED_YET");
        }
        Double rawValue = valueForDay(target, day);
        if (rawValue != null && rawValue > EPSILON) {
            return DailyProductionDecision.monitored(round2(rawValue));
        }

        DailyEstimate estimate = estimateMissingDay(target, allSites, day);
        return estimate.value() == null
                ? DailyProductionDecision.unavailable(missingStatus(target, day, "NO_ESTIMATION_BASIS"))
                : DailyProductionDecision.estimated(round2(estimate.value()), missingStatus(target, day, estimate.method()));
    }

    /**
     * Rule of three over the selected period, starting at commissioning.
     * A valid zero is monitored. Without monitored days no estimate is available.
     * Daily comparison retains its independent estimation policy.
     */
    public DisplayedProductionComputation computeDisplayedProduction(
            DailySiteProduction target, LocalDate startDate, LocalDate endDate
    ) {
        if (target == null || target.document() == null || target.swapDate() == null
                || startDate == null || endDate == null || endDate.isBefore(startDate)) {
            return unavailableDisplayedProduction();
        }
        double realKwh = 0.0;
        int monitoredDays = 0;
        int missingDays = 0;
        for (LocalDate day = startDate; !day.isAfter(endDate); day = day.plusDays(1)) {
            if (!isProductionDay(target, day)) continue;
            Double value = valueForDay(target, day);
            if (value != null) {
                realKwh += value;
                monitoredDays++;
            } else {
                missingDays++;
            }
        }
        if (monitoredDays == 0) {
            return new DisplayedProductionComputation(0.0, 0.0, null, 0, missingDays);
        }
        double estimatedKwh = realKwh / monitoredDays * missingDays;
        return new DisplayedProductionComputation(round2(realKwh), round2(estimatedKwh),
                round2(realKwh + estimatedKwh), monitoredDays, missingDays);
    }

    public DisplayedProductionComputation computeDisplayedProduction(
            DailySiteProduction target, List<DailySiteProduction> allSites,
            LocalDate startDate, LocalDate endDate
    ) {
        return computeDisplayedProduction(target, startDate, endDate);
    }

    private DailyEstimate estimateMissingDay(
            DailySiteProduction target,
            List<DailySiteProduction> allSites,
            LocalDate day
    ) {
        if (target == null || day == null || target.installedPowerKw() <= EPSILON) {
            return new DailyEstimate(null, "NO_ESTIMATION_BASIS");
        }

        YearMonth month = YearMonth.from(day);
        return estimateMissingDay(target, day, estimationBasis(target, allSites, month));
    }

    private EstimationBasis estimationBasis(
            DailySiteProduction target,
            List<DailySiteProduction> allSites,
            YearMonth month
    ) {
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();
        MonitoredStats targetStats = monitoredStats(target, monthStart, monthEnd);
        Double peerDailyKwh = targetStats.days() >= FULL_SITE_CONFIDENCE_DAYS
                ? null
                : bestPeerDailyKwh(target, allSites, monthStart, monthEnd);
        return new EstimationBasis(targetStats, peerDailyKwh);
    }

    private DailyEstimate estimateMissingDay(
            DailySiteProduction target,
            LocalDate day,
            EstimationBasis basis
    ) {
        MonitoredStats targetStats = basis.targetStats();

        if (targetStats.days() >= FULL_SITE_CONFIDENCE_DAYS) {
            return new DailyEstimate(targetStats.average(), "SITE_HISTORY_ESTIMATED");
        }

        Double peerDailyKwh = basis.peerDailyKwh();
        if (peerDailyKwh != null) {
            if (targetStats.days() <= 0) {
                return new DailyEstimate(peerDailyKwh, "REGIONAL_REFERENCE_ESTIMATED");
            }

            double confidence = Math.min(1.0, targetStats.days() / (double) FULL_SITE_CONFIDENCE_DAYS);
            double weighted = confidence * targetStats.average() + (1.0 - confidence) * peerDailyKwh;
            return new DailyEstimate(weighted, "REGIONAL_WEIGHTED_ESTIMATED");
        }

        return new DailyEstimate(theoreticalDailyKwh(target, day), "THEORETICAL_FALLBACK_ESTIMATED");
    }

    private Double bestPeerDailyKwh(
            DailySiteProduction target,
            List<DailySiteProduction> allSites,
            LocalDate startDate,
            LocalDate endDate
    ) {
        if (target == null || target.installedPowerKw() <= EPSILON || target.governorate() == null
                || allSites == null || startDate == null || endDate == null || endDate.isBefore(startDate)) {
            return null;
        }

        double bestSpecificDailyYield = -1.0;
        for (DailySiteProduction candidate : allSites) {
            if (candidate == null || candidate.document() == null || candidate.installedPowerKw() <= EPSILON
                    || candidate.swapDate() == null || sameSite(target, candidate)
                    || !Objects.equals(target.governorate(), candidate.governorate())) {
                continue;
            }

            double monitoredKwh = 0.0;
            int monitoredDays = 0;
            LocalDate cursor = startDate;
            while (!cursor.isAfter(endDate)) {
                if (isProductionDay(candidate, cursor)) {
                    Double value = valueForDay(candidate, cursor);
                    if (value != null && value > EPSILON) {
                        monitoredKwh += value;
                        monitoredDays++;
                    }
                }
                cursor = cursor.plusDays(1);
            }

            if (monitoredDays < MIN_RELIABLE_PEER_DAYS) {
                continue;
            }

            double specificDailyYield = monitoredKwh / monitoredDays / candidate.installedPowerKw();
            if (specificDailyYield > bestSpecificDailyYield) {
                bestSpecificDailyYield = specificDailyYield;
            }
        }

        if (bestSpecificDailyYield <= EPSILON) {
            return null;
        }

        double peerDailyKwh = bestSpecificDailyYield * target.installedPowerKw();
        Double theoreticalAverage = theoreticalAverageForProductionDays(target, startDate, endDate);
        if (theoreticalAverage != null && theoreticalAverage > EPSILON) {
            peerDailyKwh = Math.min(peerDailyKwh, theoreticalAverage * MAX_PEER_TO_THEORETICAL_RATIO);
        }
        return round2(peerDailyKwh);
    }

    private MonitoredStats monitoredStats(DailySiteProduction site, LocalDate startDate, LocalDate endDate) {
        if (site == null || startDate == null || endDate == null || endDate.isBefore(startDate)) {
            return new MonitoredStats(0.0, 0);
        }

        double kwh = 0.0;
        int days = 0;
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            if (isProductionDay(site, cursor)) {
                Double value = valueForDay(site, cursor);
                if (value != null && value > EPSILON) {
                    kwh += value;
                    days++;
                }
            }
            cursor = cursor.plusDays(1);
        }
        return new MonitoredStats(kwh, days);
    }

    private Double theoreticalAverageForProductionDays(
            DailySiteProduction site,
            LocalDate startDate,
            LocalDate endDate
    ) {
        double total = 0.0;
        int days = 0;
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            if (isProductionDay(site, cursor)) {
                Double value = theoreticalDailyKwh(site, cursor);
                if (value != null) {
                    total += value;
                    days++;
                }
            }
            cursor = cursor.plusDays(1);
        }
        return days == 0 ? null : total / days;
    }

    private boolean sameSite(DailySiteProduction left, DailySiteProduction right) {
        if (left.document() == right.document()) {
            return true;
        }

        return Objects.equals(left.document().getBrand(), right.document().getBrand())
                && Objects.equals(left.siteCode(), right.siteCode())
                && Objects.equals(left.document().getProviderAccountGroup(), right.document().getProviderAccountGroup());
    }

    private DisplayedProductionComputation unavailableDisplayedProduction() {
        return new DisplayedProductionComputation(0.0, 0.0, null, 0, 0);
    }

    public Map<YearMonth, Double> monthlyRealAverages(DailySiteProduction target) {
        if (target == null || target.valuesByDate() == null || target.valuesByDate().isEmpty()) {
            return Map.of();
        }

        Map<YearMonth, double[]> sumsByMonth = new HashMap<>();
        for (Map.Entry<LocalDate, Double> entry : target.valuesByDate().entrySet()) {
            LocalDate day = entry.getKey();
            Double value = normalizeHistoryValue(entry.getValue());

            if (day == null || value == null || value <= EPSILON || !isProductionDay(target, day)) {
                continue;
            }

            double[] stats = sumsByMonth.computeIfAbsent(YearMonth.from(day), ignored -> new double[2]);
            stats[0] += value;
            stats[1] += 1.0;
        }

        Map<YearMonth, Double> averages = new HashMap<>();
        for (Map.Entry<YearMonth, double[]> entry : sumsByMonth.entrySet()) {
            double[] stats = entry.getValue();
            if (stats[1] > 0) {
                averages.put(entry.getKey(), stats[0] / stats[1]);
            }
        }

        return averages;
    }

    public boolean isProductionDay(DailySiteProduction site, LocalDate day) {
        if (site == null || site.document() == null || day == null || site.swapDate() == null) {
            return false;
        }
        return !day.isBefore(site.swapDate());
    }

    public Double valueForDay(DailySiteProduction site, LocalDate day) {
        if (site == null || day == null || site.valuesByDate() == null) {
            return null;
        }
        return normalizeHistoryValue(site.valuesByDate().get(day));
    }

    public boolean isPresent(DailySiteProduction site, LocalDate day) {
        return site != null && site.presentDates() != null && site.presentDates().contains(day);
    }

    public Double theoreticalDailyKwh(DailySiteProduction site, LocalDate day) {
        if (site == null || day == null || isBlank(site.zone()) || site.installedPowerKw() <= EPSILON) {
            return null;
        }
        return round2(theoreticalDailyYieldPerKwc(site.zone(), day) * site.installedPowerKw());
    }

    public Double theoreticalMonthlyAverageKwh(DailySiteProduction site, YearMonth month) {
        if (site == null || month == null || isBlank(site.zone()) || site.installedPowerKw() <= EPSILON) {
            return null;
        }

        double theoreticalMonthlyKwh = theoreticalYieldPerKwc(site.zone(), month.atDay(1), month.atEndOfMonth())
                * site.installedPowerKw();
        return round2(theoreticalMonthlyKwh / month.lengthOfMonth());
    }

    public double theoreticalYieldPerKwc(String zone, LocalDate startDate, LocalDate endDate) {
        if (isBlank(zone) || !THEORETICAL_DAILY_YIELD.containsKey(zone)
                || startDate == null || endDate == null || endDate.isBefore(startDate)) {
            return 0.0;
        }

        double sum = 0.0;
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            sum += theoreticalDailyYieldPerKwc(zone, cursor);
            cursor = cursor.plusDays(1);
        }
        return sum;
    }

    public double theoreticalDailyYieldPerKwc(String zone, LocalDate day) {
        if (isBlank(zone) || !THEORETICAL_DAILY_YIELD.containsKey(zone) || day == null) {
            return 0.0;
        }

        double[][] yields = THEORETICAL_DAILY_YIELD.get(zone);
        int monthIndex = day.getMonthValue() - 1;
        int decadeIndex = decadeForDay(day.getDayOfMonth()) - 1;
        return yields[monthIndex][decadeIndex];
    }

    public String governorateForSite(SiteDocument site) {
        if (site == null) {
            return null;
        }

        String fromName = normalizeGovernorateCode(site.getSiteName());
        if (fromName != null) {
            return fromName;
        }

        String fromCode = normalizeGovernorateCode(site.getSiteCode());
        if (fromCode != null) {
            return fromCode;
        }

        return normalizeGovernorateCode(site.getProviderSiteId());
    }

    public String zoneForGovernorate(String governorate) {
        if (governorate == null) {
            return null;
        }

        for (Map.Entry<String, List<String>> entry : ZONE_GOVERNORATES.entrySet()) {
            if (entry.getValue().contains(governorate)) {
                return entry.getKey();
            }
        }

        return null;
    }

    public List<String> allGovernorates() {
        return GOVERNORATE_CODES;
    }

    public Double normalizeHistoryValue(Double value) {
        if (value == null || value.isNaN() || value.isInfinite() || value < 0) {
            return null;
        }
        return round2(value);
    }

    public double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public Double round2(Double value) {
        return value == null ? null : round2(value.doubleValue());
    }

    private String missingStatus(DailySiteProduction site, LocalDate day, String suffix) {
        if (!isPresent(site, day)) {
            return "ABSENT_" + suffix;
        }

        Double rawValue = valueForDay(site, day);
        if (rawValue == null) {
            return "NULL_" + suffix;
        }

        if (rawValue <= EPSILON) {
            return "ZERO_" + suffix;
        }

        return suffix;
    }

    public String normalizeGovernorateCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String normalized = Normalizer
                .normalize(raw.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT);

        String[] tokens = normalized.split("[^A-Z0-9]+");
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            for (String governorate : GOVERNORATE_CODES) {
                if (token.equals(governorate) || token.startsWith(governorate + "_") || token.startsWith(governorate + "-")) {
                    return governorate;
                }
                if (token.startsWith(governorate) && token.length() > governorate.length()
                        && Character.isDigit(token.charAt(governorate.length()))) {
                    return governorate;
                }
            }
        }

        for (String governorate : GOVERNORATE_CODES) {
            if (normalized.startsWith(governorate + "_") || normalized.startsWith(governorate + "-")) {
                return governorate;
            }
        }

        return null;
    }

    private int decadeForDay(int dayOfMonth) {
        if (dayOfMonth <= 10) {
            return 1;
        }
        if (dayOfMonth <= 20) {
            return 2;
        }
        return 3;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record DisplayedProductionComputation(
            Double realKwh,
            Double estimatedKwh,
            Double displayedKwh,
            int monitoredDays,
            int estimatedDays
    ) {
    }

    private record MonitoredStats(double kwh, int days) {
        double average() {
            return days <= 0 ? 0.0 : kwh / days;
        }
    }

    private record DailyEstimate(Double value, String method) {
    }

    private record EstimationBasis(MonitoredStats targetStats, Double peerDailyKwh) {
    }

    public record DailySiteProduction(
            SiteDocument document,
            String siteCode,
            String siteName,
            String governorate,
            String zone,
            double installedPowerKw,
            LocalDate swapDate,
            Map<LocalDate, Double> valuesByDate,
            Set<LocalDate> presentDates
    ) {
    }

    public record DailyProductionDecision(
            Double originalValue,
            Double effectiveValue,
            boolean monitored,
            boolean estimated,
            String status
    ) {
        static DailyProductionDecision monitored(Double value) {
            return new DailyProductionDecision(value, value, true, false, "ORIGINAL");
        }

        static DailyProductionDecision estimated(Double value, String status) {
            return new DailyProductionDecision(null, value, false, true, status);
        }

        static DailyProductionDecision unavailable(String status) {
            return new DailyProductionDecision(null, null, false, false, status);
        }
    }
}
