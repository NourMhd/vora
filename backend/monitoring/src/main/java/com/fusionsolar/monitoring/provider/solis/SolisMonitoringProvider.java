package com.fusionsolar.monitoring.provider.solis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fusionsolar.monitoring.client.solis.SolisCloudClient;
import com.fusionsolar.monitoring.dto.DeviceItemDto;
import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.dto.SiteDetailDto;
import com.fusionsolar.monitoring.dto.SiteDevicesResponseDto;
import com.fusionsolar.monitoring.dto.SiteSummaryDto;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.provider.MonitoringProvider;
import lombok.RequiredArgsConstructor;
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
import java.util.Objects;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "providers.solis", name = "enabled", havingValue = "true")
public class SolisMonitoringProvider implements MonitoringProvider {

    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");
    private static final DateTimeFormatter SOLIS_DISPLAY_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private static final String STATION_LIST = "/v1/api/userStationList";
    private static final String STATION_DETAIL = "/v1/api/stationDetail";
    private static final String STATION_DAY_ENERGY = "/v1/api/stationDayEnergyList";
    private static final String STATION_MONTH_ENERGY = "/v1/api/stationMonthEnergyList";
    private static final String STATION_YEAR_ENERGY = "/v1/api/stationYearEnergyList";
    private static final String INVERTER_LIST = "/v1/api/inverterList";

    private final SolisCloudClient solisCloudClient;

    @Override
    public Brand getBrand() {
        return Brand.SOLIS;
    }

    @Override
    public List<SiteSummaryDto> getSitesSummary() {
        Map<String, SiteSummaryDto> sitesByCode = new LinkedHashMap<>();
        int pageNo = 1;
        int pageSize = 100;

        while (pageNo <= 50) {
            JsonNode data = solisCloudClient.post(STATION_LIST, Map.of(
                    "pageNo", pageNo,
                    "pageSize", pageSize
            ));

            List<JsonNode> records = extractRecords(data);

            for (JsonNode record : records) {
                SiteSummaryDto site = toSiteSummary(record);

                if (site.getSiteCode() != null && !site.getSiteCode().isBlank()) {
                    sitesByCode.put(site.getSiteCode(), site);
                }
            }

            if (records.size() < pageSize || !hasMorePages(data, pageNo, pageSize)) {
                break;
            }

            pageNo++;
        }

        return new ArrayList<>(sitesByCode.values());
    }

    @Override
    public SiteDetailDto getSiteDetail(String siteCode) {
        JsonNode data = solisCloudClient.post(STATION_DETAIL, stationBody(siteCode));
        JsonNode detail = unwrapSingle(data);

        if (detail.isMissingNode() || detail.isNull() || detail.size() == 0) {
            Optional<SiteSummaryDto> fromList = getSitesSummary()
                    .stream()
                    .filter(site -> Objects.equals(siteCode, site.getSiteCode()))
                    .findFirst();

            return fromList.map(this::toDetail).orElseGet(() -> emptyDetail(siteCode));
        }

        SiteSummaryDto summary = toSiteSummary(detail);

        if (summary.getSiteCode() == null || summary.getSiteCode().isBlank()) {
            summary.setSiteCode(siteCode);
        }

        return toDetail(summary);
    }

    @Override
    public List<HistoryPointDto> getHistory(String siteCode, String type, String value) {
        String normalizedType = type == null || type.isBlank()
                ? "day"
                : type.toLowerCase(Locale.ROOT);

        String bucket = value == null || value.isBlank()
                ? defaultBucket(normalizedType)
                : value;

        String path = switch (normalizedType) {
            case "day" -> STATION_DAY_ENERGY;
            case "month" -> STATION_MONTH_ENERGY;
            case "year", "yearly" -> STATION_YEAR_ENERGY;
            default -> throw new IllegalArgumentException("Invalid Solis history type: " + type);
        };

        JsonNode data = solisCloudClient.post(path, stationHistoryBody(siteCode, bucket));

        List<HistoryPointDto> points = new ArrayList<>();

        for (JsonNode record : extractRecords(data)) {
            Long collectTime = collectTime(record, normalizedType, bucket);
            Double valueKwh = historyEnergyValue(record, normalizedType);

            /*
             * Important:
             * Ne pas ajouter un point sans valeur énergie.
             * Et surtout ne jamais utiliser power/pac comme fallback énergie.
             */
            if (collectTime != null && valueKwh != null) {
                points.add(new HistoryPointDto(collectTime, valueKwh));
            }
        }

        points.sort(
                Comparator.comparing(
                        HistoryPointDto::getCollectTime,
                        Comparator.nullsLast(Long::compareTo)
                )
        );

        return points;
    }

