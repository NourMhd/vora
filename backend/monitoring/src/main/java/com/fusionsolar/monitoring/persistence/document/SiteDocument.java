package com.fusionsolar.monitoring.persistence.document;

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
@Document(collection = "sites")
@CompoundIndexes({
        @CompoundIndex(
                name = "idx_brand_group_sitecode",
                def = "{'brand': 1, 'providerAccountGroup': 1, 'siteCode': 1}"
        ),
        @CompoundIndex(
                name = "idx_brand_group",
                def = "{'brand': 1, 'providerAccountGroup': 1}"
        )
})
public class SiteDocument {

    @Id
    private String id;

    private Brand brand;

    /**
     * Identifiant technique de l'API Northbound qui a découvert / mis à jour le site.
     * Exemple: itscom-api-01, global-engineering-api-01.
     */
    private String providerAccountKey;

    /**
     * Groupe réel FusionSolar / entreprise. Plusieurs API Northbound peuvent partager le même groupe.
     * Exemple: itscom ou global-engineering.
     */
    private String providerAccountGroup;

    @Indexed
    private String siteCode;

    private String providerSiteId;

    private String siteName;

    private SiteStatus status;

    private Double installedPowerKw;

    private String gridConnectionDate;

    private Double dayEnergyKwh;

    private Instant energyLastSyncAt;

    private Instant monthEnergyLastSyncAt;

    private LocalDate energyDate;

    private Double monthEnergyKwh;

    private Double yearEnergyKwh;

    private Double totalEnergyKwh;

    private Instant yearEnergyLastSyncAt;

    /**
     * Date de swap attribuée manuellement depuis l'interface Swaps dates.
     * Stockée en MongoDB comme date ISO, affichée côté UI au format dd/MM/yyyy.
     */
    private LocalDate swapDate;

    private Instant lastSyncAt;

    private Instant createdAt;

    private Instant updatedAt;

    private Boolean active;

    private Integer missingConfirmations;

    private Instant statusLastSyncAt;

    private Instant missingSince;

    private Instant deletedAt;
}
