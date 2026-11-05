package com.fusionsolar.monitoring.provider.mapper;

import com.fusionsolar.monitoring.dto.DashboardSummaryDto;
import com.fusionsolar.monitoring.dto.DeviceItemDto;
import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.dto.SiteDetailDto;
import com.fusionsolar.monitoring.dto.SiteDevicesResponseDto;
import com.fusionsolar.monitoring.dto.SiteSummaryDto;
import com.fusionsolar.monitoring.persistence.document.SiteDeviceDocument;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.document.SiteHistoryDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.enums.HistoryGranularity;
import com.fusionsolar.monitoring.persistence.enums.SiteStatus;
import com.fusionsolar.monitoring.util.SiteNameNormalizer;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

@Component
public class SiteMongoMapper {

    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");
    private static final double EPSILON = 0.000001;
    private static final DateTimeFormatter DISPLAY_SWAP_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public SiteDocument toSiteDocument(
            Brand brand,
            SiteSummaryDto source,
            SiteDocument existing,
            Instant now
    ) {
        if (source == null) {
            throw new IllegalArgumentException("SiteSummaryDto source is required");
        }

        Instant effectiveNow = now != null ? now : Instant.now();

        Double dayEnergyKwh = mergeNonNegative(
                source.getDayEnergyKwh(),
                existing != null ? existing.getDayEnergyKwh() : null
        );

        Double monthEnergyKwh = mergeNonNegative(
                source.getMonthEnergyKwh(),
                existing != null ? existing.getMonthEnergyKwh() : null
        );

        Double totalEnergyKwh = mergeNonNegative(
                source.getTotalEnergyKwh(),
                existing != null ? existing.getTotalEnergyKwh() : null
        );

        Double yearEnergyKwh = mergeCurrentYearEnergy(
                brand,
                source.getYearEnergyKwh(),
                monthEnergyKwh,
                totalEnergyKwh,
                existing,
                effectiveNow
        );

        return SiteDocument.builder()
                .id(existing != null ? existing.getId() : null)
                .brand(brand)
                .providerAccountKey(existing != null ? existing.getProviderAccountKey() : null)
                .providerAccountGroup(existing != null ? existing.getProviderAccountGroup() : null)
                .siteCode(mergeString(
                        source.getSiteCode(),
                        existing != null ? existing.getSiteCode() : null
                ))
                .providerSiteId(mergeString(
                        source.getSiteCode(),
                        existing != null ? existing.getProviderSiteId() : null
                ))
                .siteName(SiteNameNormalizer.normalize(mergeString(
                        source.getSiteName(),
                        existing != null ? existing.getSiteName() : null
                )))
                .status(mergeStatus(source.getStatus(), existing))
                .statusLastSyncAt(toSiteStatus(source.getStatus()) != SiteStatus.UNKNOWN
                        ? effectiveNow : (existing != null ? existing.getStatusLastSyncAt() : null))
                .installedPowerKw(mergeNonNegative(
                        source.getInstalledPowerKw(),
                        existing != null ? existing.getInstalledPowerKw() : null
                ))
                .gridConnectionDate(normalizeGridConnectionDate(
                        brand,
                        mergeString(
                                source.getGridConnectionDate(),
                                existing != null ? existing.getGridConnectionDate() : null
                        )
                ))
                .energyLastSyncAt(existing != null ? existing.getEnergyLastSyncAt() : null)
                .monthEnergyLastSyncAt(existing != null ? existing.getMonthEnergyLastSyncAt() : null)
                .energyDate(existing != null ? existing.getEnergyDate() : null)
                .dayEnergyKwh(dayEnergyKwh)
                .monthEnergyKwh(monthEnergyKwh)
                .yearEnergyKwh(yearEnergyKwh)
                .totalEnergyKwh(totalEnergyKwh)
                .swapDate(mergeLocalDate(
                        parseIsoDate(source.getSwapDate()),
                        existing != null ? existing.getSwapDate() : null
                ))
                .yearEnergyLastSyncAt(brand == Brand.SOLIS && nonNegativeOrNull(source.getYearEnergyKwh()) != null
                        ? effectiveNow : (existing != null ? existing.getYearEnergyLastSyncAt() : null))
                .missingConfirmations(existing != null && existing.getMissingConfirmations() != null ? existing.getMissingConfirmations() : 0)
                .active(existing != null ? existing.getActive() : true)
                .missingSince(existing != null ? existing.getMissingSince() : null)
                .deletedAt(existing != null ? existing.getDeletedAt() : null)
                .lastSyncAt(effectiveNow)
                .createdAt(existing != null ? existing.getCreatedAt() : effectiveNow)
                .updatedAt(effectiveNow)
                .build();
    }

