package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.dto.MaxProductionReportDto;
import com.fusionsolar.monitoring.dto.MaxProductionRowDto;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.document.SiteHistoryDocument;
import com.fusionsolar.monitoring.persistence.enums.HistoryGranularity;
import com.fusionsolar.monitoring.persistence.enums.SiteStatus;
import com.fusionsolar.monitoring.persistence.repositories.SiteHistoryRepository;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import com.fusionsolar.monitoring.util.SiteNameNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class MaxProductionService {

    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");

    private final SiteRepository siteRepository;
    private final SiteHistoryRepository siteHistoryRepository;
    private final ProductionEstimationService productionEstimationService;
    private final CurrentSiteAccessService currentSiteAccessService;

    public MaxProductionReportDto getMaxProductionReport() {
        LocalDate today = LocalDate.now(TUNIS_ZONE);
        LocalDate calculatedUntil = today.minusDays(1);

        List<SiteDocument> healthySites = siteRepository.findAllByOrderBySiteNameAsc().stream()
                .filter(currentSiteAccessService::canAccess)
                .filter(site -> site.getStatus() == SiteStatus.HEALTHY)
                .sorted(Comparator
                        .comparing((SiteDocument site) -> safeText(site.getSiteName()))
                        .thenComparing(site -> safeText(site.getSiteCode())))
                .toList();

        MaxScanContext context = new MaxScanContext();
        List<MaxProductionRowDto> rows = healthySites.stream()
                .map(site -> buildRow(site, calculatedUntil, context))
                .toList();

        int withValues = (int) rows.stream()
                .filter(row -> row.getMaxProductionKwh() != null)
                .count();

        return new MaxProductionReportDto(
                context.oldestDate == null ? null : context.oldestDate.toString(),
                calculatedUntil.toString(),
                rows.size(),
                withValues,
                rows
        );
    }

    private MaxProductionRowDto buildRow(SiteDocument site, LocalDate calculatedUntil, MaxScanContext context) {
        LocalDate commissioningDate = parseFlexibleDate(site.getGridConnectionDate());
        Map<LocalDate, Double> dailyValues = loadRealMonitoredDailyValues(site, commissioningDate, calculatedUntil, context);

        Map.Entry<LocalDate, Double> maxEntry = dailyValues.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > ProductionEstimationService.EPSILON)
                .max((left, right) -> {
                    int valueCompare = Double.compare(left.getValue(), right.getValue());
                    if (valueCompare != 0) {
                        return valueCompare;
                    }
                    return left.getKey().compareTo(right.getKey());
                })
                .orElse(null);

        Double installedPowerKw = productionEstimationService.round2(site.getInstalledPowerKw());
        Double maxProduction = maxEntry == null ? null : productionEstimationService.round2(maxEntry.getValue());
        Double kwhPerKwc = null;

        if (maxProduction != null && installedPowerKw != null && installedPowerKw > ProductionEstimationService.EPSILON) {
            kwhPerKwc = productionEstimationService.round2(maxProduction / installedPowerKw);
        }

        return new MaxProductionRowDto(
                site.getBrand() == null ? null : site.getBrand().name(),
                site.getSiteCode(),
                SiteNameNormalizer.normalize(site.getSiteName()),
                installedPowerKw,
                maxProduction,
                maxEntry == null ? null : maxEntry.getKey().toString(),
                kwhPerKwc,
                maxEntry == null ? "NO_MONITORED_VALUE" : "OK"
        );
    }

    private Map<LocalDate, Double> loadRealMonitoredDailyValues(
            SiteDocument site,
            LocalDate commissioningDate,
            LocalDate calculatedUntil,
            MaxScanContext context
    ) {
        Map<LocalDate, Double> dailyValues = new HashMap<>();

        if (site.getBrand() == null || site.getSiteCode() == null || site.getSiteCode().isBlank()) {
            return dailyValues;
        }

        List<SiteHistoryDocument> histories = siteHistoryRepository.findAllByBrandAndSiteCodeAndGranularity(
                site.getBrand(),
                site.getSiteCode(),
                HistoryGranularity.MONTH
        );

        for (SiteHistoryDocument history : histories) {
            if (history == null || history.getPoints() == null || history.getPoints().isEmpty()) {
                continue;
            }

            for (HistoryPointDto point : history.getPoints()) {
                if (point == null || point.getCollectTime() == null) {
                    continue;
                }

                LocalDate day = toLocalDate(point.getCollectTime());
                if (day == null || day.isAfter(calculatedUntil)) {
                    continue;
                }

                if (commissioningDate != null && day.isBefore(commissioningDate)) {
                    continue;
                }

                Double value = productionEstimationService.normalizeHistoryValue(point.getValue());
                if (value == null || value <= ProductionEstimationService.EPSILON) {
                    continue;
                }

                if (context.oldestDate == null || day.isBefore(context.oldestDate)) {
                    context.oldestDate = day;
                }

                keepBestDailyValue(dailyValues, day, value);
            }
        }

        return dailyValues;
    }

    /**
     * L'historique MONTH doit contenir une valeur journalière finale par date.
     * Si plusieurs points existent pour la même date à cause de resynchronisations
     * ou doublons API, ils ne doivent jamais être additionnés. On garde la plus
     * grande valeur positive observée pour cette journée, ce qui évite le cas
     * 20,95 kWh + 20,95 kWh = 41,90 kWh.
     */
    private void keepBestDailyValue(Map<LocalDate, Double> dailyValues, LocalDate day, Double value) {
        if (day == null || value == null || value <= ProductionEstimationService.EPSILON) {
            return;
        }

        dailyValues.merge(day, value, Math::max);
    }

    private LocalDate toLocalDate(Long collectTime) {
        if (collectTime == null) {
            return null;
        }

        long epochMillis = collectTime < 1_000_000_000_000L ? collectTime * 1000 : collectTime;
        return Instant.ofEpochMilli(epochMillis).atZone(TUNIS_ZONE).toLocalDate();
    }


    private LocalDate parseFlexibleDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String trimmed = value.trim();

        if (trimmed.matches("\\d{10,13}")) {
            try {
                long raw = Long.parseLong(trimmed);
                long epochMillis = raw < 1_000_000_000_000L ? raw * 1000 : raw;
                return Instant.ofEpochMilli(epochMillis).atZone(TUNIS_ZONE).toLocalDate();
            } catch (NumberFormatException ignored) {
                // Try formatted dates below.
            }
        }

        if (trimmed.length() >= 10) {
            String datePart = trimmed.substring(0, 10);
            try {
                return LocalDate.parse(datePart);
            } catch (DateTimeParseException ignored) {
                // Try full value below.
            }
        }

        try {
            return LocalDate.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            // Try display formats below.
        }

        try {
            return LocalDate.parse(trimmed, DateTimeFormatter.ofPattern("dd/MM/uuuu"));
        } catch (DateTimeParseException ignored) {
            // Try slash ISO-like format below.
        }

        try {
            return LocalDate.parse(trimmed, DateTimeFormatter.ofPattern("uuuu/MM/dd"));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String safeText(String value) {
        return Objects.toString(value, "").toUpperCase(Locale.ROOT);
    }

    private static final class MaxScanContext {
        private LocalDate oldestDate;
    }
}
