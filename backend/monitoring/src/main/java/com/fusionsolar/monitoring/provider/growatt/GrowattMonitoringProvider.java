package com.fusionsolar.monitoring.provider.growatt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fusionsolar.monitoring.client.growatt.GrowattCloudClient;
import com.fusionsolar.monitoring.config.ProvidersProperties;
import com.fusionsolar.monitoring.dto.DeviceItemDto;
import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.dto.SiteDetailDto;
import com.fusionsolar.monitoring.dto.SiteDevicesResponseDto;
import com.fusionsolar.monitoring.dto.SiteSummaryDto;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.provider.MonitoringProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
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
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "providers.growatt", name = "enabled", havingValue = "true")
public class GrowattMonitoringProvider implements MonitoringProvider {

    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");

    private static final String PLANT_LIST = "/v1/plant/list";
    private static final String PLANT_DETAILS = "/v1/plant/details";
    private static final String PLANT_DATA = "/v1/plant/data";
    private static final String PLANT_ENERGY = "/v1/plant/energy";
    private static final String PLANT_POWER = "/v1/plant/power";
    private static final String DEVICE_LIST = "/v1/device/list";

    /*
     * Growatt refuse parfois une plage mensuelle complète, par exemple:
     * 2026-05-01 -> 2026-05-31 avec time_unit=day.
     * On découpe donc les mois en petites plages pour éviter error_time format is incorrect.
     */
    private static final int MONTH_ENERGY_CHUNK_DAYS = 7;

    private final ProvidersProperties providersProperties;
    private final GrowattCloudClient growattCloudClient;

    @Override
    public Brand getBrand() {
        return Brand.GROWATT;
    }

    @Override
    public List<SiteSummaryDto> getSitesSummary() {
        Map<String, SiteSummaryDto> sites = new LinkedHashMap<>();
        List<ProvidersProperties.HuaweiAccountProperties> accounts = enabledAccounts();

        log.info("Growatt sites summary sync started accounts={}", accounts.size());

        for (ProvidersProperties.HuaweiAccountProperties account : accounts) {
            int page = 1;
            int perPage = 100;

            while (page <= 100) {
                JsonNode data = growattCloudClient.get(
                        account,
                        PLANT_LIST,
                        Map.of("page", page, "perpage", perPage)
                );

                List<JsonNode> plants = collectArray(data.path("plants"));
                log.info("Growatt plants loaded account={} page={} count={}", safeKey(account), page, plants.size());

                if (plants.isEmpty()) {
                    break;
                }

                for (JsonNode plant : plants) {
                    JsonNode enriched = plant;
                    String plantId = text(plant, "plant_id", "plantId", "id");

                    if (!isBlank(plantId)) {
                        try {
                            JsonNode realtime = growattCloudClient.get(
                                    account,
                                    PLANT_DATA,
                                    Map.of("plant_id", plantId)
                            );
                            enriched = merge(plant.deepCopy(), realtime);
                        } catch (Exception e) {
                            log.debug(
                                    "Growatt /plant/data skipped account={} plantId={} cause={}",
                                    safeKey(account),
                                    plantId,
                                    e.getMessage()
                            );
                        }
                    }

                    SiteSummaryDto site = toSiteSummary(account, enriched);

                    if (site.getSiteCode() != null && !site.getSiteCode().isBlank()) {
                        sites.put(site.getSiteCode(), site);
                    }
                }

                int count = data.path("count").asInt(plants.size());

                if (plants.size() < perPage || page * perPage >= count) {
                    break;
                }

                page++;
            }
        }

        log.info("Growatt sites summary sync finished sites={}", sites.size());
        return new ArrayList<>(sites.values());
    }

    @Override
    public SiteDetailDto getSiteDetail(String siteCode) {
        AccountAndPlant ref = parseSiteCode(siteCode);

        JsonNode detail = growattCloudClient.get(
                ref.account(),
                PLANT_DETAILS,
                Map.of("plant_id", ref.plantId())
        );

        JsonNode data = growattCloudClient.get(
                ref.account(),
                PLANT_DATA,
                Map.of("plant_id", ref.plantId())
        );

        SiteSummaryDto summary = toSiteSummary(ref.account(), merge(detail, data));
        summary.setSiteCode(siteCode);

        return toDetail(summary);
    }

