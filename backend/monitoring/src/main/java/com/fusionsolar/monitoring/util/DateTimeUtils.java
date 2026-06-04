package com.fusionsolar.monitoring.util;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class DateTimeUtils {

    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private DateTimeUtils() {
    }

    public static LocalDate parseStrictDay(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException("Invalid day format. Expected yyyy-MM-dd");
        }
        return LocalDate.parse(value, DAY_FORMAT);
    }

    public static YearMonth parseStrictMonth(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}")) {
            throw new IllegalArgumentException("Invalid month format. Expected yyyy-MM");
        }
        return YearMonth.parse(value);
    }

    public static int parseStrictYear(String value) {
        if (value == null || !value.matches("\\d{4}")) {
            throw new IllegalArgumentException("Invalid year format. Expected yyyy");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new DateTimeParseException("Invalid year", value, 0);
        }
    }

    public static long toDayTimestamp(LocalDate date) {
        return date.atStartOfDay(TUNIS_ZONE)
                .toInstant()
                .toEpochMilli();
    }

    public static long toMonthTimestamp(YearMonth yearMonth) {
        return yearMonth.atDay(1)
                .atStartOfDay(TUNIS_ZONE)
                .toInstant()
                .toEpochMilli();
    }

    public static long toYearTimestamp(int year) {
        return LocalDate.of(year, 1, 1)
                .atStartOfDay(TUNIS_ZONE)
                .toInstant()
                .toEpochMilli();
    }
}