    private LocalDate parseIsoDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String trimmed = value.trim();
        List<DateTimeFormatter> dateFormatters = List.of(
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("yyyy/MM/dd"),
                DISPLAY_SWAP_DATE_FORMATTER
        );

        for (DateTimeFormatter formatter : dateFormatters) {
            try {
                return LocalDate.parse(trimmed, formatter);
            } catch (DateTimeParseException ignored) {
                // Try next format.
            }
        }

        if (trimmed.matches("\\d{10,13}")) {
            try {
                long raw = Long.parseLong(trimmed);
                long epochMillis = raw < 1_000_000_000_000L ? raw * 1000 : raw;
                return Instant.ofEpochMilli(epochMillis).atZone(TUNIS_ZONE).toLocalDate();
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        return null;
    }

    private String normalizeGridConnectionDate(Brand brand, String value) {
        if (value == null || value.isBlank() || brand != Brand.SOLIS) {
            return value;
        }

        LocalDate parsed = parseIsoDate(value);
        if (parsed == null) {
            return value;
        }

        return parsed.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
    }

    private LocalDate mergeLocalDate(LocalDate incoming, LocalDate existing) {
        return incoming != null ? incoming : existing;
    }

    private String formatIsoDate(LocalDate value) {
        return value != null ? value.toString() : null;
    }

    public SiteSummaryDto toSiteSummaryDto(SiteDocument source) {
        return new SiteSummaryDto(
                source.getBrand() != null ? source.getBrand().name() : null,
                source.getSiteCode(),
                SiteNameNormalizer.normalize(source.getSiteName()),
                toApiStatus(source.getStatus()),
                nullSafeDouble(source.getInstalledPowerKw()),
                source.getGridConnectionDate(),
                nullSafeDouble(source.getDayEnergyKwh()),
                nullSafeDouble(source.getMonthEnergyKwh()),
                normalizedYearEnergyKwh(source),
                nullSafeDouble(source.getTotalEnergyKwh()),
                formatIsoDate(source.getSwapDate())
        );
    }

    public SiteDetailDto toSiteDetailDto(SiteDocument source) {
        return new SiteDetailDto(
                source.getBrand() != null ? source.getBrand().name() : null,
                source.getSiteCode(),
                SiteNameNormalizer.normalize(source.getSiteName()),
                toApiStatus(source.getStatus()),
                nullSafeDouble(source.getInstalledPowerKw()),
                source.getGridConnectionDate(),
                nullSafeDouble(source.getDayEnergyKwh()),
                nullSafeDouble(source.getMonthEnergyKwh()),
                normalizedYearEnergyKwh(source),
                nullSafeDouble(source.getTotalEnergyKwh()),
                formatIsoDate(source.getSwapDate())
        );
    }

    public SiteDeviceDocument toSiteDeviceDocument(
            Brand brand,
            String siteCode,
            List<DeviceItemDto> devices,
            SiteDeviceDocument existing,
            Instant now
    ) {
        Instant effectiveNow = now != null ? now : Instant.now();

        return SiteDeviceDocument.builder()
                .id(existing != null ? existing.getId() : null)
                .brand(brand)
                .providerAccountKey(existing != null ? existing.getProviderAccountKey() : null)
                .providerAccountGroup(existing != null ? existing.getProviderAccountGroup() : null)
                .siteCode(siteCode)
                .devices(devices != null ? devices : List.of())
                .lastSyncAt(effectiveNow)
                .createdAt(existing != null ? existing.getCreatedAt() : effectiveNow)
                .updatedAt(effectiveNow)
                .build();
    }

    public SiteHistoryDocument toSiteHistoryDocument(
            Brand brand,
            String siteCode,
            HistoryGranularity granularity,
            String bucket,
            List<HistoryPointDto> points,
            SiteHistoryDocument existing,
            Instant now
    ) {
        Instant effectiveNow = now != null ? now : Instant.now();

        return SiteHistoryDocument.builder()
                .id(existing != null ? existing.getId() : null)
                .brand(brand)
                .providerAccountKey(existing != null ? existing.getProviderAccountKey() : null)
                .providerAccountGroup(existing != null ? existing.getProviderAccountGroup() : null)
                .siteCode(siteCode)
                .granularity(granularity)
                .bucket(bucket)
                .points(points != null ? points : List.of())
                .lastSyncAt(effectiveNow)
                .createdAt(existing != null ? existing.getCreatedAt() : effectiveNow)
                .updatedAt(effectiveNow)
                .build();
    }

    public SiteDevicesResponseDto toSiteDevicesResponseDto(SiteDeviceDocument source) {
        return new SiteDevicesResponseDto(
                source.getSiteCode(),
                source.getDevices() != null ? source.getDevices() : List.of()
        );
    }

    public DashboardSummaryDto toDashboardSummary(List<SiteDocument> sites) {
        List<SiteDocument> safeSites = sites != null ? sites : List.of();

        int totalSites = safeSites.size();

        double totalInstalledPowerKw = safeSites.stream()
                .mapToDouble(site -> nullSafeDouble(site.getInstalledPowerKw()))
                .sum();

        double totalDayEnergyKwh = safeSites.stream()
                .mapToDouble(site -> nullSafeDouble(site.getDayEnergyKwh()))
                .sum();

        double totalMonthEnergyKwh = safeSites.stream()
                .mapToDouble(site -> nullSafeDouble(site.getMonthEnergyKwh()))
                .sum();

        double totalYearEnergyKwh = safeSites.stream()
                .mapToDouble(this::normalizedYearEnergyKwh)
                .sum();

        double totalEnergyKwh = safeSites.stream()
                .mapToDouble(site -> nullSafeDouble(site.getTotalEnergyKwh()))
                .sum();

        int healthySites = (int) safeSites.stream()
                .filter(site -> site.getStatus() == SiteStatus.HEALTHY)
                .count();

        int faultySites = (int) safeSites.stream()
                .filter(site -> site.getStatus() == SiteStatus.FAULTY)
                .count();

        int disconnectedSites = (int) safeSites.stream()
                .filter(site -> site.getStatus() == SiteStatus.DISCONNECTED)
                .count();

        return new DashboardSummaryDto(
                totalSites,
                round2(totalInstalledPowerKw),
                round2(totalDayEnergyKwh),
                round2(totalMonthEnergyKwh),
                round2(totalYearEnergyKwh),
                round2(totalEnergyKwh),
                healthySites,
                faultySites,
                disconnectedSites
        );
    }

    public HistoryGranularity toGranularity(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("History type is required");
        }

        return switch (type.toLowerCase()) {
            case "day" -> HistoryGranularity.DAY;
            case "month" -> HistoryGranularity.MONTH;
            case "year" -> HistoryGranularity.YEAR;
            case "yearly" -> HistoryGranularity.YEARLY;
            default -> throw new IllegalArgumentException("Invalid type: " + type);
        };
    }

