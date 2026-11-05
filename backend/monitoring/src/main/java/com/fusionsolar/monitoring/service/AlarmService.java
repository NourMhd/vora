package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.dto.AlarmDashboardDto;
import com.fusionsolar.monitoring.dto.AlarmDto;
import com.fusionsolar.monitoring.dto.AlarmSectionDto;
import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.persistence.document.AlarmDocument;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.document.SiteHistoryDocument;
import com.fusionsolar.monitoring.persistence.enums.AlarmType;
import com.fusionsolar.monitoring.persistence.enums.HistoryGranularity;
import com.fusionsolar.monitoring.persistence.enums.SiteStatus;
import com.fusionsolar.monitoring.persistence.repositories.AlarmRepository;
import com.fusionsolar.monitoring.persistence.repositories.SiteHistoryRepository;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlarmService {

    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");
    private static final int LAST_MONITORED_LOOKBACK_DAYS = 90;
    private static final int ALARM_HISTORY_LIMIT = 300;
    private static final double GOVERNORATE_GAP_THRESHOLD_PERCENT = 20.0;
    private static final Duration NO_MONITORING_THRESHOLD = Duration.ofDays(1);

    private final SiteRepository siteRepository;
    private final SiteHistoryRepository siteHistoryRepository;
    private final AlarmRepository alarmRepository;
    private final CurrentSiteAccessService currentSiteAccessService;
    private final ProductionEstimationService productionEstimationService;

    public AlarmDashboardDto getDashboard() {
        Instant now = Instant.now();
        List<SiteDocument> visibleSites = siteRepository.findAllByOrderBySiteNameAsc()
                .stream()
                .filter(currentSiteAccessService::canAccess)
                .filter(SiteAccessPolicy::isActive)
                .toList();

        Set<String> visibleAlarmKeys = possibleAlarmKeys(visibleSites);
        Map<String, AlarmCandidate> candidates = new LinkedHashMap<>();
        collectGovernorateGapAlarms(visibleSites, candidates, now);
        collectNoMonitoringAlarms(visibleSites, candidates, now);
        collectStatusAlarms(visibleSites, candidates, now);

        List<AlarmDocument> activeDocuments = synchronizeAlarmState(candidates, visibleAlarmKeys, now);
        List<AlarmDto> activeDtos = activeDocuments.stream()
                .map(this::toDto)
                .sorted(this::compareAlarmDtos)
                .toList();

        List<AlarmDto> history = alarmRepository
                .findByActiveFalseOrderByClearedAtDesc(PageRequest.of(0, ALARM_HISTORY_LIMIT))
                .stream()
                .filter(document -> document.getAlarmKey() != null && visibleAlarmKeys.contains(document.getAlarmKey()))
                .map(this::toDto)
                .toList();

        List<AlarmSectionDto> sections = List.of(
                section(
                        AlarmType.PERFORMANCE_GAP,
                        "Sites à -20% ou plus",
                        "Sites Healthy dont le rendement kWh/kWc du dernier jour monitorisé complet hors aujourd’hui est au moins 20% sous le meilleur site Healthy du même gouvernorat.",
                        activeDtos
                ),
                section(
                        AlarmType.NO_MONITORING_1D,
                        "1 jour ou plus sans monitoring",
                        "Sites sans nouveau point de monitoring depuis au moins 1 jour jusqu’à maintenant.",
                        activeDtos
                ),
                section(
                        AlarmType.STATUS_FAULT_OR_OFFLINE,
                        "Sites en défaut ou offline",
                        "Sites dont le statut fournisseur est Faulty/Défaut ou Disconnected/Offline.",
                        activeDtos
                )
        );

        return AlarmDashboardDto.builder()
                .generatedAt(now.toString())
                .activeCount(activeDtos.size())
                .historyCount(history.size())
                .activeSections(sections)
                .history(history)
                .build();
    }

    private void collectGovernorateGapAlarms(
            List<SiteDocument> sites,
            Map<String, AlarmCandidate> candidates,
            Instant now
    ) {
        LocalDate endDay = LocalDate.now(TUNIS_ZONE).minusDays(1);

        Map<String, List<SiteDocument>> sitesByGovernorate = sites.stream()
                .filter(this::isHealthy)
                .filter(site -> nullToZero(site.getInstalledPowerKw()) > ProductionEstimationService.EPSILON)
                .filter(site -> productionEstimationService.governorateForSite(site) != null)
                .collect(Collectors.groupingBy(productionEstimationService::governorateForSite));

        for (Map.Entry<String, List<SiteDocument>> entry : sitesByGovernorate.entrySet()) {
            String governorate = entry.getKey();
            if (governorate == null || governorate.isBlank()) {
                continue;
            }

            List<PeerYield> yields = entry.getValue()
                    .stream()
                    .map(site -> latestPeerYield(site, endDay))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .filter(peer -> peer.specificYield() >= 0.0)
                    .toList();

            if (yields.size() <= 1) {
                continue;
            }

            PeerYield best = yields.stream()
                    .max(Comparator.comparingDouble(PeerYield::specificYield))
                    .orElse(null);

            if (best == null || best.specificYield() <= ProductionEstimationService.EPSILON) {
                continue;
            }

            for (PeerYield current : yields) {
                double comparisonPercent = (current.specificYield() / best.specificYield()) * 100.0;
                double gapPercent = round2(Math.max(0.0, 100.0 - comparisonPercent));
                if (gapPercent + ProductionEstimationService.EPSILON < GOVERNORATE_GAP_THRESHOLD_PERCENT) {
                    continue;
                }

                SiteDocument site = current.site();
                String message = String.format(
                        Locale.FRANCE,
                        "Écart de -%.2f %% par rapport au meilleur site du gouvernorat %s (%s). Rendement site: %.2f kWh/kWc, meilleur: %.2f kWh/kWc, jour utilisé: %s.",
                        gapPercent,
                        governorate,
                        siteLabel(best.site()),
                        current.specificYield(),
                        best.specificYield(),
                        current.date()
                );

                candidates.put(alarmKey(AlarmType.PERFORMANCE_GAP, site), AlarmCandidate.builder()
                        .type(AlarmType.PERFORMANCE_GAP)
                        .site(site)
                        .governorate(governorate)
                        .title("Écart de production par gouvernorat")
                        .message(message)
                        .severity(gapPercent >= 50.0 ? "CRITICAL" : "WARNING")
                        .metricValue(gapPercent)
                        .thresholdValue(GOVERNORATE_GAP_THRESHOLD_PERCENT)
                        .unit("%")
                        .bestSiteName(safeName(best.site()))
                        .bestSiteCode(best.site().getSiteCode())
                        .bestValue(round2(best.specificYield()))
                        .monitoredDate(current.date())
                        .firstSeenAt(now)
                        .build());
            }
        }
    }

    private void collectNoMonitoringAlarms(
            List<SiteDocument> sites,
            Map<String, AlarmCandidate> candidates,
            Instant now
    ) {
        for (SiteDocument site : sites) {
            Optional<MonitoringPoint> latest = latestMonitoringPoint(site, now);
            Duration age = latest
                    .map(point -> Duration.between(point.collectTime(), now))
                    .orElse(NO_MONITORING_THRESHOLD.plusMinutes(1));

            if (age.compareTo(NO_MONITORING_THRESHOLD) < 0) {
                continue;
            }

            double hours = round2(age.toMinutes() / 60.0);
            String message = latest
                    .map(point -> String.format(
                            Locale.FRANCE,
                            "Dernier point monitorisé à %s, soit %.2f h sans monitoring jusqu’à présent.",
                            point.collectTime().atZone(TUNIS_ZONE).toLocalDateTime(),
                            hours
                    ))
                    .orElse("Aucun point de monitoring récent trouvé; le site est considéré sans monitoring depuis au moins 1 jour.");

            candidates.put(alarmKey(AlarmType.NO_MONITORING_1D, site), AlarmCandidate.builder()
                    .type(AlarmType.NO_MONITORING_1D)
                    .site(site)
                    .governorate(productionEstimationService.governorateForSite(site))
                    .title("1 jour ou plus sans monitoring")
                    .message(message)
                    .severity(hours >= 48.0 ? "CRITICAL" : "WARNING")
                    .metricValue(hours)
                    .thresholdValue(24.0)
                    .unit("h")
                    .lastMonitoringAt(latest.map(MonitoringPoint::collectTime).orElse(null))
                    .monitoredDate(latest.map(MonitoringPoint::date).orElse(null))
                    .firstSeenAt(now)
                    .build());
        }
    }

    private void collectStatusAlarms(
            List<SiteDocument> sites,
            Map<String, AlarmCandidate> candidates,
            Instant now
    ) {
        for (SiteDocument site : sites) {
            if (!isFaultOrOffline(site)) {
                continue;
            }

            String statusLabel = site.getStatus() == SiteStatus.DISCONNECTED ? "offline" : "en défaut";
            String message = "Le site est " + statusLabel + " selon le dernier statut fournisseur.";

            candidates.put(alarmKey(AlarmType.STATUS_FAULT_OR_OFFLINE, site), AlarmCandidate.builder()
                    .type(AlarmType.STATUS_FAULT_OR_OFFLINE)
                    .site(site)
                    .governorate(productionEstimationService.governorateForSite(site))
                    .title("Site en défaut ou offline")
                    .message(message)
                    .severity(site.getStatus() == SiteStatus.DISCONNECTED ? "CRITICAL" : "WARNING")
                    .metricValue(null)
                    .thresholdValue(null)
                    .unit(null)
                    .firstSeenAt(now)
                    .build());
        }
    }

    private List<AlarmDocument> synchronizeAlarmState(
            Map<String, AlarmCandidate> candidates,
            Set<String> visibleAlarmKeys,
            Instant now
    ) {
        Map<String, AlarmDocument> activeByKey = alarmRepository.findAllByActiveTrue()
                .stream()
                .filter(document -> document.getAlarmKey() != null)
                .filter(document -> visibleAlarmKeys.contains(document.getAlarmKey()))
                .collect(Collectors.toMap(AlarmDocument::getAlarmKey, Function.identity(), (left, right) -> left));

        List<AlarmDocument> savedActive = new ArrayList<>();

        for (AlarmCandidate candidate : candidates.values()) {
            AlarmDocument existing = activeByKey.remove(candidate.alarmKey());
            AlarmDocument document = existing != null ? existing : new AlarmDocument();
            applyCandidate(document, candidate, now);
            savedActive.add(alarmRepository.save(document));
        }

        for (AlarmDocument resolved : activeByKey.values()) {
            resolved.setActive(false);
            resolved.setClearedAt(now);
            resolved.setUpdatedAt(now);
            alarmRepository.save(resolved);
        }

        return savedActive;
    }


    private Set<String> possibleAlarmKeys(List<SiteDocument> sites) {
        if (sites == null || sites.isEmpty()) {
            return Set.of();
        }
        Set<String> keys = new HashSet<>();
        for (SiteDocument site : sites) {
            keys.add(alarmKey(AlarmType.PERFORMANCE_GAP, site));
            keys.add(alarmKey(AlarmType.NO_MONITORING_1D, site));
            keys.add(alarmKey(AlarmType.STATUS_FAULT_OR_OFFLINE, site));
        }
        return keys;
    }

    private void applyCandidate(AlarmDocument document, AlarmCandidate candidate, Instant now) {
        SiteDocument site = candidate.site();
        document.setAlarmKey(candidate.alarmKey());
        document.setType(candidate.type());
        document.setActive(true);
        document.setBrand(site.getBrand());
        document.setProviderAccountGroup(accountGroupOf(site));
        document.setSiteCode(site.getSiteCode());
        document.setSiteName(safeName(site));
        document.setSiteStatus(site.getStatus());
        document.setGovernorate(candidate.governorate());
        document.setTitle(candidate.title());
        document.setMessage(candidate.message());
        document.setSeverity(candidate.severity());
        document.setMetricValue(candidate.metricValue());
        document.setThresholdValue(candidate.thresholdValue());
        document.setUnit(candidate.unit());
        document.setBestSiteName(candidate.bestSiteName());
        document.setBestSiteCode(candidate.bestSiteCode());
        document.setBestValue(candidate.bestValue());
        document.setMonitoredDate(candidate.monitoredDate());
        document.setLastMonitoringAt(candidate.lastMonitoringAt());
        document.setClearedAt(null);
        document.setLastDetectedAt(now);
        document.setUpdatedAt(now);

        if (document.getFirstDetectedAt() == null) {
            document.setFirstDetectedAt(now);
        }
        if (document.getCreatedAt() == null) {
            document.setCreatedAt(now);
        }
    }

    private AlarmSectionDto section(
            AlarmType type,
            String title,
            String description,
            List<AlarmDto> activeAlarms
    ) {
        List<AlarmDto> alarms = activeAlarms.stream()
                .filter(alarm -> type.name().equals(alarm.getType()))
                .toList();
        return AlarmSectionDto.builder()
                .type(type.name())
                .title(title)
                .description(description)
                .count(alarms.size())
                .alarms(alarms)
                .build();
    }

    private Optional<PeerYield> latestPeerYield(SiteDocument site, LocalDate endDay) {
        double power = nullToZero(site != null ? site.getInstalledPowerKw() : null);
        if (power <= ProductionEstimationService.EPSILON) {
            return Optional.empty();
        }

        return lastMonitoredProduction(site, endDay, LAST_MONITORED_LOOKBACK_DAYS)
                .map(result -> new PeerYield(
                        site,
                        result.date(),
                        result.realMonitoredKwh(),
                        result.realMonitoredKwh() / power
                ));
    }

    private Optional<ProductionDayResult> lastMonitoredProduction(
            SiteDocument site,
            LocalDate endDay,
            int lookbackDays
    ) {
        if (site == null || endDay == null) {
            return Optional.empty();
        }

        int safeLookbackDays = Math.max(1, lookbackDays);
        LocalDate startDay = endDay.minusDays(safeLookbackDays - 1L);
        ProductionEstimationService.DailySiteProduction production = productionMetadata(site);

        LocalDate cursor = endDay;
        while (!cursor.isBefore(startDay)) {
            if (!productionEstimationService.isProductionDay(production, cursor)) {
                cursor = cursor.minusDays(1);
                continue;
            }

            Optional<Double> dayHistoryTotal = realKwhFromMonthHistory(site, cursor);
            if (dayHistoryTotal.isPresent()) {
                return Optional.of(new ProductionDayResult(
                        cursor,
                        round2(dayHistoryTotal.get())
                ));
            }

            cursor = cursor.minusDays(1);
        }

        return Optional.empty();
    }

    private Optional<Double> realKwhFromMonthHistory(SiteDocument site, LocalDate day) {
        if (site == null || day == null) {
            return Optional.empty();
        }

        String monthBucket = day.toString().substring(0, 7);

        Optional<SiteHistoryDocument> history = findHistory(
                site,
                HistoryGranularity.MONTH,
                monthBucket
        );

        if (history.isEmpty() || history.get().getPoints() == null || history.get().getPoints().isEmpty()) {
            return Optional.empty();
        }

        for (HistoryPointDto point : history.get().getPoints()) {
            if (point == null || point.getCollectTime() == null) {
                continue;
            }

            LocalDate pointDay = toLocalDate(point.getCollectTime());
            if (!day.equals(pointDay)) {
                continue;
            }

            Double value = productionEstimationService.normalizeHistoryValue(point.getValue());
            if (value != null && value > ProductionEstimationService.EPSILON) {
                return Optional.of(round2(value));
            }
        }

        return Optional.empty();
    }

    private ProductionEstimationService.DailySiteProduction productionMetadata(SiteDocument site) {
        String governorate = productionEstimationService.governorateForSite(site);
        String zone = productionEstimationService.zoneForGovernorate(governorate);

        return new ProductionEstimationService.DailySiteProduction(
                site,
                site != null ? site.getSiteCode() : null,
                site != null ? site.getSiteName() : null,
                governorate,
                zone,
                site != null ? nullToZero(site.getInstalledPowerKw()) : 0.0,
                site != null ? site.getSwapDate() : null,
                Map.of(),
                Set.of()
        );
    }

    private Optional<MonitoringPoint> latestMonitoringPoint(SiteDocument site, Instant now) {
        if (site == null || now == null) {
            return Optional.empty();
        }

        LocalDate today = LocalDate.now(TUNIS_ZONE);
        List<String> buckets = List.of(today.toString(), today.minusDays(1).toString());

        Optional<MonitoringPoint> fromHistory = buckets.stream()
                .map(bucket -> findHistory(site, HistoryGranularity.DAY, bucket))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .flatMap(history -> history.getPoints() == null ? java.util.stream.Stream.empty() : history.getPoints().stream())
                .map(this::toMonitoringPoint)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(point -> !point.collectTime().isAfter(now))
                .max(Comparator.comparing(MonitoringPoint::collectTime));

        if (fromHistory.isPresent()) {
            return fromHistory;
        }

        Instant fallback = site.getLastSyncAt();
        if (fallback == null) {
            fallback = site.getUpdatedAt();
        }
        if (fallback == null) {
            return Optional.empty();
        }
        return Optional.of(new MonitoringPoint(fallback, fallback.atZone(TUNIS_ZONE).toLocalDate()));
    }

    private Optional<MonitoringPoint> toMonitoringPoint(HistoryPointDto point) {
        if (point == null || point.getCollectTime() == null) {
            return Optional.empty();
        }

        Double value = productionEstimationService.normalizeHistoryValue(point.getValue());
        if (value == null) {
            return Optional.empty();
        }

        Instant instant = toInstant(point.getCollectTime());
        return Optional.of(new MonitoringPoint(instant, instant.atZone(TUNIS_ZONE).toLocalDate()));
    }

    private Optional<SiteHistoryDocument> findHistory(
            SiteDocument site,
            HistoryGranularity granularity,
            String bucket
    ) {
        if (site == null) {
            return Optional.empty();
        }

        Optional<SiteHistoryDocument> grouped = siteHistoryRepository
                .findByBrandAndProviderAccountGroupAndSiteCodeAndGranularityAndBucket(
                        site.getBrand(),
                        accountGroupOf(site),
                        site.getSiteCode(),
                        granularity,
                        bucket
                );

        if (grouped.isPresent()) {
            return grouped;
        }

        return siteHistoryRepository.findByBrandAndSiteCodeAndGranularityAndBucket(
                site.getBrand(),
                site.getSiteCode(),
                granularity,
                bucket
        );
    }

    private AlarmDto toDto(AlarmDocument document) {
        return AlarmDto.builder()
                .id(document.getId())
                .alarmKey(document.getAlarmKey())
                .type(document.getType() != null ? document.getType().name() : null)
                .active(Boolean.TRUE.equals(document.getActive()))
                .brand(document.getBrand() != null ? document.getBrand().name() : null)
                .siteCode(document.getSiteCode())
                .siteName(document.getSiteName())
                .siteStatus(document.getSiteStatus() != null ? document.getSiteStatus().name() : null)
                .governorate(document.getGovernorate())
                .title(document.getTitle())
                .message(document.getMessage())
                .severity(document.getSeverity())
                .metricValue(round2(document.getMetricValue()))
                .thresholdValue(round2(document.getThresholdValue()))
                .unit(document.getUnit())
                .bestSiteName(document.getBestSiteName())
                .bestSiteCode(document.getBestSiteCode())
                .bestValue(round2(document.getBestValue()))
                .monitoredDate(document.getMonitoredDate() != null ? document.getMonitoredDate().toString() : null)
                .lastMonitoringAt(document.getLastMonitoringAt() != null ? document.getLastMonitoringAt().toString() : null)
                .firstDetectedAt(document.getFirstDetectedAt() != null ? document.getFirstDetectedAt().toString() : null)
                .lastDetectedAt(document.getLastDetectedAt() != null ? document.getLastDetectedAt().toString() : null)
                .clearedAt(document.getClearedAt() != null ? document.getClearedAt().toString() : null)
                .build();
    }

    private int compareAlarmDtos(AlarmDto left, AlarmDto right) {
        int severity = Integer.compare(severityRank(right.getSeverity()), severityRank(left.getSeverity()));
        if (severity != 0) {
            return severity;
        }
        double rightMetric = right.getMetricValue() != null ? right.getMetricValue() : 0.0;
        double leftMetric = left.getMetricValue() != null ? left.getMetricValue() : 0.0;
        int metric = Double.compare(rightMetric, leftMetric);
        if (metric != 0) {
            return metric;
        }
        return String.valueOf(left.getSiteName()).compareToIgnoreCase(String.valueOf(right.getSiteName()));
    }

    private int severityRank(String severity) {
        if ("CRITICAL".equalsIgnoreCase(severity)) {
            return 3;
        }
        if ("WARNING".equalsIgnoreCase(severity)) {
            return 2;
        }
        return 1;
    }

    private boolean isHealthy(SiteDocument site) {
        return site != null && site.getStatus() == SiteStatus.HEALTHY;
    }

    private boolean isFaultOrOffline(SiteDocument site) {
        return site != null && (site.getStatus() == SiteStatus.FAULTY || site.getStatus() == SiteStatus.DISCONNECTED);
    }

    private String alarmKey(AlarmType type, SiteDocument site) {
        return type.name()
                + "|"
                + (site.getBrand() != null ? site.getBrand().name() : "UNKNOWN")
                + "|"
                + accountGroupOf(site)
                + "|"
                + String.valueOf(site.getSiteCode());
    }

    private String accountGroupOf(SiteDocument site) {
        if (site != null && site.getProviderAccountGroup() != null && !site.getProviderAccountGroup().isBlank()) {
            return site.getProviderAccountGroup().trim();
        }
        if (site != null && site.getProviderAccountKey() != null && !site.getProviderAccountKey().isBlank()) {
            return site.getProviderAccountKey().trim();
        }
        return "primary";
    }

    private LocalDate toLocalDate(Long collectTime) {
        if (collectTime == null) {
            return null;
        }
        return toInstant(collectTime).atZone(TUNIS_ZONE).toLocalDate();
    }

    private Instant toInstant(Long collectTime) {
        long raw = collectTime;
        long abs = Math.abs(raw);
        long epochMillis;
        if (abs >= 10_000_000_000_000_000L) {
            epochMillis = raw / 1_000_000L;
        } else if (abs >= 100_000_000_000_000L) {
            epochMillis = raw / 1_000L;
        } else if (abs < 10_000_000_000L) {
            epochMillis = raw * 1_000L;
        } else {
            epochMillis = raw;
        }
        return Instant.ofEpochMilli(epochMillis);
    }

    private String safeName(SiteDocument site) {
        if (site == null) {
            return "—";
        }
        if (site.getSiteName() != null && !site.getSiteName().isBlank()) {
            return site.getSiteName();
        }
        return site.getSiteCode();
    }

    private String siteLabel(SiteDocument site) {
        if (site == null) {
            return "—";
        }
        String name = safeName(site);
        String code = site.getSiteCode();
        if (code == null || code.isBlank() || code.equals(name)) {
            return name;
        }
        return name + " - " + code;
    }

    private double nullToZero(Double value) {
        return value != null ? value : 0.0;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private Double round2(Double value) {
        return value == null ? null : round2(value.doubleValue());
    }

    @lombok.Builder
    private record AlarmCandidate(
            AlarmType type,
            SiteDocument site,
            String governorate,
            String title,
            String message,
            String severity,
            Double metricValue,
            Double thresholdValue,
            String unit,
            String bestSiteName,
            String bestSiteCode,
            Double bestValue,
            LocalDate monitoredDate,
            Instant lastMonitoringAt,
            Instant firstSeenAt
    ) {
        String alarmKey() {
            return type.name()
                    + "|"
                    + (site.getBrand() != null ? site.getBrand().name() : "UNKNOWN")
                    + "|"
                    + (site.getProviderAccountGroup() != null && !site.getProviderAccountGroup().isBlank()
                    ? site.getProviderAccountGroup().trim()
                    : site.getProviderAccountKey() != null && !site.getProviderAccountKey().isBlank()
                    ? site.getProviderAccountKey().trim()
                    : "primary")
                    + "|"
                    + String.valueOf(site.getSiteCode());
        }
    }

    private record ProductionDayResult(
            LocalDate date,
            Double realMonitoredKwh
    ) {
    }

    private record PeerYield(
            SiteDocument site,
            LocalDate date,
            double realKwh,
            double specificYield
    ) {
    }

    private record MonitoringPoint(
            Instant collectTime,
            LocalDate date
    ) {
    }
}
