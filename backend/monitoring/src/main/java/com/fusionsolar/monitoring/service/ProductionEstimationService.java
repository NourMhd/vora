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
import java.util.Set;

@Service
public class ProductionEstimationService {

    public static final double EPSILON = 0.000001;
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
     * Règle métier de production affichée:
     * Production affichée = kWh réel monitorisé + kWh estimé.
     *
     * L'estimation des jours sans monitoring se calcule au niveau du mois:
     * Estimation_jour = ((E_reel / M) + (E_theorique / J)) / 2.
     *
     * Cette méthode garde une décision journalière compatible avec les écrans existants;
     * elle utilise donc la moyenne théorique du mois, et non le théorique isolé du jour.
     * Si M = 0, aucune estimation n'est produite pour éviter une production purement théorique.
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

        Double theoreticalMonthlyAverage = theoreticalMonthlyAverageKwh(target, YearMonth.from(day));
        if (theoreticalMonthlyAverage == null) {
            return DailyProductionDecision.unavailable("NO_ESTIMATION_BASIS");
        }

        Double monthAverage = monthlyRealAverages == null ? null : monthlyRealAverages.get(YearMonth.from(day));
        if (monthAverage == null || monthAverage <= EPSILON) {
            return DailyProductionDecision.unavailable(missingStatus(target, day, "NO_REAL_AVERAGE"));
        }

        return DailyProductionDecision.estimated(
                round2((monthAverage + theoreticalMonthlyAverage) / 2.0),
                missingStatus(target, day, "MONTH_REAL_THEORETICAL_AVERAGE_ESTIMATED")
        );
    }

    /**
     * Compatibilité avec les anciens appels: la liste des sites comparables n'est plus utilisée.
     */
    public DailyProductionDecision decideDailyProduction(
            DailySiteProduction target,
            List<DailySiteProduction> allSites,
            LocalDate day
    ) {
        return decideDailyProduction(target, day, monthlyRealAverages(target));
    }

    /**
     * Calcule la production affichée sur une période en appliquant la règle mensuelle:
     * Production_affichee = E_reel + (((E_reel / M) + (E_theorique / J)) / 2) × N.
     *
     * Pour une période qui chevauche plusieurs mois, la règle est appliquée mois par mois
     * sur les jours de production inclus dans la période.
     */
    public DisplayedProductionComputation computeDisplayedProduction(
            DailySiteProduction target,
            LocalDate startDate,
            LocalDate endDate
    ) {
        if (target == null || target.document() == null || startDate == null || endDate == null || endDate.isBefore(startDate)) {
            return new DisplayedProductionComputation(0.0, 0.0, null, 0, 0);
        }

        double realKwh = 0.0;
        double estimatedKwh = 0.0;
        int monitoredDays = 0;
        int estimatedDays = 0;
        boolean hasUsableValue = false;

        YearMonth cursor = YearMonth.from(startDate);
        YearMonth endMonth = YearMonth.from(endDate);
        while (!cursor.isAfter(endMonth)) {
            LocalDate monthStart = cursor.atDay(1);
            LocalDate monthEnd = cursor.atEndOfMonth();
            LocalDate rangeStart = maxDate(startDate, monthStart);
            LocalDate rangeEnd = minDate(endDate, monthEnd);

            MonthlyDisplayedProduction month = computeDisplayedProductionForMonth(target, rangeStart, rangeEnd);
            realKwh += month.realKwh();
            estimatedKwh += month.estimatedKwh();
            monitoredDays += month.monitoredDays();
            estimatedDays += month.estimatedDays();
            hasUsableValue = hasUsableValue || month.hasUsableValue();

            cursor = cursor.plusMonths(1);
        }

        if (!hasUsableValue) {
            return new DisplayedProductionComputation(round2(realKwh), round2(estimatedKwh), null, monitoredDays, estimatedDays);
        }

        return new DisplayedProductionComputation(
                round2(realKwh),
                round2(estimatedKwh),
                round2(realKwh + estimatedKwh),
                monitoredDays,
                estimatedDays
        );
    }

    private MonthlyDisplayedProduction computeDisplayedProductionForMonth(
            DailySiteProduction target,
            LocalDate startDate,
            LocalDate endDate
    ) {
        int days = 0;
        int monitoredDays = 0;
        double realKwh = 0.0;
        double theoreticalKwh = 0.0;

        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            if (isProductionDay(target, cursor)) {
                days++;
                Double real = valueForDay(target, cursor);
                if (real != null && real > EPSILON) {
                    monitoredDays++;
                    realKwh += real;
                }

                Double theoretical = theoreticalDailyKwh(target, cursor);
                if (theoretical != null) {
                    theoreticalKwh += theoretical;
                }
            }
            cursor = cursor.plusDays(1);
        }

        if (days <= 0) {
            return new MonthlyDisplayedProduction(0.0, 0.0, 0, 0, false);
        }

        int missingDays = Math.max(0, days - monitoredDays);
        if (monitoredDays <= 0) {
            return new MonthlyDisplayedProduction(round2(realKwh), 0.0, 0, missingDays, false);
        }

        double realAverage = realKwh / monitoredDays;
        double theoreticalAverage = theoreticalKwh / days;
        double estimationPerMissingDay = (realAverage + theoreticalAverage) / 2.0;
        double estimated = estimationPerMissingDay * missingDays;

        return new MonthlyDisplayedProduction(
                round2(realKwh),
                round2(estimated),
                monitoredDays,
                missingDays,
                true
        );
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

    private LocalDate maxDate(LocalDate left, LocalDate right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }

    private LocalDate minDate(LocalDate left, LocalDate right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isBefore(right) ? left : right;
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

    private record MonthlyDisplayedProduction(
            Double realKwh,
            Double estimatedKwh,
            int monitoredDays,
            int estimatedDays,
            boolean hasUsableValue
    ) {
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