    public SiteStatus toSiteStatus(String status) {
        if (status == null || status.isBlank()) {
            return SiteStatus.UNKNOWN;
        }

        return switch (status.toLowerCase()) {
            case "healthy" -> SiteStatus.HEALTHY;
            case "faulty" -> SiteStatus.FAULTY;
            case "disconnected" -> SiteStatus.DISCONNECTED;
            default -> SiteStatus.UNKNOWN;
        };
    }

    public String toApiStatus(SiteStatus status) {
        if (status == null) {
            return "unknown";
        }

        return switch (status) {
            case HEALTHY -> "healthy";
            case FAULTY -> "faulty";
            case DISCONNECTED -> "disconnected";
            default -> "unknown";
        };
    }

    private SiteStatus mergeStatus(String incomingStatus, SiteDocument existing) {
        if (incomingStatus != null && !incomingStatus.isBlank()) {
            SiteStatus incoming = toSiteStatus(incomingStatus);

            // L'appel /thirdData/stations ne fournit pas l'état live; il arrive comme UNKNOWN.
            // On ne doit pas écraser un ancien HEALTHY/FAULTY/DISCONNECTED par UNKNOWN.
            if (incoming == SiteStatus.UNKNOWN
                    && existing != null
                    && existing.getStatus() != null
                    && existing.getStatus() != SiteStatus.UNKNOWN) {
                return existing.getStatus();
            }

            return incoming;
        }

        if (existing != null && existing.getStatus() != null) {
            return existing.getStatus();
        }

        return SiteStatus.UNKNOWN;
    }