    @Override
    public List<HistoryPointDto> getHistory(String siteCode, String type, String value) {
        AccountAndPlant ref = parseSiteCode(siteCode);
        Range range = rangeFor(type, value);

        if ("day".equals(range.requestType())) {
            return getDayPowerHistory(ref, range.start());
        }

        /*
         * Correction principale:
         * Pour MONTH, on ne fait plus un seul appel 2026-05-01 -> 2026-05-31.
         * On découpe en chunks de 7 jours.
         */
        if ("month".equals(range.requestType())) {
            return getMonthEnergyHistory(ref, range.start(), range.end());
        }

        return getEnergyHistory(ref, range);
    }

    private List<HistoryPointDto> getMonthEnergyHistory(AccountAndPlant ref, LocalDate start, LocalDate end) {
        List<HistoryPointDto> points = new ArrayList<>();

        if (start == null || end == null || start.isAfter(end)) {
            return points;
        }

        LocalDate cursor = start;

        while (!cursor.isAfter(end)) {
            LocalDate chunkEnd = cursor.plusDays(MONTH_ENERGY_CHUNK_DAYS - 1L);

            if (chunkEnd.isAfter(end)) {
                chunkEnd = end;
            }

            Range chunkRange = new Range(
                    cursor,
                    chunkEnd,
                    "day",
                    Math.max(1, (int) java.time.temporal.ChronoUnit.DAYS.between(cursor, chunkEnd) + 1),
                    "month"
            );

            points.addAll(getEnergyHistory(ref, chunkRange));

            cursor = chunkEnd.plusDays(1);
        }

        return deduplicateAndSort(points);
    }

    private List<HistoryPointDto> getEnergyHistory(AccountAndPlant ref, Range range) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("plant_id", ref.plantId());
        params.put("start_date", range.start().toString());
        params.put("end_date", range.end().toString());
        params.put("time_unit", range.timeUnit());
        params.put("page", 1);
        params.put("perpage", Math.max(1, range.perPage()));

        JsonNode data = growattCloudClient.get(ref.account(), PLANT_ENERGY, params);

        List<HistoryPointDto> points = new ArrayList<>();

        for (JsonNode energy : collectArrayFlexible(data, "energys", "energies", "energy")) {
            String date = text(energy, "date", "time", "day", "month", "year");
            Double valueKwh = number(
                    energy,
                    "energy",
                    "value",
                    "generation",
                    "kwh",
                    "eToday",
                    "eMonth",
                    "eYear"
            );

            Long collectTime = toEpochMillis(date, range.timeUnit());

            if (collectTime != null && valueKwh != null && valueKwh >= 0) {
                points.add(new HistoryPointDto(collectTime, round2(valueKwh)));
            }
        }