    /**
     * Batch Solis pour accélérer le backfill des courbes MONTH/YEAR.
     * SolisCloud accepte jusqu’à 100 stations par appel via idList/stationIds.
     * Le DAY reste site par site, car la courbe détaillée d’une journée est plus spécifique.
     */
    public Map<String, List<HistoryPointDto>> getBatchHistory(
            List<String> siteCodes,
            String type,
            String value
    ) {
        if (siteCodes == null || siteCodes.isEmpty()) {
            return Map.of();
        }

        String normalizedType = type == null || type.isBlank()
                ? "month"
                : type.toLowerCase(Locale.ROOT);

        if (!"month".equals(normalizedType) && !"year".equals(normalizedType) && !"yearly".equals(normalizedType)) {
            throw new IllegalArgumentException("Solis batch history supports only month/year. For day use single-station history.");
        }

        String bucket = value == null || value.isBlank()
                ? defaultBucket(normalizedType)
                : value;

        String path = "month".equals(normalizedType)
                ? STATION_MONTH_ENERGY
                : STATION_YEAR_ENERGY;

        List<String> ids = siteCodes.stream()
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .limit(100)
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pageNo", 1);
        body.put("pageSize", Math.min(100, ids.size()));
        body.put("time", bucket);
        body.put("idList", ids);
        body.put("stationIds", String.join(",", ids));

        JsonNode data = solisCloudClient.post(path, body);
        Map<String, List<HistoryPointDto>> pointsBySite = new LinkedHashMap<>();

        for (JsonNode record : extractRecords(data)) {
            String stationId = firstText(record, "stationId", "stationCode", "id", "plantId", "psId");

            if ((stationId == null || stationId.isBlank()) && ids.size() == 1) {
                stationId = ids.get(0);
            }

            if (stationId == null || stationId.isBlank()) {
                continue;
            }

            Long collectTime = collectTime(record, normalizedType, bucket);
            Double valueKwh = historyEnergyValue(record, normalizedType);

            if (collectTime != null && valueKwh != null) {
                pointsBySite.computeIfAbsent(stationId, ignored -> new ArrayList<>())
                        .add(new HistoryPointDto(collectTime, valueKwh));
            }
        }

        pointsBySite.replaceAll((stationId, points) -> points.stream()
                .sorted(Comparator.comparing(
                        HistoryPointDto::getCollectTime,
                        Comparator.nullsLast(Long::compareTo)
                ))
                .toList());

        return pointsBySite;
    }

    @Override
    public List<HistoryPointDto> getYearlyHistory(String siteCode) {
        int currentYear = Year.now(TUNIS_ZONE).getValue();
        int firstYear = currentYear - 9;

        List<HistoryPointDto> points = new ArrayList<>();

        for (int year = firstYear; year <= currentYear; year++) {
            List<HistoryPointDto> months = getHistory(siteCode, "year", String.valueOf(year));

            double total = months.stream()
                    .map(HistoryPointDto::getValue)
                    .filter(Objects::nonNull)
                    .mapToDouble(Double::doubleValue)
                    .sum();

            Long time = LocalDate.of(year, 1, 1)
                    .atStartOfDay(TUNIS_ZONE)
                    .toInstant()
                    .toEpochMilli();

            points.add(new HistoryPointDto(time, round2(total)));
        }

        return points;
    }

    @Override
    public SiteDevicesResponseDto getSiteDevices(String siteCode) {
        JsonNode data = solisCloudClient.post(INVERTER_LIST, Map.of(
                "pageNo", 1,
                "pageSize", 100,
                "stationId", siteCode,
                "stationCode", siteCode,
                "id", siteCode
        ));

        List<DeviceItemDto> devices = new ArrayList<>();

        for (JsonNode record : extractRecords(data)) {
            DeviceItemDto device = new DeviceItemDto();

            device.setId(firstText(record, "id", "inverterId", "deviceId", "sn", "esnCode"));
            device.setDevDn(firstText(record, "devDn", "deviceDn", "inverterId", "id", "sn"));
            device.setDevName(firstText(record, "devName", "deviceName", "inverterName", "name", "sn"));
            device.setStationCode(siteCode);
            device.setEsnCode(firstText(record, "esnCode", "sn", "serialNumber", "inverterSn"));
            device.setDevTypeId(firstInteger(record, "devTypeId", "deviceType", "type"));
            device.setModel(firstText(record, "model", "inverterModel", "productModel"));
            device.setInvType(firstText(record, "invType", "inverterType", "typeName"));

            devices.add(device);
        }

        return new SiteDevicesResponseDto(siteCode, devices);
    }

