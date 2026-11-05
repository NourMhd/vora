package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.document.SiteHistoryDocument;
import com.fusionsolar.monitoring.persistence.enums.HistoryGranularity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Règles communes pour éviter les trous dans les buckets MONTH.
 *
 * Un mois n'est considéré OK que si tous les jours attendus déjà terminés sont présents
 * avec une valeur kWh exploitable, et si la dernière synchronisation a eu lieu après
 * la fin de la période attendue + une marge de finalisation fournisseur.
 */
public final class HistoryBackfillCompletenessSupport {

    private static final DateTimeFormatter DISPLAY_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private HistoryBackfillCompletenessSupport() {
    }

    public static LocalDate firstHistoryDate(
            SiteDocument site,
            LocalDate referenceDate,
            int maxLookbackMonths,
            ZoneId zone
    ) {
        ZoneId safeZone = zone != null ? zone : ZoneId.systemDefault();
        LocalDate safeReferenceDate = referenceDate != null ? referenceDate : LocalDate.now(safeZone);

        LocalDate maxLookbackStart = YearMonth.from(safeReferenceDate)
                .minusMonths(Math.max(1, maxLookbackMonths) - 1L)
                .atDay(1);

        LocalDate productionStartDate = site != null && site.getSwapDate() != null
                ? site.getSwapDate()
                : parseDate(site != null ? site.getGridConnectionDate() : null, safeZone);

        if (productionStartDate == null) {
            return maxLookbackStart;
        }

        return productionStartDate.isBefore(maxLookbackStart)
                ? maxLookbackStart
                : productionStartDate;
    }

    public static boolean isMonthBucketAfterSiteStart(
            SiteDocument site,
            String bucket,
            LocalDate referenceDate,
            int maxLookbackMonths,
            ZoneId zone
    ) {
        try {
            YearMonth month = YearMonth.parse(bucket);
            LocalDate firstDate = firstHistoryDate(site, referenceDate, maxLookbackMonths, zone);
            return !month.atEndOfMonth().isBefore(firstDate);
        } catch (Exception e) {
            return true;
        }
    }

    public static boolean isMonthHistoryDue(
            SiteDocument site,
            SiteHistoryDocument document,
            String bucket,
            LocalDate today,
            int maxLookbackMonths,
            long emptyHistoryRetryMs,
            long incompleteMonthRefreshMs,
            long invalidValueMonthRefreshMs,
            long currentMonthRefreshMs,
            int finalizationGraceHours,
            ZoneId zone
    ) {
        ZoneId safeZone = zone != null ? zone : ZoneId.systemDefault();
        LocalDate safeToday = today != null ? today : LocalDate.now(safeZone);

        MonthCompleteness completeness =
                inspectMonth(site, document, bucket, safeToday, maxLookbackMonths, safeZone);

        if (completeness.expectedDays() <= 0) {
            return false;
        }

        if (document == null) {
            return true;
        }

        boolean hasPoints = document.getPoints() != null && !document.getPoints().isEmpty();
        if (!hasPoints) {
            return isRetryDue(document, emptyHistoryRetryMs);
        }

        if (completeness.missingDays() > 0) {
            return isRetryDue(document, incompleteMonthRefreshMs);
        }

        if (completeness.invalidValueDays() > 0) {
            return isRetryDue(document, invalidValueMonthRefreshMs);
        }

        if (isCurrentMonth(bucket, safeToday) && isRefreshDue(document, currentMonthRefreshMs)) {
            return true;
        }

        Instant finalizationDeadline = completeness.expectedEnd()
                .plusDays(1)
                .atStartOfDay(safeZone)
                .plusHours(Math.max(0, finalizationGraceHours))
                .toInstant();

        Instant now = Instant.now();
        if (now.isBefore(finalizationDeadline)) {
            return false;
        }

        return document.getLastSyncAt() == null
                || document.getLastSyncAt().isBefore(finalizationDeadline);
    }

