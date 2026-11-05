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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
     * Growatt refuse parfois une plage mensuelle complète,
     * donc on découpe le mois en petites plages.
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
        InventorySnapshot snapshot = getSitesInventory();
        if (!snapshot.failedAccountGroups().isEmpty())
            throw new IllegalStateException("Growatt inventory incomplete for accounts: " + snapshot.failedAccountGroups());
        return snapshot.sites();
    }

    /** Only fully enumerated accounts may confirm missing sites. */
    public InventorySnapshot getSitesInventory() {
        List<SiteSummaryDto> sites = new ArrayList<>();
        java.util.Set<String> completed = new java.util.HashSet<>();
        java.util.Set<String> failed = new java.util.HashSet<>();
        List<ProvidersProperties.HuaweiAccountProperties> accounts = enabledAccounts();
        if (accounts.isEmpty()) throw new IllegalStateException("No enabled Growatt source configured");
        java.util.Set<String> configured = new java.util.HashSet<>();
        for (var account : accounts) {
            if (!configured.add(safeKey(account)))
                throw new IllegalStateException("Duplicate Growatt account group: " + safeKey(account));
        }
        for (var account : accounts) {
            try {
                sites.addAll(loadAccountInventory(account));
                completed.add(safeKey(account));
            } catch (Exception e) {
                failed.add(safeKey(account));
                log.warn("Growatt inventory failed account={}: {}", safeKey(account), e.getMessage());
            }
        }
        return new InventorySnapshot(List.copyOf(sites), java.util.Set.copyOf(completed), java.util.Set.copyOf(failed));
    }

    private List<SiteSummaryDto> loadAccountInventory(ProvidersProperties.HuaweiAccountProperties account) {
        Map<String, SiteSummaryDto> sites = new LinkedHashMap<>();
        int page = 1;
        int perPage = 100;
        int receivedForAccount = 0;

        while (page <= 100) {
            JsonNode data = growattCloudClient.get(
                    account,
                    PLANT_LIST,
                    Map.of("page", page, "perpage", perPage)
            );

            com.fusionsolar.monitoring.provider.ProviderInventoryPage inventoryPage =
                    com.fusionsolar.monitoring.provider.ProviderInventoryPage.parse(data,
                            "plants", "list", "records", "rows", "datas");
            List<JsonNode> plants = inventoryPage.records();
            receivedForAccount += plants.size();
            log.info("Growatt plants loaded account={} page={} count={}", safeKey(account), page, plants.size());

            if (plants.isEmpty()) {
                inventoryPage.hasNext(receivedForAccount, perPage);
                break;
            }

            for (JsonNode plant : plants) {
                JsonNode enriched = plant;
                boolean liveDataFailed = false;
                String plantId = text(plant, "plant_id", "plantId", "id");

                if (isBlank(plantId)) throw new IllegalStateException("Growatt inventory contains invalid site identity");
                if (!isBlank(plantId)) {
                    try {
                        JsonNode realtime = growattCloudClient.get(
                                account,
                                PLANT_DATA,
                                Map.of("plant_id", plantId)
                        );
                        enriched = merge(plant.deepCopy(), realtime);
                    } catch (Exception e) {
                        liveDataFailed = true;
                        log.warn(
                                "Growatt /plant/data skipped account={} plantId={} cause={}",
                                safeKey(account),
                                plantId,
                                e.getMessage()
                        );
                    }
                }

                SiteSummaryDto site = toSiteSummary(account, enriched);
                // Inventory identity must not be overwritten by realtime payload fields.
                site.setSiteCode("GROWATT_" + safeKey(account) + "_" + plantId);
                if (liveDataFailed) site.setStatus("unknown");

                if (site.getSiteCode() != null && !site.getSiteCode().isBlank()) {
                    if (sites.putIfAbsent(site.getSiteCode(), site) != null)
                        throw new IllegalStateException("Growatt repeated site in paginated inventory");
                } else {
                    throw new IllegalStateException("Growatt inventory contains invalid site identity");
                }
            }

            if (!inventoryPage.hasNext(receivedForAccount, perPage)) {
                break;
            }

            if (page == 100) throw new IllegalStateException("Growatt inventory pagination limit exceeded");
            page++;
        }
        return new ArrayList<>(sites.values());
    }

    public record InventorySnapshot(List<SiteSummaryDto> sites,
                                    java.util.Set<String> completedAccountGroups,
                                    java.util.Set<String> failedAccountGroups) {}

    public String inventoryAccountGroup(String siteCode) {
        return safeKey(parseSiteCode(siteCode).account());
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

        /*
         * DAY = courbe horaire de puissance kW.
         * Source: /v1/plant/power
         */
        if ("day".equals(range.requestType())) {
            return getDayPowerHistory(ref, range.start());
        }

        /*
         * MONTH = courbe énergie kWh par jour.
         * Source: /v1/plant/energy, découpé en chunks.
         */
        if ("month".equals(range.requestType())) {
            return getMonthEnergyHistory(ref, range.start(), range.end());
        }

        /*
         * YEAR / YEARLY = énergie kWh par mois/année.
         */
        return getEnergyHistory(ref, range);
    }

    private List<HistoryPointDto> getDayPowerHistory(AccountAndPlant ref, LocalDate date) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("plant_id", ref.plantId());
        params.put("date", date.toString());
        params.put("timezone_id", 1);

        JsonNode data = growattCloudClient.get(ref.account(), PLANT_POWER, params);

        List<JsonNode> records = collectArrayFlexible(
                data,
                "powers",
                "power",
                "data",
                "list",
                "records",
                "rows"
        );

        log.info(
                "Growatt day power loaded account={} plant={} date={} records={}",
                safeKey(ref.account()),
                ref.plantId(),
                date,
                records.size()
        );

        if (log.isDebugEnabled() && !records.isEmpty()) {
            log.debug("Growatt day power sample account={} plant={} date={} sample={}",
                    safeKey(ref.account()), ref.plantId(), date, oneLineNode(records.get(0)));
        }

        List<HistoryPointDto> points = new ArrayList<>();

        for (JsonNode power : records) {
            String time = text(
                    power,
                    "time",
                    "date",
                    "data_time",
                    "dataTime",
                    "timestamp",
                    "collectTime",
                    "timeStr",
                    "xAxis",
                    "label"
            );

            Double valueKw = firstPowerKw(
                    power,
                    "power",
                    "pac",
                    "p",
                    "current_power",
                    "currentPower",
                    "value"
            );

            Long collectTime = toEpochMillis(time, "day", date);

            if (collectTime != null && valueKw != null && valueKw >= 0) {
                points.add(new HistoryPointDto(collectTime, round2(valueKw)));
            }
        }

        return deduplicateAndSort(points);
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

        List<JsonNode> records = new ArrayList<>();
        String previousPage = null;
        for (int page = 1; page <= 1000; page++) {
            params.put("page", page);
            JsonNode data = growattCloudClient.get(ref.account(), PLANT_ENERGY, params);
            List<JsonNode> items = collectArrayFlexible(data, "energys", "energies", "energy", "list", "records", "rows");
            if (items.isEmpty()) break;
            String fingerprint = items.toString();
            if (fingerprint.equals(previousPage))
                throw new IllegalStateException("Growatt repeated history page; pagination not supported for this response");
            previousPage = fingerprint;
            records.addAll(items);
            int total = data.path("count").asInt(-1);
            if (total < 0) total = data.path("total").asInt(-1);
            if (total >= 0 && records.size() >= total) break;
            if (total < 0 && items.size() < Math.max(1, range.perPage())) break;
            if (page == 1000) throw new IllegalStateException("Growatt history pagination limit exceeded");
        }

        log.debug(
                "Growatt energy loaded account={} plant={} type={} start={} end={} records={}",
                safeKey(ref.account()),
                ref.plantId(),
                range.requestType(),
                range.start(),
                range.end(),
                records.size()
        );

        List<HistoryPointDto> points = new ArrayList<>();

        for (JsonNode energy : records) {
            String date = text(
                    energy,
                    "date",
                    "time",
                    "day",
                    "month",
                    "year",
                    "data_time",
                    "dataTime",
                    "timestamp"
            );

            Double valueKwh = firstEnergyKwh(
                    energy,
                    "energy",
                    "value",
                    "generation",
                    "kwh",
                    "eToday",
                    "eMonth",
                    "eYear",
                    "today_energy",
                    "month_energy",
                    "year_energy"
            );

            Long collectTime = toEpochMillis(date, range.timeUnit(), null);

            if (collectTime != null && valueKwh != null && valueKwh >= 0) {
                points.add(new HistoryPointDto(collectTime, round2(valueKwh)));
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

        for (JsonNode node : collectArrayFlexible(data, "devices", "list", "records", "rows")) {
            DeviceItemDto device = new DeviceItemDto();

            device.setId(text(node, "device_id", "deviceId", "device_sn", "sn"));
            device.setDevDn(text(node, "device_sn", "sn", "device_id", "deviceId"));
            device.setDevName(text(node, "device_name", "deviceName", "device_sn", "alias", "model"));
            device.setStationCode(siteCode);
            device.setEsnCode(text(node, "device_sn", "sn"));
            device.setDevTypeId(integer(node, "type", "device_type", "deviceType"));
            device.setModel(text(node, "model", "manufacturer"));
            device.setInvType(text(node, "manufacturer", "type", "device_type"));

            devices.add(device);
        }

        return new SiteDevicesResponseDto(siteCode, devices);
    }

    public AccountAndPlant parseSiteCode(String siteCode) {
        if (siteCode == null || !siteCode.startsWith("GROWATT_")) {
            throw new IllegalArgumentException("Invalid Growatt site code: " + siteCode);
        }

        for (ProvidersProperties.HuaweiAccountProperties account : enabledAccounts().stream()
                .sorted(Comparator.comparingInt((ProvidersProperties.HuaweiAccountProperties a) -> safeKey(a).length()).reversed()).toList()) {
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
                nonNegative(firstPowerKw(node, "peak_power", "capacity", "installedPowerKw")),
                text(node, "create_date", "gridConnectionDate", "createDate"),
                nonNegative(firstEnergyKwh(node, "today_energy", "todayEnergy", "dayEnergyKwh")),
                nonNegative(firstEnergyKwh(node, "month_energy", "monthly_energy", "monthEnergyKwh")),
                nonNegative(firstEnergyKwh(node, "year_energy", "yearly_energy", "yearEnergyKwh")),
                nonNegative(firstEnergyKwh(node, "total_energy", "totalEnergy", "totalEnergyKwh")),
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

    private Long toEpochMillis(String value, String timeUnit, LocalDate fallbackDate) {
        if (isBlank(value)) {
            return null;
        }

        String raw = value.trim();

        Long numericEpoch = parseEpochMillis(raw);
        if (numericEpoch != null) {
            return numericEpoch;
        }

        try {
            if (raw.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                return LocalDate.parse(raw)
                        .atStartOfDay(TUNIS_ZONE)
                        .toInstant()
                        .toEpochMilli();
            }

            if (raw.matches("^\\d{4}/\\d{2}/\\d{2}$")) {
                return LocalDate.parse(raw, DateTimeFormatter.ofPattern("yyyy/MM/dd"))
                        .atStartOfDay(TUNIS_ZONE)
                        .toInstant()
                        .toEpochMilli();
            }

            if (raw.matches("^\\d{4}-\\d{2}$")) {
                return YearMonth.parse(raw)
                        .atDay(1)
                        .atStartOfDay(TUNIS_ZONE)
                        .toInstant()
                        .toEpochMilli();
            }

            if (raw.matches("^\\d{4}$")) {
                return LocalDate.of(Integer.parseInt(raw), 1, 1)
                        .atStartOfDay(TUNIS_ZONE)
                        .toInstant()
                        .toEpochMilli();
            }

            LocalTime localTime = parseTimeOfDay(raw);
            if (localTime != null && fallbackDate != null) {
                return fallbackDate
                        .atTime(localTime)
                        .atZone(TUNIS_ZONE)
                        .toInstant()
                        .toEpochMilli();
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
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME
            );

            for (DateTimeFormatter formatter : formatters) {
                try {
                    return LocalDateTime.parse(raw, formatter)
                            .atZone(TUNIS_ZONE)
                            .toInstant()
                            .toEpochMilli();
                } catch (DateTimeParseException ignored) {
                    // Continue.
                }
            }

            if ("day".equals(timeUnit) && raw.matches("^\\d{1,2}$") && fallbackDate != null) {
                int hour = Math.max(0, Math.min(23, Integer.parseInt(raw)));
                return fallbackDate
                        .atTime(hour, 0)
                        .atZone(TUNIS_ZONE)
                        .toInstant()
                        .toEpochMilli();
            }

        } catch (Exception ignored) {
            // Return null below.
        }

        return null;
    }

    private Long parseEpochMillis(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String trimmed = raw.trim();

        if (!trimmed.matches("^-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?$")) {
            return null;
        }

        try {
            long epoch = new BigDecimal(trimmed).longValue();
            long abs = Math.abs(epoch);

            if (abs < 1_000_000_000L) {
                return null;
            }

            if (abs >= 10_000_000_000_000_000L) {
                return epoch / 1_000_000L;
            }

            if (abs >= 100_000_000_000_000L) {
                return epoch / 1_000L;
            }

            if (abs < 10_000_000_000L) {
                return epoch * 1_000L;
            }

            return epoch;
        } catch (NumberFormatException | ArithmeticException ignored) {
            return null;
        }
    }

    private LocalTime parseTimeOfDay(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String text = raw.trim();

        List<DateTimeFormatter> formatters = List.of(
                DateTimeFormatter.ofPattern("H:mm:ss"),
                DateTimeFormatter.ofPattern("HH:mm:ss"),
                DateTimeFormatter.ofPattern("H:mm"),
                DateTimeFormatter.ofPattern("HH:mm")
        );

        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalTime.parse(text, formatter);
            } catch (DateTimeParseException ignored) {
                // Continue.
            }
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
        List<JsonNode> result = new ArrayList<>();

        if (parent == null || parent.isMissingNode() || parent.isNull()) {
            return result;
        }

        if (parent.isArray()) {
            return collectArray(parent);
        }

        collectNamedArrays(result, parent, names);

        JsonNode data = parent.path("data");
        if (!data.isMissingNode() && !data.isNull()) {
            if (data.isArray()) {
                result.addAll(collectArray(data));
            } else if (data.isObject()) {
                collectNamedArrays(result, data, names);
            }
        }

        JsonNode page = parent.path("page");
        if (!page.isMissingNode() && !page.isNull() && page.isObject()) {
            collectNamedArrays(result, page, names);
        }

        if (result.isEmpty()) {
            JsonNode datas = parent.path("datas");
            if (datas.isArray()) {
                result.addAll(collectArray(datas));
            }
        }

        if (result.isEmpty()) {
            JsonNode rows = parent.path("rows");
            if (rows.isArray()) {
                result.addAll(collectArray(rows));
            }
        }

        return result;
    }

    private void collectNamedArrays(List<JsonNode> result, JsonNode parent, String... names) {
        if (parent == null || !parent.isObject()) {
            return;
        }

        for (String name : names) {
            JsonNode candidate = parent.path(name);

            if (candidate != null && candidate.isArray()) {
                candidate.forEach(result::add);
            }
        }
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

    private Double firstEnergyKwh(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode valueNode = node.path(name);

            if (valueNode.isMissingNode() || valueNode.isNull()) {
                continue;
            }

            String rawText = valueNode.asText(null);
            Double value = valueNode.isNumber() ? valueNode.asDouble() : parseNumber(rawText);

            if (value == null) {
                continue;
            }

            String unit = text(
                    node,
                    name + "_unit",
                    name + "Unit",
                    name + "unit",
                    name + "_str",
                    name + "Str",
                    "unit",
                    "energyUnit",
                    "energy_unit",
                    "dataUnit"
            );

            return round2(toKwh(value, unit != null ? unit : rawText));
        }

        return null;
    }

    private Double firstPowerKw(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode valueNode = node.path(name);

            if (valueNode.isMissingNode() || valueNode.isNull()) {
                continue;
            }

            String rawText = valueNode.asText(null);
            Double value = valueNode.isNumber() ? valueNode.asDouble() : parseNumber(rawText);

            if (value == null) {
                continue;
            }

            String unit = text(
                    node,
                    name + "_unit",
                    name + "Unit",
                    name + "unit",
                    name + "_str",
                    name + "Str",
                    "unit",
                    "powerUnit",
                    "power_unit",
                    "dataUnit"
            );

            return round2(toKw(value, unit != null ? unit : rawText));
        }

        return null;
    }

    private Double number(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);

            if (!value.isMissingNode() && !value.isNull()) {
                if (value.isNumber()) {
                    return value.asDouble();
                }

                Double parsed = parseNumber(value.asText(null));

                if (parsed != null) {
                    return parsed;
                }
            }
        }

        return null;
    }

    private Integer integer(JsonNode node, String... names) {
        Double value = number(node, names);
        return value == null ? null : value.intValue();
    }

    private Double parseNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String text = raw.trim()
                .replace("\u00A0", "")
                .replace(" ", "");

        if (text.contains(",") && text.contains(".")) {
            text = text.replace(",", "");
        } else if (text.contains(",")) {
            text = text.replace(",", ".");
        }

        text = text.replaceAll("[^0-9.\\-]", "");

        if (text.isBlank() || "-".equals(text) || ".".equals(text)) {
            return null;
        }

        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double toKwh(Double value, String unitText) {
        if (value == null) {
            return null;
        }

        if (unitText == null || unitText.isBlank()) {
            return value;
        }

        String normalized = unitText.trim()
                .toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("·", "")
                .replace("/", "");

        if (normalized.contains("gwh")) {
            return value * 1_000_000.0;
        }

        if (normalized.contains("mwh")) {
            return value * 1_000.0;
        }

        if (normalized.contains("kwh") || normalized.contains("kw·h")) {
            return value;
        }

        if (normalized.contains("wh") && !normalized.contains("kwh") && !normalized.contains("mwh")) {
            return value / 1_000.0;
        }

        return value;
    }

    private Double toKw(Double value, String unitText) {
        if (value == null) {
            return null;
        }

        if (unitText == null || unitText.isBlank()) {
            return value;
        }

        String normalized = unitText.trim()
                .toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("·", "")
                .replace("/", "");

        if (normalized.contains("mw")) {
            return value * 1_000.0;
        }

        if (normalized.contains("kw")) {
            return value;
        }

        if (normalized.contains("w") && !normalized.contains("kw") && !normalized.contains("mw")) {
            return value / 1_000.0;
        }

        return value;
    }

    private Double nonNegative(Double value) {
        return value == null || value.isNaN() || value.isInfinite() || value < 0
                ? null
                : round2(value);
    }

    private Double round2(Double value) {
        return value == null ? null : Math.round(value * 100.0) / 100.0;
    }

    private String oneLineNode(JsonNode node) {
        if (node == null) {
            return "";
        }

        String text = node.toString()
                .replace("\r", " ")
                .replace("\n", " ")
                .replace("\t", " ")
                .trim();

        if (text.length() > 1200) {
            return text.substring(0, 1200) + "...";
        }

        return text;
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
