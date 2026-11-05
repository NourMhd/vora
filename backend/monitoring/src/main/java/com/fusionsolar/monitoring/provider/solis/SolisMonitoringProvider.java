package com.fusionsolar.monitoring.provider.solis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fusionsolar.monitoring.client.solis.SolisCloudClient;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "providers.solis", name = "enabled", havingValue = "true")
public class SolisMonitoringProvider implements MonitoringProvider {

    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");
    private static final DateTimeFormatter SOLIS_DISPLAY_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private static final String STATION_LIST = "/v1/api/userStationList";
    private static final String STATION_DETAIL = "/v1/api/stationDetail";

    /*
     * DAY:
     * - STATION_DAY = vraie courbe journalière d'une station.
     * - Retour attendu: plusieurs points avec time/timeStr + power.
     * - Unité courbe: kW.
     */
    private static final String STATION_DAY = "/v1/api/stationDay";

    /*
     * STATION_DAY_ENERGY retourne la production totale du jour par station.
     * On le garde en constante car utile si tu veux plus tard un KPI journalier batch,
     * mais il ne doit pas servir pour dessiner la courbe Day.
     */
    private static final String STATION_DAY_ENERGY = "/v1/api/stationDayEnergyList";

    /*
     * MONTH/YEAR single-station détaillés:
     * - STATION_MONTH retourne les kWh par jour du mois demandé.
     * - STATION_YEAR retourne les kWh par mois de l'année demandée.
     *
     * IMPORTANT:
     * Les endpoints *EnergyList retournent souvent un seul total par station.
     * Ils ne suffisent pas pour afficher le KPI journalier historique dans le front.
     */
    private static final String STATION_MONTH = "/v1/api/stationMonth";
    private static final String STATION_YEAR = "/v1/api/stationYear";

    /*
     * Gardés uniquement comme référence/fallback éventuel, pas pour les courbes UI.
     */
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

            com.fusionsolar.monitoring.provider.ProviderInventoryPage inventoryPage =
                    com.fusionsolar.monitoring.provider.ProviderInventoryPage.parse(data,
                            "records", "list", "rows", "datas", "dataList");
            List<JsonNode> records = inventoryPage.records();

            for (JsonNode record : records) {
                SiteSummaryDto site = toSiteSummary(record);

                if (site.getSiteCode() != null && !site.getSiteCode().isBlank()) {
                    if (sitesByCode.putIfAbsent(site.getSiteCode(), site) != null)
                        throw new IllegalStateException("Solis repeated site in paginated inventory");
                } else {
                    throw new IllegalStateException("Solis inventory contains invalid site identity");
                }
            }

            if (!inventoryPage.hasNext(sitesByCode.size(), pageSize)) {
                break;
            }

            if (pageNo == 50) throw new IllegalStateException("Solis inventory pagination limit exceeded");
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
                : value.trim();

        boolean dayCurve = "day".equals(normalizedType);

        String path = switch (normalizedType) {
            case "day" -> STATION_DAY;
            case "month" -> STATION_MONTH;
            case "year", "yearly" -> STATION_YEAR;
            default -> throw new IllegalArgumentException("Invalid Solis history type: " + type);
        };

        Map<String, Object> body = stationHistoryBody(siteCode, normalizedType, bucket);

        JsonNode data = solisCloudClient.post(path, body);
        List<JsonNode> records = extractHistoryRecords(data);

        /*
         * Pour DAY, /stationDay est déjà un endpoint single-station.
         * Les records peuvent ne pas contenir stationId/id.
         * Donc on ne filtre pas par id pour ne pas supprimer toute la vraie courbe.
         */
        if (dayCurve) {
            List<HistoryPointDto> points = toHistoryPoints(records, normalizedType, bucket, siteCode);
            logParsedHistory(siteCode, normalizedType, bucket, records, points);
            return points;
        }

