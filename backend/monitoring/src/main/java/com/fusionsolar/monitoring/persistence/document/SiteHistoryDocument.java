package com.fusionsolar.monitoring.persistence.document;

import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.enums.HistoryGranularity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "site_history")
@CompoundIndexes({
        @CompoundIndex(
                name = "idx_brand_group_sitecode_granularity_bucket",
                def = "{'brand': 1, 'providerAccountGroup': 1, 'siteCode': 1, 'granularity': 1, 'bucket': 1}"
        ),
        @CompoundIndex(
                name = "idx_brand_group_granularity_bucket",
                def = "{'brand': 1, 'providerAccountGroup': 1, 'granularity': 1, 'bucket': 1}"
        )
})
public class SiteHistoryDocument {

    @Id
    private String id;

    private Brand brand;

    private String providerAccountKey;

    private String providerAccountGroup;

    private String siteCode;

    private HistoryGranularity granularity;

    private String bucket;

    private List<HistoryPointDto> points;

    private Instant lastSyncAt;

    private Instant createdAt;

    private Instant updatedAt;
}