    private SiteSummaryDto toSiteSummary(JsonNode node) {
        Double dayEnergyKwh = nonNegative(firstEnergyKwh(
                node,
                "dayEnergyKwh", "dayEnergy", "todayEnergy", "etoday"
        ));

        Double monthEnergyKwh = nonNegative(firstEnergyKwh(
                node,
                "monthEnergyKwh", "monthEnergy", "emonth"
        ));

        Double rawYearEnergyKwh = firstEnergyKwh(
                node,
                "yearEnergyKwh", "yearEnergy", "eyear"
        );

        Double totalEnergyKwh = nonNegative(firstEnergyKwh(
                node,
                "totalEnergyKwh", "totalEnergy", "allEnergy", "eTotal", "etotal"
        ));

        Double yearEnergyKwh = normalizeSummaryYearEnergy(
                rawYearEnergyKwh,
                monthEnergyKwh,
                totalEnergyKwh
        );

        return new SiteSummaryDto(
                Brand.SOLIS.name(),
                firstText(node, "stationCode", "stationId", "id", "plantId", "psId"),
                firstText(node, "stationName", "plantName", "name", "psName"),
                toApiStatus(firstText(node, "status", "state", "runningStatus", "stationStatus")),
                nonNegative(firstNumber(node, "installedPowerKw", "installedPower", "capacity", "capacityKw", "totalCapacity", "powerCapacity")),
                normalizeSolisDisplayDate(firstText(node, "gridConnectionDate", "gridConnectedTime", "connectTime", "createDate", "createTime")),
                dayEnergyKwh,
                monthEnergyKwh,
                yearEnergyKwh,
                totalEnergyKwh,
                normalizeSolisDisplayDate(firstText(node, "swapDate", "replacementDate", "replacementTime"))
        );
    }

    private Double normalizeSummaryYearEnergy(Double rawYearEnergyKwh, Double monthEnergyKwh, Double totalEnergyKwh) {
        Double year = nonNegative(rawYearEnergyKwh);
        Double month = nonNegative(monthEnergyKwh);
        Double total = nonNegative(totalEnergyKwh);

        if (year == null) {
            return null;
        }

        if (month != null && month > 0 && year + 0.000001 < month) {
            return null;
        }

        if (total != null && total > 0 && year - 0.000001 > total) {
            return null;
        }

        return Math.round(year * 100.0) / 100.0;
    }

    private Double nonNegative(Double value) {
        if (value == null || value.isNaN() || value.isInfinite() || value < 0) {
            return null;
        }
        return value;
    }