        /*
         * Pour MONTH/YEAR, si la réponse contient explicitement stationId/stationCode,
         * on filtre par site pour éviter le retour global.
         *
         * Important:
         * On ne considère PAS "id" comme stationId ici, car certains endpoints Solis
         * retournent un id de ligne différent de l'id station.
         */
        boolean responseHasStrictStationIds = records.stream()
                .map(this::recordStationId)
                .anyMatch(id -> id != null && !id.isBlank());

        if (responseHasStrictStationIds) {
            records = records.stream()
                    .filter(record -> isRecordForStation(record, siteCode))
                    .toList();

            if (records.isEmpty()) {
                log.info(
                        "Solis history has no record for requested site. site={} type={} bucket={}",
                        siteCode,
                        normalizedType,
                        bucket
                );
                return List.of();
            }
        } else if (looksLikeUnscopedStationList(records, normalizedType, bucket)) {
            log.warn(
                    "Solis history ignored because response looks unscoped. site={} type={} bucket={} records={} sample={}",
                    siteCode,
                    normalizedType,
                    bucket,
                    records.size(),
                    records.isEmpty() ? "" : oneLineNode(records.get(0))
            );
            return List.of();
        }

        List<HistoryPointDto> points = toHistoryPoints(records, normalizedType, bucket, siteCode);
        logParsedHistory(siteCode, normalizedType, bucket, records, points);
        return points;
    }

    private void logParsedHistory(
            String siteCode,
            String normalizedType,
            String bucket,
            List<JsonNode> records,
            List<HistoryPointDto> points
    ) {
        log.info(
                "Solis history parsed site={} type={} bucket={} records={} points={}",
                siteCode,
                normalizedType,
                bucket,
                records == null ? 0 : records.size(),
                points == null ? 0 : points.size()
        );

        if (log.isDebugEnabled() && records != null && !records.isEmpty()) {
            log.debug("Solis history sample site={} type={} bucket={} sample={}",
                    siteCode, normalizedType, bucket, oneLineNode(records.get(0)));
        }
    }

    private List<HistoryPointDto> toHistoryPoints(
            List<JsonNode> records,
            String normalizedType,
            String bucket,
            String siteCode
    ) {
        if (records == null || records.isEmpty()) {
            log.debug(
                    "Solis history empty. site={} type={} bucket={}",
                    siteCode,
                    normalizedType,
                    bucket
            );
            return List.of();
        }

        List<HistoryPointDto> points = new ArrayList<>();

        for (JsonNode record : records) {
            Long collectTime = collectTime(record, normalizedType, bucket);
            Double value = historyValue(record, normalizedType);

            if (collectTime != null && value != null && value >= 0) {
                points.add(new HistoryPointDto(collectTime, value));
            }
        }

        points.sort(
                Comparator.comparing(
                        HistoryPointDto::getCollectTime,
                        Comparator.nullsLast(Long::compareTo)
                )
        );

        return deduplicateByTime(points);
    }

    /**
     * Batch Solis pour accélérer le backfill des courbes MONTH/YEAR.
     * Le DAY doit rester site par site avec /stationDay, car c'est une courbe détaillée.
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
                : value.trim();

        List<String> ids = siteCodes.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(String::trim)
                .distinct()
                .limit(100)
                .toList();

        Map<String, List<HistoryPointDto>> pointsBySite = new LinkedHashMap<>();

        /*
         * Correction importante:
         * Pour les KPI journaliers historiques, on a besoin des points journaliers du mois.
         * Les endpoints batch stationMonthEnergyList/stationYearEnergyList renvoient souvent
         * un seul total par station. On utilise donc les endpoints single-station détaillés
         * stationMonth/stationYear, site par site.
         */
        for (String siteCode : ids) {
            try {
                List<HistoryPointDto> points = getHistory(siteCode, normalizedType, bucket);
                pointsBySite.put(siteCode, points != null ? points : List.of());
            } catch (Exception e) {
                log.warn(
                        "Solis detailed history failed in batch fallback. site={} type={} bucket={} cause={}",
                        siteCode,
                        normalizedType,
                        bucket,
                        e.getMessage()
                );
                pointsBySite.put(siteCode, List.of());
            }
        }

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
                "dayEnergyKwh", "dayEnergy", "todayEnergy", "etoday", "eToday", "homeLoadTodayEnergy"
        ));

        Double monthEnergyKwh = nonNegative(firstEnergyKwh(
                node,
                "monthEnergyKwh", "monthEnergy", "emonth", "eMonth"
        ));

        Double rawYearEnergyKwh = firstEnergyKwh(
                node,
                "yearEnergyKwh", "yearEnergy", "eyear", "eYear"
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
                nonNegative(firstNumber(node, "installedPowerKw", "installedPower", "capacity", "capacityKw", "totalCapacity", "powerCapacity", "power")),
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

        return round2(value);
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
        Object stationId = stationIdBodyValue(siteCode);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", stationId);
        body.put("stationId", stationId);
        body.put("stationCode", siteCode);
        body.put("plantId", stationId);
        body.put("psId", stationId);
        return body;
    }

    private Map<String, Object> stationDayCurveBody(String siteCode, String bucket) {
        Object stationId = stationIdBodyValue(siteCode);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", stationId);

        /*
         * D'après les retours Solis observés chez toi, les données reviennent avec timeZone=8.
         * On garde donc 8 pour rester aligné avec SolisCloud.
         */
        body.put("money", "TND");
        body.put("time", bucket);
        body.put("timeZone", 8);
        body.put("nmiCode", "");

        return body;
    }

    private Map<String, Object> stationHistoryBody(String siteCode, String type, String bucket) {
        if ("day".equals(type)) {
            return stationDayCurveBody(siteCode, bucket);
        }

        Object stationId = stationIdBodyValue(siteCode);
        Map<String, Object> body = new LinkedHashMap<>();

        /*
         * IMPORTANT SOLIS:
         * La documentation officielle de /stationMonth et /stationYear attend un body minimal:
         *   stationMonth: id, money, month, timeZone, nmiCode
         *   stationYear : id, money, year,  timeZone, nmiCode
         * Les variantes stationId/stationCode/plantId/psId/time/date/startDate/endDate peuvent
         * provoquer des réponses partielles selon les comptes. On les évite ici.
         */
        body.put("id", stationId);
        body.put("money", "TND");
        body.put("timeZone", 8);
        body.put("nmiCode", "");

        if ("month".equals(type)) {
            body.put("month", bucket);
        } else if ("year".equals(type) || "yearly".equals(type)) {
            body.put("year", bucket.substring(0, Math.min(4, bucket.length())));
        } else {
            body.put("time", bucket);
        }

        return body;
    }

    private Object stationIdBodyValue(String siteCode) {
        if (siteCode == null) {
            return "";
        }

        /*
         * Solis montre les IDs station entre guillemets dans ses exemples officiels.
         * On conserve donc le 19 chiffres en String. Cela évite toute ambiguïté côté API
         * et tout risque d'arrondi si le backend Solis passe par un parser JSON number.
         */
        return siteCode.trim();
    }

    private boolean isRecordForStation(JsonNode record, String siteCode) {
        String recordStationId = recordStationId(record);

        if (recordStationId == null || recordStationId.isBlank()) {
            return true;
        }

        String left = normalizedProviderId(recordStationId);
        String right = normalizedProviderId(siteCode);

        return left != null && left.equals(right);
    }

    /*
     * Strict station id.
     *
     * Important:
     * On ne met PAS "id" ici, car certains endpoints Solis retournent un id de ligne
     * différent de l'id station.
     */
    private String recordStationId(JsonNode record) {
        return firstText(
                record,
                "stationId",
                "stationCode",
                "station_id",
                "plantId",
                "plant_id",
                "psId",
                "ps_id"
        );
    }

    private String recordStationIdForRequested(JsonNode record, Set<String> requestedIds) {
        String scoped = recordStationId(record);

        if (scoped != null && !scoped.isBlank()) {
            return scoped;
        }

        /*
         * Pour les endpoints batch, le champ "id" peut être l'id station.
         * On l'accepte seulement s'il correspond vraiment à l'un des IDs demandés.
         */
        String id = firstText(record, "id");
        String normalizedId = normalizedProviderId(id);

        if (normalizedId != null && requestedIds != null && requestedIds.contains(normalizedId)) {
            return id;
        }

        return null;
    }

    private String requestedSiteKey(String stationId, List<String> requestedIds) {
        String normalizedStationId = normalizedProviderId(stationId);

        if (normalizedStationId == null || requestedIds == null || requestedIds.isEmpty()) {
            return stationId;
        }

        return requestedIds.stream()
                .filter(id -> normalizedStationId.equals(normalizedProviderId(id)))
                .findFirst()
                .orElse(stationId);
    }

    private String normalizedProviderId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String trimmed = value.trim();

        if (trimmed.matches("^\\d+(?:\\.0+)?$")) {
            String numeric = trimmed.replaceAll("\\.0+$", "");
            numeric = numeric.replaceFirst("^0+(?!$)", "");
            return numeric;
        }

        return trimmed.toLowerCase(Locale.ROOT);
    }

    private boolean looksLikeUnscopedStationList(List<JsonNode> records, String type, String bucket) {
        if (records == null || records.size() <= 1) {
            return false;
        }

        /*
         * Si les records ont un axe horaire/journalier/mensuel exploitable,
         * ce n'est probablement pas une liste globale non filtrée.
         */
        if (hasGranularAxis(records, type)) {
            return false;
        }

        long validTimes = records.stream()
                .map(record -> collectTime(record, type, bucket))
                .filter(Objects::nonNull)
                .count();

        long distinctTimes = records.stream()
                .map(record -> collectTime(record, type, bucket))
                .filter(Objects::nonNull)
                .distinct()
                .count();

        return validTimes > 1 && distinctTimes <= 1;
    }

    /*
     * DAY retourne maintenant une puissance kW.
     * MONTH/YEAR retournent des énergies kWh.
     */
    private Double historyValue(JsonNode record, String type) {
        return switch (type) {
            case "day" -> firstPowerKw(
                    record,
                    "power",
                    "psum",
                    "pac",
                    "p",
                    "value"
            );

            case "month" -> firstEnergyKwh(
                    record,
                    "energy",
                    "energyStr",
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
                    "energyStr",
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

    private List<JsonNode> extractHistoryRecords(JsonNode node) {
        List<JsonNode> records = new ArrayList<>();

        collectPairedAxisRecords(records, node);
        if (!records.isEmpty()) {
            return records;
        }

        return extractRecords(node);
    }

    private List<JsonNode> extractRecords(JsonNode node) {
        List<JsonNode> records = new ArrayList<>();

        if (node == null || node.isMissingNode() || node.isNull()) {
            return records;
        }

        collectArray(records, node.path("records"));
        collectArray(records, node.path("list"));
        collectArray(records, node.path("rows"));
        collectArray(records, node.path("datas"));
        collectArray(records, node.path("dataList"));

        collectArray(records, node.path("page").path("records"));
        collectArray(records, node.path("page").path("list"));

        collectArray(records, node.path("data").path("records"));
        collectArray(records, node.path("data").path("list"));
        collectArray(records, node.path("data").path("rows"));
        collectArray(records, node.path("data").path("datas"));
        collectArray(records, node.path("data").path("dataList"));

        collectArray(records, node.path("data").path("page").path("records"));
        collectArray(records, node.path("data").path("page").path("list"));

        if (records.isEmpty() && node.isArray()) {
            collectArray(records, node);
        }

        if (records.isEmpty() && node.path("data").isArray()) {
            collectArray(records, node.path("data"));
        }

        if (records.isEmpty() && node.isObject()) {
            JsonNode single = unwrapSingle(node);

            if (single.isObject()) {
                records.add(single);
            }
        }

        return records;
    }

    private void collectPairedAxisRecords(List<JsonNode> records, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }

        if (node.isObject()) {
            JsonNode axisArray = firstArray(
                    node,
                    "xAxis",
                    "xaxis",
                    "xData",
                    "xdata",
                    "times",
                    "timeList",
                    "dateList",
                    "dayList",
                    "monthList",
                    "labels",
                    "labelList",
                    "names",
                    "nameList"
            );

            JsonNode valueArray = normalizeSeriesArray(firstArray(
                    node,
                    "yAxis",
                    "yaxis",
                    "yData",
                    "ydata",
                    "values",
                    "valueList",
                    "energyList",
                    "energies",
                    "dayEnergyList",
                    "monthEnergyList",
                    "generationList",
                    "generationValueList",
                    "powerList",
                    "powers",
                    "pacList",
                    "dataList",
                    "datas"
            ));

            if (axisArray != null
                    && valueArray != null
                    && axisArray.isArray()
                    && valueArray.isArray()
                    && axisArray.size() > 0
                    && valueArray.size() > 0) {
                int size = Math.min(axisArray.size(), valueArray.size());

                for (int index = 0; index < size; index++) {
                    ObjectNode record = JsonNodeFactory.instance.objectNode();
                    copyAxisValue(record, axisArray.get(index));
                    copyHistoryValue(record, valueArray.get(index));
                    records.add(record);
                }

                return;
            }

            node.fields().forEachRemaining(entry -> collectPairedAxisRecords(records, entry.getValue()));
            return;
        }

        if (node.isArray()) {
            node.forEach(child -> collectPairedAxisRecords(records, child));
        }
    }

    private JsonNode firstArray(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) {
            return null;
        }

        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value != null && value.isArray()) {
                return value;
            }
        }

        return null;
    }

    private JsonNode normalizeSeriesArray(JsonNode candidate) {
        if (candidate == null || !candidate.isArray()) {
            return candidate;
        }

        if (candidate.size() > 0 && candidate.get(0).isArray()) {
            return candidate.get(0);
        }

        if (candidate.size() > 0 && candidate.get(0).isObject()) {
            JsonNode data = firstArray(candidate.get(0), "data", "values", "valueList", "energyList", "powerList");
            if (data != null) {
                return data;
            }
        }

        return candidate;
    }

    private void copyAxisValue(ObjectNode target, JsonNode axis) {
        if (axis == null || axis.isNull()) {
            return;
        }

        if (axis.isObject()) {
            axis.fields().forEachRemaining(entry -> target.set(entry.getKey(), entry.getValue()));
            return;
        }

        target.set("xAxis", axis);
    }

    private void copyHistoryValue(ObjectNode target, JsonNode value) {
        if (value == null || value.isNull()) {
            return;
        }

        if (value.isObject()) {
            value.fields().forEachRemaining(entry -> target.set(entry.getKey(), entry.getValue()));
            if (!target.has("value")) {
                JsonNode nestedValue = value.path("data");
                if (!nestedValue.isMissingNode() && !nestedValue.isArray() && !nestedValue.isObject()) {
                    target.set("value", nestedValue);
                }
            }
            return;
        }

        target.set("value", value);
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

        return total == null || pageNo * pageSize < total;
    }

  private String toApiStatus(String rawStatus) {
    if (rawStatus == null || rawStatus.isBlank()) {
        return "unknown";
    }

    String status = rawStatus.trim().toLowerCase(Locale.ROOT);

    return switch (status) {
        case "1", "normal", "online", "running",
             "healthy", "ok", "active" -> "healthy";

        case "2", "offline", "disconnected",
             "inactive", "disconnect" -> "disconnected";

        case "3", "fault", "faulty", "alarm",
             "alarms", "warning", "error" -> "faulty";

        default -> "unknown";
    };
}

    private Long collectTime(JsonNode record, String type, String bucket) {
        Long granularTime = collectTimeFromGranularAxis(record, type, bucket);

        if (granularTime != null) {
            return granularTime;
        }

        String raw = firstText(
                record,
                "collectTime",
                "collect_time",
                "dataTime",
                "data_time",
                "timestamp",
                "time",
                "date",
                "dateStr",
                "timeStr",
                "day",
                "month",
                "year",
                "xAxis",
                "xaxis",
                "label",
                "name"
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

    private Long collectTimeFromGranularAxis(JsonNode record, String type, String bucket) {
        return switch (type) {
            case "day" -> {
                String timeText = firstText(
                        record,
                        "timeStr",
                        "timeText",
                        "hourText",
                        "xAxis",
                        "xaxis",
                        "label",
                        "name"
                );

                LocalTime time = parseTimeOfDay(timeText);

                if (time != null) {
                    yield LocalDate.parse(bucket)
                            .atTime(time)
                            .atZone(TUNIS_ZONE)
                            .toInstant()
                            .toEpochMilli();
                }

                Integer hour = parseHourUnit(firstText(
                        record,
                        "hour",
                        "hours",
                        "hourNo",
                        "hourIndex",
                        "timePoint",
                        "point"
                ));

                if (hour == null) {
                    yield null;
                }

                yield LocalDate.parse(bucket)
                        .atTime(LocalTime.of(hour, 0))
                        .atZone(TUNIS_ZONE)
                        .toInstant()
                        .toEpochMilli();
            }

            case "month" -> {
                Integer day = parseDayUnit(firstText(
                        record,
                        "day",
                        "dayNo",
                        "dayIndex",
                        "dateStr",
                        "date",
                        "xAxis",
                        "xaxis",
                        "label",
                        "name"
                ));

                if (day == null) {
                    yield null;
                }

                YearMonth yearMonth = YearMonth.parse(bucket);
                int safeDay = Math.max(1, Math.min(day, yearMonth.lengthOfMonth()));

                yield yearMonth
                        .atDay(safeDay)
                        .atStartOfDay(TUNIS_ZONE)
                        .toInstant()
                        .toEpochMilli();
            }

            case "year", "yearly" -> {
                Integer month = parseMonthUnit(firstText(
                        record,
                        "month",
                        "monthNo",
                        "monthIndex",
                        "dateStr",
                        "date",
                        "xAxis",
                        "xaxis",
                        "label",
                        "name"
                ));

                if (month == null) {
                    yield null;
                }

                int year = Integer.parseInt(bucket.substring(0, 4));
                int safeMonth = Math.max(1, Math.min(month, 12));

                yield YearMonth.of(year, safeMonth)
                        .atDay(1)
                        .atStartOfDay(TUNIS_ZONE)
                        .toInstant()
                        .toEpochMilli();
            }

            default -> null;
        };
    }

    private boolean hasGranularAxis(List<JsonNode> records, String type) {
        if (records == null || records.isEmpty()) {
            return false;
        }

        return switch (type) {
            case "day" -> records.stream().anyMatch(record ->
                    parseTimeOfDay(firstText(
                            record,
                            "timeStr",
                            "timeText",
                            "hourText",
                            "xAxis",
                            "xaxis",
                            "label",
                            "name"
                    )) != null
                            || parseHourUnit(firstText(
                            record,
                            "hour",
                            "hours",
                            "hourNo",
                            "hourIndex",
                            "timePoint",
                            "point"
                    )) != null
            );

            case "month" -> records.stream().anyMatch(record -> parseDayUnit(firstText(
                    record,
                    "day",
                    "dayNo",
                    "dayIndex",
                    "dateStr",
                    "date",
                    "xAxis",
                    "xaxis",
                    "label",
                    "name"
            )) != null);

            case "year", "yearly" -> records.stream().anyMatch(record -> parseMonthUnit(firstText(
                    record,
                    "month",
                    "monthNo",
                    "monthIndex",
                    "dateStr",
                    "date",
                    "xAxis",
                    "xaxis",
                    "label",
                    "name"
            )) != null);

            default -> false;
        };
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
                // Try next.
            }
        }

        return null;
    }

    private Integer parseHourUnit(String raw) {
        Integer value = parseSmallUnit(raw, 0, 23);

        if (value != null) {
            return value;
        }

        if (raw == null || raw.isBlank()) {
            return null;
        }

        String text = raw.trim().toLowerCase(Locale.ROOT);

        if (text.matches("^\\d{1,2}:\\d{2}.*$")) {
            try {
                int hour = Integer.parseInt(text.substring(0, text.indexOf(':')));

                if (hour >= 0 && hour <= 23) {
                    return hour;
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        if (text.matches("^\\d{1,2}h.*$")) {
            try {
                int hour = Integer.parseInt(text.replaceFirst("^(\\d{1,2}).*$", "$1"));

                if (hour >= 0 && hour <= 23) {
                    return hour;
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        return null;
    }

    private Integer parseDayUnit(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String text = raw.trim();

        if (text.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            try {
                return LocalDate.parse(text).getDayOfMonth();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }

        if (text.matches("^\\d{4}/\\d{2}/\\d{2}$")) {
            try {
                return LocalDate.parse(text, DateTimeFormatter.ofPattern("yyyy/MM/dd")).getDayOfMonth();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }

        return parseSmallUnit(text, 1, 31);
    }

    private Integer parseMonthUnit(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String text = raw.trim();

        if (text.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            try {
                return LocalDate.parse(text).getMonthValue();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }

        if (text.matches("^\\d{4}/\\d{2}/\\d{2}$")) {
            try {
                return LocalDate.parse(text, DateTimeFormatter.ofPattern("yyyy/MM/dd")).getMonthValue();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }

        if (text.matches("^\\d{4}-\\d{2}$")) {
            try {
                return YearMonth.parse(text).getMonthValue();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }

        return parseSmallUnit(text, 1, 12);
    }

    private Integer parseSmallUnit(String raw, int min, int max) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String text = raw.trim()
                .toLowerCase(Locale.ROOT)
                .replace(" ", "");

        if (text.matches("^\\d{1,2}(?:\\.0+)?$")) {
            try {
                int value = (int) Double.parseDouble(text);

                if (value >= min && value <= max) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
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

    private String normalizeSolisDisplayDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String trimmed = raw.trim();
        Long epochMillis = collectTimeFromAnyDate(trimmed);

        if (epochMillis != null) {
            return Instant.ofEpochMilli(epochMillis)
                    .atZone(TUNIS_ZONE)
                    .toLocalDate()
                    .format(SOLIS_DISPLAY_DATE);
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
                // Try next.
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
                // Try next.
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
        if (node == null) {
            return null;
        }

        for (String field : fields) {
            JsonNode value = node.path(field);

            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText(null);

                if (text != null && !text.isBlank() && !"null".equalsIgnoreCase(text)) {
                    return text.trim();
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
                    "dataUnit",
                    "energyStr"
            );

            Double converted = toKwh(value, unit != null ? unit : rawText);
            return converted != null ? round2(converted) : round2(value);
        }

        return null;
    }

    private Double firstPowerKw(JsonNode node, String... fields) {
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

            /*
             * Solis stationDay expose souvent une valeur brute avec powerPec=0.001.
             * Le libellé powerStr peut néanmoins être "kW" : ignorer le coefficient
             * transforme par exemple 20 W en 20 kW. Le coefficient fournisseur est
             * donc appliqué avant la conversion d'unité.
             */
            Double coefficient = firstNumber(node, field + "Pec", field + "_pec");
            if (coefficient == null && "power".equals(field)) {
                coefficient = firstNumber(node, "powerPec");
            }
            if (coefficient != null && coefficient > 0) {
                value *= coefficient;
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
                    "powerUnit",
                    "dataUnit",
                    "powerStr",
                    "psumStr",
                    "pacStr"
            );

            Double converted = toKw(value, unit != null ? unit : rawText);
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

    private List<HistoryPointDto> deduplicateByTime(List<HistoryPointDto> points) {
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

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
