package com.fusionsolar.monitoring.persistence.document;

import com.fusionsolar.monitoring.persistence.enums.AlarmType;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.enums.SiteStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "site_alarms")
@CompoundIndexes({
        @CompoundIndex(name = "idx_alarm_active_type", def = "{'active': 1, 'type': 1}"),
        @CompoundIndex(name = "idx_alarm_key_active", def = "{'alarmKey': 1, 'active': 1}"),
        @CompoundIndex(name = "idx_alarm_cleared", def = "{'active': 1, 'clearedAt': -1}")
})
public class AlarmDocument {

    @Id
    private String id;

    @Indexed
    private String alarmKey;

    private AlarmType type;
    private Boolean active;

    private Brand brand;
    private String providerAccountGroup;
    private String siteCode;
    private String siteName;
    private SiteStatus siteStatus;
    private String governorate;

    private String title;
    private String message;
    private String severity;

    private Double metricValue;
    private Double thresholdValue;
    private String unit;

    private String bestSiteName;
    private String bestSiteCode;
    private Double bestValue;

    private LocalDate monitoredDate;
    private Instant lastMonitoringAt;

    private Instant firstDetectedAt;
    private Instant lastDetectedAt;
    private Instant clearedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