    private SiteDetailDto toDetail(SiteSummaryDto site) {
        return new SiteDetailDto(
                Brand.SOLIS.name(),
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

    private SiteDetailDto emptyDetail(String siteCode) {
        return new SiteDetailDto(
                Brand.SOLIS.name(),
                siteCode,
                null,
                "unknown",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private Map<String, Object> stationBody(String siteCode) {
        return Map.of(
                "id", siteCode,
                "stationId", siteCode,
                "stationCode", siteCode
        );
    }

    private Map<String, Object> stationHistoryBody(String siteCode, String bucket) {
        return Map.of(
                "id", siteCode,
                "stationId", siteCode,
                "stationCode", siteCode,
                "time", bucket,
                "date", bucket
        );
    }

    /*
     * Valeur énergie historique.
     *
     * On exclut volontairement:
     * - power
     * - pac
     * - activePower
     * - currentPower
     * - totalEnergy
     * - allEnergy
     *
     * Ces champs ne doivent pas être sommés dans les historiques day/month/year.
     */
    private Double historyEnergyValue(JsonNode record, String type) {
        return switch (type) {
            case "day" -> firstEnergyKwh(
                    record,
                    "energy",
                    "generation",
                    "generationValue",
                    "yield",
                    "value",
                    "hourEnergy",
                    "dayEnergy",
                    "eToday",
                    "etoday",
                    "energyKwh",
                    "kwh"
            );

            case "month" -> firstEnergyKwh(
                    record,
                    "energy",
                    "generation",
                    "generationValue",
                    "yield",
                    "value",
                    "dayEnergy",
                    "dailyEnergy",
                    "eDay",
                    "eday",
                    "energyKwh",
                    "kwh"
            );

            case "year", "yearly" -> firstEnergyKwh(
                    record,
                    "energy",
                    "generation",
                    "generationValue",
                    "yield",
                    "value",
                    "monthEnergy",
                    "monthlyEnergy",
                    "eMonth",
                    "emonth",
                    "energyKwh",
                    "kwh"
            );

            default -> null;
        };
    }

    private List<JsonNode> extractRecords(JsonNode node) {
        List<JsonNode> records = new ArrayList<>();

        collectArray(records, node.path("records"));
        collectArray(records, node.path("list"));
        collectArray(records, node.path("rows"));

        collectArray(records, node.path("page").path("records"));
        collectArray(records, node.path("page").path("list"));

        collectArray(records, node.path("data").path("records"));
        collectArray(records, node.path("data").path("list"));
        collectArray(records, node.path("data").path("rows"));

        collectArray(records, node.path("data").path("page").path("records"));
        collectArray(records, node.path("data").path("page").path("list"));

        if (records.isEmpty() && node.isArray()) {
            collectArray(records, node);
        }

        if (records.isEmpty() && node.isObject()) {
            JsonNode single = unwrapSingle(node);

            if (single.isObject()) {
                records.add(single);
            }
        }

        return records;
    }

    private void collectArray(List<JsonNode> records, JsonNode candidate) {
        if (candidate != null && candidate.isArray()) {
            candidate.forEach(records::add);
        }
    }

    private JsonNode unwrapSingle(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }

        if (node.path("record").isObject()) {
            return node.path("record");
        }

        if (node.path("detail").isObject()) {
            return node.path("detail");
        }

        if (node.path("station").isObject()) {
            return node.path("station");
        }

        if (node.path("data").isObject()) {
            return unwrapSingle(node.path("data"));
        }

        return node;
    }

    private boolean hasMorePages(JsonNode data, int pageNo, int pageSize) {
        Integer total = firstInteger(data, "total", "totalCount", "recordCount");

        if (total == null) {
            total = firstInteger(data.path("page"), "total", "totalCount", "recordCount");
        }

        return total != null && pageNo * pageSize < total;
    }

    private String toApiStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return "unknown";
        }

        String status = rawStatus.trim().toLowerCase(Locale.ROOT);

        return switch (status) {
            case "1", "normal", "online", "running", "healthy", "ok", "active" -> "healthy";
            case "2", "fault", "faulty", "alarm", "alarms", "warning", "error" -> "faulty";
            case "3", "4", "offline", "disconnected", "inactive", "disconnect" -> "disconnected";
            default -> "unknown";
        };
    }

    private Long collectTime(JsonNode record, String type, String bucket) {
        String raw = firstText(
                record,
                "collectTime",
                "time",
                "date",
                "day",
                "month",
                "year",
                "xAxis",
                "label"
        );

        if (raw == null || raw.isBlank()) {
            return null;
        }

        raw = raw.trim();

        Long numericEpoch = parseEpochMillis(raw);
        if (numericEpoch != null) {
            return numericEpoch;
        }

        List<DateTimeFormatter> dateTimeFormatters = List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
        );

        for (DateTimeFormatter formatter : dateTimeFormatters) {
            try {
                return LocalDateTime.parse(raw, formatter)
                        .atZone(TUNIS_ZONE)
                        .toInstant()
                        .toEpochMilli();
            } catch (DateTimeParseException ignored) {
                // Try next format.
            }
        }

        try {
            return Instant.parse(raw).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // Try compact formats below.
        }

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
            return Year.parse(raw)
                    .atDay(1)
                    .atStartOfDay(TUNIS_ZONE)
                    .toInstant()
                    .toEpochMilli();
        }

