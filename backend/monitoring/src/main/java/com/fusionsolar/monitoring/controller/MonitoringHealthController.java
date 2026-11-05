package com.fusionsolar.monitoring.controller;

import com.fusionsolar.monitoring.dto.HealthDto;
import com.fusionsolar.monitoring.dto.SyncStatusDto;
import com.fusionsolar.monitoring.persistence.document.SyncRunDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import com.fusionsolar.monitoring.persistence.repositories.SyncRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class MonitoringHealthController {

    private final MongoTemplate mongoTemplate;
    private final SiteRepository siteRepository;
    private final SyncRunRepository syncRunRepository;

    @GetMapping("/health")
    public HealthDto health() {
        Map<String, Object> checks = new LinkedHashMap<>();
        boolean mongoOk = true;

        try {
            mongoTemplate.getDb().runCommand(new org.bson.Document("ping", 1));
        } catch (Exception e) {
            mongoOk = false;
            checks.put("mongoError", e.getMessage());
        }

        checks.put("mongo", mongoOk ? "OK" : "DOWN");

        checks.put("huaweiSites", siteRepository.countByBrand(Brand.HUAWEI));
        checks.put("solisSites", siteRepository.countByBrand(Brand.SOLIS));

        checks.put("lastHuaweiSitesSync", latest(Brand.HUAWEI, "SITES_SUMMARY"));
        checks.put("lastSolisSitesSync", latest(Brand.SOLIS, "SITES_SUMMARY"));

        checks.put("lastHuaweiDevicesSync", latest(Brand.HUAWEI, "HUAWEI_DEVICES"));
        checks.put("lastSolisDevicesSync", latest(Brand.SOLIS, "DEVICES"));

        checks.put("lastSolisCurrentHistorySync", latest(Brand.SOLIS, "SOLIS_HISTORY_CURRENT"));
        checks.put("lastSolisYearlyHistorySync", latest(Brand.SOLIS, "SOLIS_HISTORY_YEARLY"));

        return new HealthDto(mongoOk ? "UP" : "DEGRADED", Instant.now(), checks);
    }

    @GetMapping("/sync/status")
    public List<SyncStatusDto> syncStatus() {
        return List.of(
                toDto(syncRunRepository.findTopByBrandAndTypeOrderByStartedAtDesc(Brand.HUAWEI, "STATES").orElse(null)),
                toDto(syncRunRepository.findTopByBrandAndTypeOrderByStartedAtDesc(Brand.GROWATT, "SITES_SUMMARY").orElse(null)),
                toDto(syncRunRepository.findTopByBrandAndTypeOrderByStartedAtDesc(Brand.HUAWEI, "SITES_SUMMARY").orElse(null)),
                toDto(syncRunRepository.findTopByBrandAndTypeOrderByStartedAtDesc(Brand.SOLIS, "SITES_SUMMARY").orElse(null)),
                toDto(syncRunRepository.findTopByBrandAndTypeOrderByStartedAtDesc(Brand.HUAWEI, "HUAWEI_DEVICES").orElse(null)),
                toDto(syncRunRepository.findTopByBrandAndTypeOrderByStartedAtDesc(Brand.SOLIS, "DEVICES").orElse(null)),
                toDto(syncRunRepository.findTopByBrandAndTypeOrderByStartedAtDesc(Brand.SOLIS, "SOLIS_HISTORY_CURRENT").orElse(null)),
                toDto(syncRunRepository.findTopByBrandAndTypeOrderByStartedAtDesc(Brand.SOLIS, "SOLIS_HISTORY_YEARLY").orElse(null))
        );
    }

    private Object latest(Brand brand, String type) {
        return syncRunRepository.findTopByBrandAndTypeOrderByStartedAtDesc(brand, type)
                .map(this::toDto)
                .orElse(null);
    }

    private SyncStatusDto toDto(SyncRunDocument run) {
        if (run == null) {
            return new SyncStatusDto(null, null, "NEVER_RUN", null, null, null, Map.of());
        }

        return new SyncStatusDto(
                run.getBrand() != null ? run.getBrand().name() : null,
                run.getType(),
                run.getStatus() != null ? run.getStatus().name() : null,
                run.getMessage(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getStats() != null ? run.getStats() : Map.of()
        );
    }
}