    private Double mergeCurrentYearEnergy(
            Brand brand,
            Double incomingYearEnergyKwh,
            Double monthEnergyKwh,
            Double totalEnergyKwh,
            SiteDocument existing,
            Instant now
    ) {
        if (brand == Brand.SOLIS) {
            return mergeSolisCurrentYearEnergy(
                    incomingYearEnergyKwh,
                    monthEnergyKwh,
                    totalEnergyKwh,
                    existing,
                    now
            );
        }

        Double existingYearEnergyKwh = existing != null ? existing.getYearEnergyKwh() : null;

        Instant existingYearReference = null;
        if (existing != null) {
            existingYearReference = existing.getYearEnergyLastSyncAt() != null
                    ? existing.getYearEnergyLastSyncAt()
                    : existing.getLastSyncAt();
        }

        boolean canReuseExistingYear = existingYearEnergyKwh != null
                && existingYearReference != null
                && isSameTunisYear(existingYearReference, now);

        Double candidate = incomingYearEnergyKwh != null && incomingYearEnergyKwh >= 0
                ? incomingYearEnergyKwh
                : null;

        if (candidate == null && canReuseExistingYear) {
            candidate = existingYearEnergyKwh;
        }

        if (candidate == null) {
            candidate = 0.0;
        }

        double month = monthEnergyKwh != null ? monthEnergyKwh : 0.0;

        if (month > 0 && candidate + EPSILON < month) {
            if (canReuseExistingYear
                    && existingYearEnergyKwh != null
                    && existingYearEnergyKwh + EPSILON >= month) {
                return round2(existingYearEnergyKwh);
            }

            return round2(month);
        }

        return round2(candidate);
    }

    private Double mergeSolisCurrentYearEnergy(
            Double incomingYearEnergyKwh, Double monthEnergyKwh, Double totalEnergyKwh,
            SiteDocument existing, Instant now
    ) {
        Double incoming = nonNegativeOrNull(incomingYearEnergyKwh);
        // Only retain a year explicitly acquired by the dedicated year collector.
        Double previous = existing != null && existing.getYearEnergyLastSyncAt() != null
                && isSameTunisYear(existing.getYearEnergyLastSyncAt(), now)
                ? nonNegativeOrNull(existing.getYearEnergyKwh()) : null;
        double candidate = incoming != null ? incoming : (previous != null ? previous : 0.0);
        Double month = nonNegativeOrNull(monthEnergyKwh);
        if (month != null) candidate = Math.max(candidate, month);
        Double total = nonNegativeOrNull(totalEnergyKwh);
        return round2(total != null ? Math.min(candidate, total) : candidate);
    }

    private double normalizedYearEnergyKwh(SiteDocument source) {
        if (source == null) {
            return 0.0;
        }

        double month = nullSafeDouble(source.getMonthEnergyKwh());
        double year = nullSafeDouble(source.getYearEnergyKwh());

        if (source.getBrand() == Brand.SOLIS) {
            return mergeSolisCurrentYearEnergy(null, source.getMonthEnergyKwh(), source.getTotalEnergyKwh(), source, Instant.now());
        }

        return round2(Math.max(year, month));
    }

    private Double mergeNonNegative(Double incoming, Double existing) {
        if (incoming != null && incoming >= 0) {
            return round2(incoming);
        }

        return existing != null ? round2(existing) : 0.0;
    }

    private Double positiveOrNull(Double value) {
        if (value == null || value.isNaN() || value.isInfinite() || value <= EPSILON) {
            return null;
        }

        return value;
    }

    private Double nonNegativeOrNull(Double value) {
        if (value == null || value.isNaN() || value.isInfinite() || value < 0) {
            return null;
        }

        return value;
    }

    private Double firstNonNull(Double... values) {
        if (values == null) {
            return null;
        }

        for (Double value : values) {
            if (value != null) {
                return value;
            }
        }

        return null;
    }

    private String mergeString(String incoming, String existing) {
        if (incoming != null && !incoming.isBlank()) {
            return incoming;
        }

        return existing;
    }

    private LocalDate mergeSwapDate(String incoming, LocalDate existing) {
        LocalDate parsed = parseSwapDate(incoming);
        return parsed != null ? parsed : existing;
    }

    private LocalDate parseSwapDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String trimmed = value.trim();

        try {
            return LocalDate.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            // Try display format below.
        }

        try {
            return LocalDate.parse(trimmed, DISPLAY_SWAP_DATE_FORMATTER);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String swapDateToApi(LocalDate swapDate) {
        return swapDate != null ? swapDate.toString() : null;
    }

    private boolean isSameTunisYear(Instant left, Instant right) {
        if (left == null || right == null) {
            return false;
        }

        int leftYear = LocalDate.ofInstant(left, TUNIS_ZONE).getYear();
        int rightYear = LocalDate.ofInstant(right, TUNIS_ZONE).getYear();

        return leftYear == rightYear;
    }

    private double nullSafeDouble(Double value) {
        return value != null ? value : 0.0;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}