        return deduplicateAndSort(points);
    }

    private List<HistoryPointDto> getDayPowerHistory(AccountAndPlant ref, LocalDate date) {
        JsonNode data = growattCloudClient.get(
                ref.account(),
                PLANT_POWER,
                Map.of(
                        "plant_id", ref.plantId(),
                        "date", date.toString(),
                        "timezone_id", 1
                )
        );

        List<HistoryPointDto> points = new ArrayList<>();

        for (JsonNode power : collectArrayFlexible(data, "powers", "power")) {
            String time = text(power, "time", "date", "data_time", "timestamp");
            Double value = number(power, "power", "value", "pac", "p");

            Long collectTime = toEpochMillis(time, "day");

            if (collectTime != null && value != null && value >= 0) {
                points.add(new HistoryPointDto(collectTime, round2(value)));
            }
        }

        return deduplicateAndSort(points);
    }

    @Override
    public List<HistoryPointDto> getYearlyHistory(String siteCode) {
        AccountAndPlant ref = parseSiteCode(siteCode);

        int currentYear = Year.now(TUNIS_ZONE).getValue();
        int firstYear = currentYear - 9;
        LocalDate today = LocalDate.now(TUNIS_ZONE);

        Range range = new Range(
                LocalDate.of(firstYear, 1, 1),
                today,
                "year",
                20,
                "yearly"
        );

        return getEnergyHistory(ref, range);
    }

    @Override
    public SiteDevicesResponseDto getSiteDevices(String siteCode) {
        AccountAndPlant ref = parseSiteCode(siteCode);

        JsonNode data = growattCloudClient.get(
                ref.account(),
                DEVICE_LIST,
                Map.of(
                        "plant_id", ref.plantId(),
                        "page", 1,
                        "perpage", 100
                )
        );

        List<DeviceItemDto> devices = new ArrayList<>();

        for (JsonNode node : collectArray(data.path("devices"))) {
            DeviceItemDto device = new DeviceItemDto();

            device.setId(text(node, "device_id", "device_sn", "sn"));
            device.setDevDn(text(node, "device_sn", "sn", "device_id"));
            device.setDevName(text(node, "device_sn", "alias", "model"));
            device.setStationCode(siteCode);
            device.setEsnCode(text(node, "device_sn", "sn"));
            device.setDevTypeId(integer(node, "type", "device_type"));
            device.setModel(text(node, "model", "manufacturer"));
            device.setInvType(text(node, "manufacturer", "type"));

            devices.add(device);
        }

        return new SiteDevicesResponseDto(siteCode, devices);
    }

    public AccountAndPlant parseSiteCode(String siteCode) {
        if (siteCode == null || !siteCode.startsWith("GROWATT_")) {
            throw new IllegalArgumentException("Invalid Growatt site code: " + siteCode);
        }

        for (ProvidersProperties.HuaweiAccountProperties account : enabledAccounts()) {
            String prefix = "GROWATT_" + safeKey(account) + "_";

            if (siteCode.startsWith(prefix)) {
                String plantId = siteCode.substring(prefix.length());
                return new AccountAndPlant(account, plantId);
            }
        }

        throw new IllegalArgumentException("No Growatt account found for site code: " + siteCode);
    }

    public String accountKeyFromSiteCode(String siteCode) {
        return parseSiteCode(siteCode).account().getKey();
    }

    public String accountGroupFromSiteCode(String siteCode) {
        ProvidersProperties.HuaweiAccountProperties account = parseSiteCode(siteCode).account();
        return !isBlank(account.getGroup()) ? account.getGroup().trim() : safeKey(account);
    }

    private SiteSummaryDto toSiteSummary(ProvidersProperties.HuaweiAccountProperties account, JsonNode node) {
        String plantId = text(node, "plant_id", "plantId", "id");
        String siteCode = !isBlank(plantId) ? "GROWATT_" + safeKey(account) + "_" + plantId : null;

        return new SiteSummaryDto(
                Brand.GROWATT.name(),
                siteCode,
                text(node, "name", "plant_name", "plantName"),
                toStatus(text(node, "status")),
                nonNegative(number(node, "peak_power", "capacity", "installedPowerKw")),
                text(node, "create_date", "gridConnectionDate", "createDate"),
                nonNegative(number(node, "today_energy", "todayEnergy", "dayEnergyKwh")),
                nonNegative(number(node, "month_energy", "monthly_energy", "monthEnergyKwh")),
                nonNegative(number(node, "year_energy", "yearly_energy", "yearEnergyKwh")),
                nonNegative(number(node, "total_energy", "totalEnergy", "totalEnergyKwh")),
                null
        );
    }

    private SiteDetailDto toDetail(SiteSummaryDto site) {
        return new SiteDetailDto(
                Brand.GROWATT.name(),
                site.getSiteCode(),
                site.getSiteName(),
                site.getStatus(),
                site.getInstalledPowerKw(),
                site.getGridConnectionDate(),
                site.getDayEnergyKwh(),
                site.getMonthEnergyKwh(),
                site.getYearEnergyKwh(),
                site.getTotalEnergyKwh(),
                site.getSwapDate()
        );
    }

    private JsonNode merge(JsonNode left, JsonNode right) {
        if (left == null || left.isMissingNode() || left.isNull()) {
            return right;
        }

        if (right == null || right.isMissingNode() || right.isNull()) {
            return left;
        }

        if (left instanceof com.fasterxml.jackson.databind.node.ObjectNode l && right.isObject()) {
            right.fields().forEachRemaining(e -> l.set(e.getKey(), e.getValue()));
            return l;
        }

        return left;
    }

    private Range rangeFor(String type, String value) {
        String normalized = type == null || type.isBlank()
                ? "month"
                : type.toLowerCase(Locale.ROOT);

        String bucket = value == null || value.isBlank()
                ? defaultBucket(normalized)
                : value.trim();

        LocalDate today = LocalDate.now(TUNIS_ZONE);

        return switch (normalized) {
            case "day" -> {
                LocalDate day = LocalDate.parse(bucket);

                if (day.isAfter(today)) {
                    day = today;
                }

                yield new Range(day, day, "day", 1, "day");
            }

            case "month" -> {
                YearMonth ym = YearMonth.parse(bucket);
                LocalDate start = ym.atDay(1);
                LocalDate end = ym.atEndOfMonth();

                if (end.isAfter(today)) {
                    end = today;
                }

                if (start.isAfter(today)) {
                    start = today;
                    end = today;
                }

                yield new Range(
                        start,
                        end,
                        "day",
                        Math.max(1, (int) java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1),
                        "month"
                );
            }

            case "year" -> {
                int year = Integer.parseInt(bucket);
                LocalDate start = LocalDate.of(year, 1, 1);
                LocalDate end = LocalDate.of(year, 12, 31);

                if (end.isAfter(today)) {
                    end = today;
                }

                if (start.isAfter(today)) {
                    start = today;
                    end = today;
                }

                yield new Range(start, end, "month", 12, "year");
            }

            case "yearly" -> {
                int currentYear = today.getYear();

                if ("all".equalsIgnoreCase(bucket)) {
                    LocalDate start = LocalDate.of(currentYear - 9, 1, 1);
                    yield new Range(start, today, "year", 20, "yearly");
                }

                int year = Integer.parseInt(bucket);
                LocalDate start = LocalDate.of(year, 1, 1);
                LocalDate end = LocalDate.of(year, 12, 31);

                if (end.isAfter(today)) {
                    end = today;
                }

                if (start.isAfter(today)) {
                    start = today;
                    end = today;
                }

                yield new Range(start, end, "year", 1, "yearly");
            }

            default -> throw new IllegalArgumentException("Invalid Growatt history type: " + type);
        };
    }

    private String defaultBucket(String type) {
        return switch (type) {
            case "day" -> LocalDate.now(TUNIS_ZONE).toString();
            case "year", "yearly" -> String.valueOf(LocalDate.now(TUNIS_ZONE).getYear());
            default -> YearMonth.now(TUNIS_ZONE).toString();
        };
    }

    private List<HistoryPointDto> deduplicateAndSort(List<HistoryPointDto> points) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }

        Map<Long, HistoryPointDto> byTime = new LinkedHashMap<>();

        points.stream()
                .filter(point -> point != null && point.getCollectTime() != null)
                .sorted(Comparator.comparing(HistoryPointDto::getCollectTime))
                .forEach(point -> byTime.put(point.getCollectTime(), point));

        return new ArrayList<>(byTime.values());
    }

    private Long toEpochMillis(String value, String timeUnit) {
        if (isBlank(value)) {
            return null;
        }

        String raw = value.trim();

        try {
            if (raw.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                return LocalDate.parse(raw).atStartOfDay(TUNIS_ZONE).toInstant().toEpochMilli();
            }

            if (raw.matches("^\\d{4}-\\d{2}$")) {
                return YearMonth.parse(raw).atDay(1).atStartOfDay(TUNIS_ZONE).toInstant().toEpochMilli();
            }

            if (raw.matches("^\\d{4}$")) {
                return LocalDate.of(Integer.parseInt(raw), 1, 1).atStartOfDay(TUNIS_ZONE).toInstant().toEpochMilli();
            }

            try {
                return Instant.parse(raw).toEpochMilli();
            } catch (DateTimeParseException ignored) {
                // Try offset date-time below.
            }

            try {
                return java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
                // Try local date-time formats below.
            }

            List<DateTimeFormatter> formatters = List.of(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME
            );

            for (DateTimeFormatter formatter : formatters) {
                try {
                    return LocalDateTime.parse(raw, formatter).atZone(TUNIS_ZONE).toInstant().toEpochMilli();
                } catch (DateTimeParseException ignored) {
                    // Continue.
                }
            }
        } catch (Exception ignored) {
            // Return null below.
        }

        return null;
    }

    private String toStatus(String raw) {
        if (isBlank(raw)) {
            return "unknown";
        }

        String s = raw.trim().toLowerCase(Locale.ROOT);

        return switch (s) {
            case "1", "normal", "online", "healthy", "running" -> "healthy";
            case "2", "fault", "faulty", "alarm", "warning", "error" -> "faulty";
            case "3", "4", "offline", "lost", "disconnected", "disconnect" -> "disconnected";
            default -> "unknown";
        };
    }

    private List<ProvidersProperties.HuaweiAccountProperties> enabledAccounts() {
        ProvidersProperties.ProviderProperties growatt = providersProperties.getGrowatt();

        if (growatt == null || growatt.getAccounts() == null) {
            return List.of();
        }

        return growatt.getAccounts().stream()
                .filter(account -> account != null && !Boolean.FALSE.equals(account.getEnabled()))
                .filter(account -> !isBlank(account.getToken()) || !isBlank(growatt.getToken()))
                .toList();
    }

    private String safeKey(ProvidersProperties.HuaweiAccountProperties account) {
        String key = Optional.ofNullable(account.getGroup())
                .filter(v -> !v.isBlank())
                .orElse(account.getKey());

        if (isBlank(key)) {
            key = "primary";
        }

        return key.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    private List<JsonNode> collectArray(JsonNode candidate) {
        List<JsonNode> nodes = new ArrayList<>();

        if (candidate != null && candidate.isArray()) {
            candidate.forEach(nodes::add);
        }

        return nodes;
    }

    private List<JsonNode> collectArrayFlexible(JsonNode parent, String... names) {
        if (parent == null || parent.isMissingNode() || parent.isNull()) {
            return List.of();
        }

        if (parent.isArray()) {
            return collectArray(parent);
        }

        for (String name : names) {
            JsonNode candidate = parent.path(name);

            if (candidate != null && candidate.isArray()) {
                return collectArray(candidate);
            }
        }

        return List.of();
    }

    private String text(JsonNode node, String... names) {
        if (node == null) {
            return null;
        }

        for (String name : names) {
            JsonNode value = node.path(name);

            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText(null);

                if (!isBlank(text) && !"null".equalsIgnoreCase(text)) {
                    return text.trim();
                }
            }
        }

        return null;
    }

    private Double number(JsonNode node, String... names) {
        String value = text(node, names);

        if (isBlank(value)) {
            return null;
        }

        try {
            return Double.parseDouble(value.replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer integer(JsonNode node, String... names) {
        Double value = number(node, names);
        return value == null ? null : value.intValue();
    }

    private Double nonNegative(Double value) {
        return value == null || value.isNaN() || value.isInfinite() || value < 0
                ? null
                : round2(value);
    }

    private Double round2(Double value) {
        return value == null ? null : Math.round(value * 100.0) / 100.0;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record AccountAndPlant(
            ProvidersProperties.HuaweiAccountProperties account,
            String plantId
    ) {
    }

    private record Range(
            LocalDate start,
            LocalDate end,
            String timeUnit,
            int perPage,
            String requestType
    ) {
    }
}