        if (raw.matches("^\\d{1,2}$")) {
            int unit = Integer.parseInt(raw);

            return switch (type) {
                case "day" -> LocalDate.parse(bucket)
                        .atTime(LocalTime.of(Math.min(unit, 23), 0))
                        .atZone(TUNIS_ZONE)
                        .toInstant()
                        .toEpochMilli();

                case "month" -> {
                    YearMonth yearMonth = YearMonth.parse(bucket);
                    int day = Math.max(1, Math.min(unit, yearMonth.lengthOfMonth()));

                    yield yearMonth
                            .atDay(day)
                            .atStartOfDay(TUNIS_ZONE)
                            .toInstant()
                            .toEpochMilli();
                }

                case "year", "yearly" -> {
                    int year = Integer.parseInt(bucket.substring(0, 4));
                    int month = Math.max(1, Math.min(unit, 12));

                    yield YearMonth.of(year, month)
                            .atDay(1)
                            .atStartOfDay(TUNIS_ZONE)
                            .toInstant()
                            .toEpochMilli();
                }

                default -> null;
            };
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

            // Solis peut renvoyer des timestamps en secondes (10 chiffres), millisecondes
            // (13 chiffres), microsecondes ou parfois en notation scientifique via JSON.
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

    private String normalizeSolisDisplayDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String trimmed = raw.trim();
        Long epochMillis = collectTimeFromAnyDate(trimmed);
        if (epochMillis != null) {
            return Instant.ofEpochMilli(epochMillis).atZone(TUNIS_ZONE).toLocalDate().format(SOLIS_DISPLAY_DATE);
        }

        return trimmed;
    }

    private Long collectTimeFromAnyDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String trimmed = raw.trim();
        Long numericEpoch = parseEpochMillis(trimmed);
        if (numericEpoch != null) {
            return numericEpoch;
        }

        List<DateTimeFormatter> dateTimeFormatters = List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
                DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
                DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
        );

        for (DateTimeFormatter formatter : dateTimeFormatters) {
            try {
                return LocalDateTime.parse(trimmed, formatter)
                        .atZone(TUNIS_ZONE)
                        .toInstant()
                        .toEpochMilli();
            } catch (DateTimeParseException ignored) {
                // Try next format.
            }
        }

        try {
            return Instant.parse(trimmed).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // Try date-only formats below.
        }

        List<DateTimeFormatter> dateFormatters = List.of(
                DateTimeFormatter.ISO_LOCAL_DATE,
                SOLIS_DISPLAY_DATE,
                DateTimeFormatter.ofPattern("dd/MM/yyyy")
        );
        for (DateTimeFormatter formatter : dateFormatters) {
            try {
                return LocalDate.parse(trimmed, formatter)
                        .atStartOfDay(TUNIS_ZONE)
                        .toInstant()
                        .toEpochMilli();
            } catch (DateTimeParseException ignored) {
                // Try next format.
            }
        }

        return null;
    }

    private String defaultBucket(String type) {
        return switch (type) {
            case "month" -> YearMonth.now(TUNIS_ZONE).toString();
            case "year", "yearly" -> String.valueOf(Year.now(TUNIS_ZONE).getValue());
            default -> LocalDate.now(TUNIS_ZONE).toString();
        };
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);

            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText(null);

                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }

        return null;
    }

    private Double firstEnergyKwh(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode rawNode = node.path(field);

            if (rawNode.isMissingNode() || rawNode.isNull()) {
                continue;
            }

            String rawText = rawNode.asText(null);
            Double value = rawNode.isNumber() ? rawNode.asDouble() : parseNumber(rawText);

            if (value == null) {
                continue;
            }

            String unit = firstText(
                    node,
                    field + "Unit",
                    field + "_unit",
                    field + "unit",
                    field + "Str",
                    field + "Text",
                    field + "Label",
                    "unit",
                    "energyUnit",
                    "dataUnit"
            );

            Double converted = toKwh(value, unit != null ? unit : rawText);
            return converted != null ? round2(converted) : round2(value);
        }

        return null;
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

    private Double firstNumber(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);

            if (!value.isMissingNode() && !value.isNull()) {
                if (value.isNumber()) {
                    return value.asDouble();
                }

                String text = value.asText(null);

                Double parsed = parseNumber(text);

                if (parsed != null) {
                    return parsed;
                }
            }
        }

        return null;
    }

    private Integer firstInteger(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);

            if (!value.isMissingNode() && !value.isNull()) {
                if (value.isInt() || value.isLong()) {
                    return value.asInt();
                }

                String text = value.asText(null);

                if (text != null && !text.isBlank()) {
                    try {
                        return Integer.parseInt(text.trim());
                    } catch (NumberFormatException ignored) {
                        // Try next field.
                    }
                }
            }
        }

        return null;
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

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}