package com.fusionsolar.monitoring.persistence.document;

import com.fusionsolar.monitoring.persistence.enums.Brand;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "provider_configs")
public class ProviderConfigDocument {

    @Id
    private String id;

    private Brand brand;

    private Boolean enabled;

    private Boolean syncEnabled;

    private String baseUrl;

    private Map<String, Object> credentials;

    private Instant createdAt;

    private Instant updatedAt;
}