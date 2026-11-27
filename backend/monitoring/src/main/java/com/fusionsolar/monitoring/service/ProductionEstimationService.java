package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.util.SiteProductionPolicy;
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
                {3.230, 3.299, 3.443}, // Janvier
                {3.703, 3.912, 4.080}, // Février
                {4.233, 4.413, 4.607}, // Mars
                {4.814, 4.960, 5.065}, // Avril
                {5.131, 5.216, 5.315}, // Mai
                {5.415, 5.506, 5.606}, // Juin
                {5.737, 5.782, 5.747}, // Juillet
                {5.654, 5.529, 5.313}, // Août
                {4.949, 4.693, 4.509}, // Septembre
                {4.393, 4.219, 3.993}, // Octobre
                {3.723, 3.532, 3.374}, // Novembre
                {3.226, 3.165, 3.170}  // Décembre
        },
        "Zone B", new double[][]{
                {3.794, 3.398, 3.546}, // Janvier
                {3.814, 4.030, 4.202}, // Février
                {4.360, 4.545, 4.745}, // Mars
                {4.958, 5.109, 5.217}, // Avril
                {5.285, 5.373, 5.474}, // Mai
                {5.577, 5.671, 5.775}, // Juin
                {5.909, 5.956, 5.919}, // Juillet
                {5.823, 5.695, 5.472}, // Août
                {5.097, 4.834, 4.644}, // Septembre
                {4.524, 4.346, 4.113}, // Octobre
                {3.834, 3.638, 3.475}, // Novembre
                {3.684, 3.641, 3.265}  // Décembre
        },
        "Zone C", new double[][]{
                {4.049, 3.500, 3.652}, // Janvier
                {3.928, 4.150, 4.328}, // Février
                {4.491, 4.682, 4.887}, // Mars
                {5.107, 5.262, 5.373}, // Avril
                {5.443, 5.534, 5.639}, // Mai
                {5.744, 5.841, 5.948}, // Juin
                {6.086, 6.134, 6.096}, // Juillet
                {5.998, 5.866, 5.636}, // Août
                {5.250, 4.979, 4.784}, // Septembre
                {4.660, 4.476, 4.237}, // Octobre
                {3.949, 3.747, 4.044}, // Novembre
                {3.913, 3.867, 3.897}  // Décembre
        }
);

    /** Shared report origin: the earliest known physical/administrative start. */
    public static LocalDate productionOrigin(SiteDocument site) {
        if (site == null) return null;
        LocalDate mes = parseFlexibleDate(site.getGridConnectionDate());
        LocalDate swap = site.getSwapDate();
        return swap == null ? mes : mes == null || swap.isBefore(mes) ? swap : mes;
    }

    static LocalDate parseFlexibleDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String trimmed = value.trim();

        if (trimmed.matches("\\d{10,13}")) {
            try {
                long raw = Long.parseLong(trimmed);
                long epochMillis = raw < 1_000_000_000_000L ? raw * 1000 : raw;
                return java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.of("Africa/Tunis")).toLocalDate();
            } catch (NumberFormatException ignored) {
                // Try formatted dates below.
            }
        }

        if (trimmed.length() >= 10) {
            String datePart = trimmed.substring(0, 10);
            try {
                return LocalDate.parse(datePart);
            } catch (java.time.format.DateTimeParseException ignored) {
                // Try full value below.
            }
        }

        try {
            return LocalDate.parse(trimmed);
        } catch (java.time.format.DateTimeParseException ignored) {
            // Try display formats below.
        }

        try {
            return LocalDate.parse(trimmed, java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT));
        } catch (java.time.format.DateTimeParseException ignored) {
            // Try slash ISO-like format below.
        }

        try {
            return LocalDate.parse(trimmed, java.time.format.DateTimeFormatter.ofPattern("uuuu/MM/dd"));
        } catch (java.time.format.DateTimeParseException ignored) {
            return null;
        }
    }

    public record SiteReference(LocalDate start, LocalDate end, int days, double coefficient) {}

    /** Longest uninterrupted positive history, independent of report filter. */
    public SiteReference continuousReference(DailySiteProduction site, LocalDate yesterday) {
        LocalDate origin = site == null ? null : productionOrigin(site.document());
        if (origin == null || yesterday == null || !validReportPower(site.installedPowerKw())
                || !THEORETICAL_DAILY_YIELD.containsKey(site.zone() == null ? "" : site.zone())) return null;
        SiteReference best = new SiteReference(null, null, 0, 0.84);
        if (site.valuesByDate() == null || SiteProductionPolicy.forcesZero(site.document())) return best;
        LocalDate first = null, last = null;
        int days = 0;
        double actual = 0, expected = 0;
        for (var entry : new java.util.TreeMap<>(site.valuesByDate()).entrySet()) {
            LocalDate day = entry.getKey();
            Double value = entry.getValue();
            if (day.isBefore(origin) || day.isAfter(yesterday)) continue;
            if (value == null || !Double.isFinite(value) || value <= 0) {
                first = last = null; days = 0; actual = expected = 0;
                continue;
            }
            if (last == null || !last.plusDays(1).equals(day)) {
                first = day; days = 0; actual = expected = 0;
            }
            last = day; days++;
            actual += value;
            expected += theoreticalDailyYieldPerKwc(site.zone(), day) * site.installedPowerKw();
            double coefficient = expected > 0 ? actual / expected : Double.NaN;
            // Treat only floating-point noise as a tie, not a real difference in yield.
            double tolerance = 1e-12 * Math.max(Math.abs(coefficient), Math.abs(best.coefficient()));
            boolean betterAtSameLength = days == best.days() && (coefficient > best.coefficient() + tolerance
                    || (Math.abs(coefficient - best.coefficient()) <= tolerance && day.isAfter(best.end())));
            if (Double.isFinite(coefficient) && (days > best.days() || betterAtSameLength)) {
                best = new SiteReference(first, day, days, coefficient);
            }
        }
        return best;
    }

    /** The daily decision shared by the daily, monthly and annual reports. */
    public DailyProductionDecision continuousDailyDecision(DailySiteProduction site, LocalDate day,
            LocalDate yesterday, SiteReference reference) {
        LocalDate origin = site == null ? null : productionOrigin(site.document());
        if (site == null || site.document() == null) return DailyProductionDecision.unavailable("SITE_NOT_FOUND");
        if (origin == null || day == null || yesterday == null || day.isBefore(origin) || day.isAfter(yesterday))
            return DailyProductionDecision.unavailable("NOT_SWAPPED_YET");
        if (SiteProductionPolicy.forcesZero(site.document()))
            return new DailyProductionDecision(0.0, 0.0, false, false, "FORCED_ZERO");
        if (reference == null) return DailyProductionDecision.unavailable("NO_ESTIMATION_BASIS");
        Double value = rawReportValue(site, day);
        if (value != null && value > 0) return DailyProductionDecision.monitored(value);
        double replacement = theoreticalDailyYieldPerKwc(site.zone(), day) * site.installedPowerKw() * reference.coefficient();
        if (!Double.isFinite(replacement)) return DailyProductionDecision.unavailable("NO_ESTIMATION_BASIS");
        return DailyProductionDecision.estimated(replacement, missingStatus(site, day,
                reference.days() == 0 ? "THEORETICAL_FALLBACK_ESTIMATED" : "SITE_HISTORY_ESTIMATED"));
    }

    /** Monthly rounding boundary shared by all reports, including multi-month totals. */
    public DisplayedProductionComputation continuousProduction(DailySiteProduction site, LocalDate start,
            LocalDate end, LocalDate yesterday, SiteReference reference) {
        LocalDate origin = site == null ? null : productionOrigin(site.document());
        if (origin == null || start == null || end == null || yesterday == null) return unavailableDisplayedProduction();
        if (start.isBefore(origin)) start = origin;
        if (end.isAfter(yesterday)) end = yesterday;
        if (start.isAfter(end)) return new DisplayedProductionComputation(0.0, 0.0, 0.0, 0, 0);
        if (SiteProductionPolicy.forcesZero(site.document())) return new DisplayedProductionComputation(0.0, 0.0, 0.0, 0, 0);
        if (reference == null) return unavailableDisplayedProduction();
        double real = 0, estimated = 0;
        int monitored = 0, missing = 0;
        for (LocalDate from = start; !from.isAfter(end); ) {
            LocalDate to = YearMonth.from(from).atEndOfMonth();
            if (to.isAfter(end)) to = end;
            double monthReal = 0, monthEstimated = 0;
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                var decision = continuousDailyDecision(site, day, yesterday, reference);
                if (decision.monitored()) { monthReal += decision.effectiveValue(); monitored++; }
                else if (decision.estimated()) { monthEstimated += decision.effectiveValue(); missing++; }
                else return unavailableDisplayedProduction();
            }
            real += round2(monthReal); estimated += round2(monthEstimated);
            from = to.plusDays(1);
        }
        return new DisplayedProductionComputation(round2(real), round2(estimated), round2(real + estimated), monitored, missing);
    }

    public Double continuousExpected(String zone, double power, LocalDate start, LocalDate end) {
        if (start == null || end == null) return null;
        if (start.isAfter(end)) return 0.0;
        if (!validReportPower(power) || zone == null || !THEORETICAL_DAILY_YIELD.containsKey(zone)) return null;
        double total = 0;
        for (LocalDate from = start; !from.isAfter(end); ) {
            LocalDate to = YearMonth.from(from).atEndOfMonth();
            if (to.isAfter(end)) to = end;
            total += round2(theoreticalYieldPerKwc(zone, from, to) * power);
            from = to.plusDays(1);
        }
        return round2(total);
    }

    /** Compatibility entry points: all report estimation now uses the site-only reference. */
    public DailyProductionDecision decideDailyProduction(DailySiteProduction target, LocalDate day,
            Map<YearMonth, Double> ignoredMonthlyAverages) {
        return decideDailyProduction(target, List.of(), day);
    }

    public DailyProductionDecision decideDailyProduction(DailySiteProduction target,
            List<DailySiteProduction> ignoredPeers, LocalDate day) {
        LocalDate yesterday = LocalDate.now(java.time.ZoneId.of("Africa/Tunis")).minusDays(1);
        return continuousDailyDecision(target, day, yesterday, continuousReference(target, yesterday));
    }

    public DisplayedProductionComputation computeDisplayedProduction(DailySiteProduction target,
            LocalDate start, LocalDate end) {
        return computeDisplayedProduction(target, List.of(), start, end);
    }

    public DisplayedProductionComputation computeDisplayedProduction(DailySiteProduction target,
            List<DailySiteProduction> ignoredPeers, LocalDate start, LocalDate end) {
        LocalDate yesterday = LocalDate.now(java.time.ZoneId.of("Africa/Tunis")).minusDays(1);
        return continuousProduction(target, start, end, yesterday, continuousReference(target, yesterday));
    }

    private boolean validReportPower(double power) { return Double.isFinite(power) && power > 0; }

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

    /** Unrounded measurement for report classification and aggregation. */
    public Double rawReportValue(DailySiteProduction site, LocalDate day) {
        if (site == null || day == null || site.valuesByDate() == null) return null;
        Double value = site.valuesByDate().get(day);
        return value != null && Double.isFinite(value) && value >= 0 ? value : null;
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

        Double rawValue = rawReportValue(site, day);
        if (rawValue == null) {
            return "NULL_" + suffix;
        }

        if (rawValue <= 0) {
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

    public enum DailyCoverage {
        COMPLETE, PARTIAL;

        static DailyCoverage fromHistoryStatus(String status) {
            if ("COMPLETE".equalsIgnoreCase(status)) return COMPLETE;
            if ("PARTIAL".equalsIgnoreCase(status)) return PARTIAL;
            return null;
        }
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
            Set<LocalDate> presentDates,
            Map<LocalDate, DailyCoverage> coverageByDate
    ) {
        public DailySiteProduction(SiteDocument document, String siteCode, String siteName,
                String governorate, String zone, double installedPowerKw, LocalDate swapDate,
                Map<LocalDate, Double> valuesByDate, Set<LocalDate> presentDates) {
            this(document, siteCode, siteName, governorate, zone, installedPowerKw, swapDate,
                    valuesByDate, presentDates, Map.of());
        }
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
