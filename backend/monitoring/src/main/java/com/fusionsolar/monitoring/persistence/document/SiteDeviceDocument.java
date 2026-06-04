package com.fusionsolar.monitoring.persistence.document;

import com.fusionsolar.monitoring.dto.DeviceItemDto;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "site_devices")
@CompoundIndex(
        name = "idx_brand_group_sitecode_devices",
        def = "{'brand': 1, 'providerAccountGroup': 1, 'siteCode': 1}"
)
public class SiteDeviceDocument {

    @Id
    private String id;

    private Brand brand;

    private String providerAccountKey;

    private String providerAccountGroup;

    private String siteCode;

    private List<DeviceItemDto> devices;

    private Instant lastSyncAt;

    private Instant createdAt;

    private Instant updatedAt;
}
