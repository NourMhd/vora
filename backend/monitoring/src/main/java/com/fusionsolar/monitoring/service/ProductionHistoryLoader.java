package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.document.SiteHistoryDocument;
import com.fusionsolar.monitoring.persistence.enums.HistoryGranularity;
import com.fusionsolar.monitoring.persistence.repositories.SiteHistoryRepository;
import com.fusionsolar.monitoring.service.ProductionEstimationService.DailyCoverage;
import com.fusionsolar.monitoring.service.ProductionEstimationService.DailySiteProduction;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Reads cached daily observations (MONTH history), without provider calls or counter reads. */
final class ProductionHistoryLoader {
    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");
    private ProductionHistoryLoader() {}

    static String accountGroup(SiteDocument site) {
        if (!absent(site.getProviderAccountGroup())) return site.getProviderAccountGroup().trim();
        if (!absent(site.getProviderAccountKey())) return site.getProviderAccountKey().trim();
        return "primary";
    }

    static boolean unambiguous(SiteDocument site, List<SiteDocument> inventory) {
        return inventory.stream().filter(other -> other.getBrand() == site.getBrand()
                && Objects.equals(other.getSiteCode(), site.getSiteCode()))
                .map(ProductionHistoryLoader::accountGroup).distinct().count() == 1;
    }

    static DailySiteProduction load(SiteDocument site, String code, String name,
            SiteHistoryRepository repository, ProductionEstimationService engine,
            boolean allowUnattributed, LocalDate yesterday) {
        Map<LocalDate, Double> values = new HashMap<>();
        Set<LocalDate> present = new HashSet<>();
        Map<LocalDate, DailyCoverage> coverage = new HashMap<>();
        LocalDate origin = ProductionEstimationService.productionOrigin(site);
        if (site != null && origin != null && yesterday != null && !origin.isAfter(yesterday)
                && site.getBrand() != null && !absent(site.getSiteCode())) {
            List<SiteHistoryDocument> histories = repository.findAllByBrandAndSiteCodeAndGranularity(
                    site.getBrand(), site.getSiteCode(), HistoryGranularity.MONTH);
            Map<YearMonth, SiteHistoryDocument> selected = new TreeMap<>();
            String group = accountGroup(site);
            if (histories != null) for (SiteHistoryDocument history : histories) {
                if (history == null || history.getBucket() == null) continue;
                YearMonth month;
                try { month = YearMonth.parse(history.getBucket()); }
                catch (DateTimeParseException ex) { continue; }
                if (month.isBefore(YearMonth.from(origin)) || month.isAfter(YearMonth.from(yesterday))) continue;
                boolean scoped = Objects.equals(group, history.getProviderAccountGroup());
                boolean legacy = absent(history.getProviderAccountGroup())
                        && (absent(history.getProviderAccountKey()) ? allowUnattributed
                                : Objects.equals(history.getProviderAccountKey(), site.getProviderAccountKey()));
                if (!scoped && !legacy) continue;
                selected.merge(month, history, (left, right) -> better(left, right, group));
            }
            for (SiteHistoryDocument history : selected.values()) {
                if (history.getPoints() == null) continue;
                for (var point : history.getPoints()) {
                    if (point == null || point.getCollectTime() == null) continue;
                    long timestamp = point.getCollectTime();
                    LocalDate day = Instant.ofEpochMilli(timestamp < 1_000_000_000_000L ? timestamp * 1000 : timestamp)
                            .atZone(TUNIS_ZONE).toLocalDate();
                    if (day.isBefore(origin) || day.isAfter(yesterday)) continue;
                    present.add(day);
                    Double value = point.getValue();
                    if (value == null || !Double.isFinite(value) || value < 0) continue;
                    Double previous = values.get(day);
                    if (previous != null && previous >= value) continue;
                    values.put(day, value);
                    DailyCoverage status = DailyCoverage.fromHistoryStatus(point.getStatus());
                    if (status == null) coverage.remove(day); else coverage.put(day, status);
                }
            }
        }
        String governorate = engine.governorateForSite(site);
        return new DailySiteProduction(site, code, name, governorate, engine.zoneForGovernorate(governorate),
                site == null || site.getInstalledPowerKw() == null ? 0 : site.getInstalledPowerKw(),
                site == null ? null : site.getSwapDate(), values, present, coverage);
    }

    private static SiteHistoryDocument better(SiteHistoryDocument left, SiteHistoryDocument right, String group) {
        // Match the repository's scoped-first, nonempty-first, newest-document policy.
        var order = Comparator.comparingInt((SiteHistoryDocument h) -> Objects.equals(group, h.getProviderAccountGroup()) ? 1 : 0)
                .thenComparingInt(h -> h.getPoints() != null && !h.getPoints().isEmpty() ? 1 : 0)
                .thenComparing(ProductionHistoryLoader::lastUsefulInstant);
        return order.compare(left, right) >= 0 ? left : right;
    }

    private static Instant lastUsefulInstant(SiteHistoryDocument h) {
        if (h.getUpdatedAt() != null) return h.getUpdatedAt();
        if (h.getLastSyncAt() != null) return h.getLastSyncAt();
        return h.getCreatedAt() != null ? h.getCreatedAt() : Instant.EPOCH;
    }

    private static boolean absent(String value) { return value == null || value.isBlank(); }
}
