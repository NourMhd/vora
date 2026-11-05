package com.fusionsolar.monitoring.persistence.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Compteur de quota persistant. Contrairement a un compteur en memoire, il
 * survit aux redemarrages du service Windows et evite de recommencer a zero.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "provider_rate_budget")
public class ProviderRateBudgetDocument {
    @Id
    private String id;
    private String provider;
    private String accountScope;
    private String endpoint;
    private String period;
    private long used;
    private long limit;
    private Instant updatedAt;

    @Indexed(expireAfter = "0s")
    private Instant expiresAt;
}
