package com.fusionsolar.monitoring.persistence.document;

import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.enums.SyncStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "sync_runs")
@CompoundIndex(name = "idx_sync_brand_type_started", def = "{'brand': 1, 'type': 1, 'startedAt': -1}")
public class SyncRunDocument {

    @Id
    private String id;

    private Brand brand;

    @Indexed
    private String type;

    private SyncStatus status;

    private String message;

    private Map<String, Object> stats;

    @Indexed
    private Instant startedAt;

    private Instant finishedAt;
}