    public static MonthCompleteness inspectMonth(
            SiteDocument site,
            SiteHistoryDocument document,
            String bucket,
            LocalDate today,
            int maxLookbackMonths,
            ZoneId zone
    ) {
        try {
            ZoneId safeZone = zone != null ? zone : ZoneId.systemDefault();
            MonthWindow window = expectedMonthWindow(
                    site,
                    bucket,
                    today,
                    maxLookbackMonths,
                    safeZone
            );

            if (window.expectedDays() <= 0) {
                return new MonthCompleteness(
                        window.expectedStart(),
                        window.expectedEnd(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        0
                );
            }

            Map<LocalDate, List<HistoryPointDto>> pointsByDay =
                    document == null || document.getPoints() == null
                            ? Map.of()
                            : document.getPoints().stream()
                            .filter(point -> point != null && point.getCollectTime() != null)
                            .collect(Collectors.groupingBy(
                                    point -> toLocalDate(point.getCollectTime(), safeZone),
                                    LinkedHashMap::new,
                                    Collectors.toList()
                            ));

            int expectedDays = 0;
            int presentDays = 0;
            int validCollectedDays = 0;
            int missingDays = 0;
            int invalidValueDays = 0;

            LocalDate cursor = window.expectedStart();
            while (!cursor.isAfter(window.expectedEnd())) {
                expectedDays++;

                List<HistoryPointDto> dayPoints = pointsByDay.get(cursor);
                boolean present = dayPoints != null && !dayPoints.isEmpty();
                boolean valid = present
                        && dayPoints.stream().anyMatch(HistoryBackfillCompletenessSupport::hasValidValue);

                if (present) {
                    presentDays++;
                }

                if (valid) {
                    validCollectedDays++;
                } else if (present) {
                    invalidValueDays++;
                } else {
                    missingDays++;
                }

                cursor = cursor.plusDays(1);
            }

            return new MonthCompleteness(
                    window.expectedStart(),
                    window.expectedEnd(),
                    expectedDays,
                    validCollectedDays,
                    Math.max(0, expectedDays - validCollectedDays),
                    presentDays,
                    missingDays,
                    invalidValueDays
            );
        } catch (Exception e) {
            ZoneId safeZone = zone != null ? zone : ZoneId.systemDefault();
            LocalDate fallbackToday = today != null ? today : LocalDate.now(safeZone);
            return new MonthCompleteness(
                    fallbackToday,
                    fallbackToday.minusDays(1),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0
            );
        }
    }

    public static List<HistoryPointDto> clampMonthPointsToExpectedWindow(
            SiteDocument site,
            String bucket,
            List<HistoryPointDto> points,
            LocalDate today,
            int maxLookbackMonths,
            ZoneId zone
    ) {
        List<HistoryPointDto> safePoints = points != null ? points : List.of();

        if (safePoints.isEmpty()) {
            return List.of();
        }

        try {
            ZoneId safeZone = zone != null ? zone : ZoneId.systemDefault();

            MonthWindow window = expectedMonthWindow(
                    site,
                    bucket,
                    today,
                    maxLookbackMonths,
                    safeZone
            );

            if (window.expectedDays() <= 0) {
                return List.of();
            }

            return safePoints.stream()
                    .filter(point -> point != null && point.getCollectTime() != null)
                    .filter(point -> {
                        LocalDate pointDate = toLocalDate(point.getCollectTime(), safeZone);
                        return pointDate != null
                                && !pointDate.isBefore(window.expectedStart())
                                && !pointDate.isAfter(window.expectedEnd());
                    })
                    .sorted(Comparator.comparing(
                            HistoryPointDto::getCollectTime,
                            Comparator.nullsLast(Long::compareTo)
                    ))
                    .toList();

        } catch (Exception e) {
            return sortPoints(safePoints);
        }
    }

    public static boolean isMonthHistoryComplete(
            SiteDocument site,
            SiteHistoryDocument document,
            String bucket,
            LocalDate today,
            int maxLookbackMonths,
            ZoneId zone
    ) {
        MonthCompleteness completeness =
                inspectMonth(site, document, bucket, today, maxLookbackMonths, zone);

        return completeness.expectedDays() > 0
                && completeness.missingOrInvalidDays() == 0;
    }

    public static String monthStatusLabel(MonthCompleteness completeness) {
        if (completeness == null || completeness.expectedDays() <= 0) {
            return "NO_EXPECTED_DAY";
        }

        if (completeness.missingOrInvalidDays() <= 0) {
            return "COMPLETE";
        }

        if (completeness.missingDays() > 0 && completeness.invalidValueDays() > 0) {
            return "INCOMPLETE";
        }

        if (completeness.missingDays() == 0 && completeness.invalidValueDays() > 0) {
            return "STRUCTURE_COMPLETE_INVALID_VALUES";
        }

        return "INCOMPLETE";
    }

    public static String monthDueReason(MonthCompleteness completeness) {
        if (completeness == null || completeness.expectedDays() <= 0) {
            return "NO_EXPECTED_DAY";
        }

        if (completeness.presentDays() <= 0) {
            return "EMPTY_BUCKET";
        }

        if (completeness.missingDays() > 0 && completeness.invalidValueDays() > 0) {
            return "MISSING_DAYS_AND_ZERO_OR_NULL_VALUES";
        }

        if (completeness.missingDays() > 0) {
            return "MISSING_DAYS";
        }

        if (completeness.invalidValueDays() > 0) {
            return "ZERO_OR_NULL_VALUES";
        }

        return "COMPLETE";
    }

    public static List<HistoryPointDto> mergeHistoryPoints(
            List<HistoryPointDto> existingPoints,
            List<HistoryPointDto> loadedPoints,
            HistoryGranularity granularity,
            ZoneId zone
    ) {
        ZoneId safeZone = zone != null ? zone : ZoneId.systemDefault();

        List<HistoryPointDto> safeExisting =
                existingPoints != null ? existingPoints : List.of();

        List<HistoryPointDto> safeLoaded =
                loadedPoints != null ? loadedPoints : List.of();

        if (safeExisting.isEmpty()) {
            return sortPoints(safeLoaded);
        }

        if (safeLoaded.isEmpty()) {
            return sortPoints(safeExisting);
        }

        Map<String, HistoryPointDto> merged = new LinkedHashMap<>();

        int index = 0;
        for (HistoryPointDto point : safeExisting) {
            if (point != null) {
                merged.put(pointKey(point, granularity, safeZone, index++), point);
            }
        }

        index = 0;
        for (HistoryPointDto loaded : safeLoaded) {
            if (loaded == null) {
                continue;
            }

            String key = pointKey(loaded, granularity, safeZone, index++);
            HistoryPointDto existing = merged.get(key);

            if (existing == null || shouldPreferLoadedPoint(existing, loaded)) {
                merged.put(key, loaded);
            }
        }

        return sortPoints(new ArrayList<>(merged.values()));
    }

    public static LocalDate toLocalDate(Long collectTime, ZoneId zone) {
        if (collectTime == null) {
            return null;
        }

        ZoneId safeZone = zone != null ? zone : ZoneId.systemDefault();
        long epochMillis = collectTime < 1_000_000_000_000L
                ? collectTime * 1000
                : collectTime;

        return Instant.ofEpochMilli(epochMillis).atZone(safeZone).toLocalDate();
    }

    private static MonthWindow expectedMonthWindow(
            SiteDocument site,
            String bucket,
            LocalDate today,
            int maxLookbackMonths,
            ZoneId zone
    ) {
        ZoneId safeZone = zone != null ? zone : ZoneId.systemDefault();

        YearMonth month = YearMonth.parse(bucket);
        LocalDate safeToday = today != null ? today : LocalDate.now(safeZone);
        LocalDate yesterday = safeToday.minusDays(1);

        LocalDate expectedStart = month.atDay(1);
        LocalDate expectedEnd = month.atEndOfMonth();

        LocalDate firstDate = firstHistoryDate(site, safeToday, maxLookbackMonths, safeZone);

        if (expectedStart.isBefore(firstDate)) {
            expectedStart = firstDate;
        }

        if (expectedEnd.isAfter(yesterday)) {
            expectedEnd = yesterday;
        }

        int expectedDays = 0;
        if (!expectedEnd.isBefore(expectedStart)) {
            expectedDays = (int) (expectedEnd.toEpochDay() - expectedStart.toEpochDay()) + 1;
        }

        return new MonthWindow(expectedStart, expectedEnd, expectedDays);
    }

    private static LocalDate parseDate(String raw, ZoneId zone) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        ZoneId safeZone = zone != null ? zone : ZoneId.systemDefault();
        String value = raw.trim();

        if (value.matches("^\\d{10,13}$")) {
            try {
                long epoch = Long.parseLong(value);
                long epochMillis = epoch < 1_000_000_000_000L
                        ? epoch * 1000
                        : epoch;

                return Instant.ofEpochMilli(epochMillis).atZone(safeZone).toLocalDate();
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ignored) {
            // Try display format below.
        }

        try {
            return LocalDate.parse(value, DISPLAY_DATE_FORMATTER);
        } catch (DateTimeParseException ignored) {
            // Try text cleanup below.
        }

        String firstTenChars = value.length() >= 10 ? value.substring(0, 10) : value;

        try {
            return LocalDate.parse(firstTenChars);
        } catch (DateTimeParseException ignored) {
            // Try slash format below.
        }

        try {
            return LocalDate.parse(firstTenChars, DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        } catch (DateTimeParseException ignored) {
            // Try year-month below.
        }

        String normalized = value.toUpperCase(Locale.ROOT).replace('/', '-');
        if (normalized.matches("^\\d{4}-\\d{2}$")) {
            return YearMonth.parse(normalized).atDay(1);
        }

        return null;
    }

    private static boolean hasValidValue(HistoryPointDto point) {
        return point != null
                && point.getCollectTime() != null
                && point.getValue() != null
                && Double.isFinite(point.getValue())
                && point.getValue() >= 0.0;
    }

    private static boolean shouldPreferLoadedPoint(HistoryPointDto existing, HistoryPointDto loaded) {
        if (loaded == null) {
            return false;
        }

        if (existing == null) {
            return true;
        }

        boolean loadedValid = loaded.getValue() != null
                && Double.isFinite(loaded.getValue())
                && loaded.getValue() >= 0.0;
        boolean existingValid = existing.getValue() != null
                && Double.isFinite(existing.getValue())
                && existing.getValue() >= 0.0;

        if (loadedValid) {
            return true;
        }

        return !existingValid;
    }

    private static String pointKey(
            HistoryPointDto point,
            HistoryGranularity granularity,
            ZoneId zone,
            int index
    ) {
        if (point == null || point.getCollectTime() == null) {
            return "missing-" + index;
        }

        if (granularity == HistoryGranularity.MONTH) {
            LocalDate date = toLocalDate(point.getCollectTime(), zone);
            return date != null ? date.toString() : String.valueOf(point.getCollectTime());
        }

        if (granularity == HistoryGranularity.YEAR) {
            LocalDate date = toLocalDate(point.getCollectTime(), zone);
            return date != null ? YearMonth.from(date).toString() : String.valueOf(point.getCollectTime());
        }

        if (granularity == HistoryGranularity.YEARLY) {
            LocalDate date = toLocalDate(point.getCollectTime(), zone);
            return date != null ? String.valueOf(date.getYear()) : String.valueOf(point.getCollectTime());
        }

        return String.valueOf(point.getCollectTime());
    }

    private static List<HistoryPointDto> sortPoints(List<HistoryPointDto> points) {
        return points == null
                ? List.of()
                : points.stream()
                .filter(point -> point != null)
                .sorted(Comparator.comparing(
                        HistoryPointDto::getCollectTime,
                        Comparator.nullsLast(Long::compareTo)
                ))
                .toList();
    }

    private static boolean isCurrentMonth(String bucket, LocalDate today) {
        if (bucket == null || today == null) {
            return false;
        }

        return YearMonth.from(today).toString().equals(bucket);
    }

    private static boolean isRefreshDue(SiteHistoryDocument document, long refreshMs) {
        if (document == null || document.getLastSyncAt() == null) {
            return true;
        }

        return document.getLastSyncAt()
                .isBefore(Instant.now().minusMillis(Math.max(60_000L, refreshMs)));
    }

    private static boolean isRetryDue(SiteHistoryDocument document, long retryMs) {
        if (document != null && document.getNextRetryAt() != null) {
            return !Instant.now().isBefore(document.getNextRetryAt());
        }
        if (document == null || document.getLastSyncAt() == null) {
            return true;
        }

        return !Instant.now()
                .isBefore(document.getLastSyncAt().plusMillis(Math.max(60_000L, retryMs)));
    }

    private record MonthWindow(
            LocalDate expectedStart,
            LocalDate expectedEnd,
            int expectedDays
    ) {
    }

    public record MonthCompleteness(
            LocalDate expectedStart,
            LocalDate expectedEnd,
            int expectedDays,
            int collectedDays,
            int missingOrInvalidDays,
            int presentDays,
            int missingDays,
            int invalidValueDays
    ) {
    }
}
