package com.fusionsolar.monitoring.persistence.document;

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
@Document(collection = "site_descriptions")
@CompoundIndex(
        name = "idx_brand_sitecode_description",
        def = "{'brand': 1, 'siteCode': 1}"
)
public class SiteDescriptionDocument {

    @Id
    private String id;

    private Brand brand;

    private String siteCode;

    private String siteName;

    private String note;

    /**
     * Orientation du champ PV en degrés. Exemple: 180 = Sud.
     */
    private Double orientationDeg;

    private String photoFilename;

    private String photoContentType;

    /**
     * Image stockée en base64 pour rester simple avec MongoDB local.
     */
    private String photoBase64;

    private Instant photoUpdatedAt;

    /**
     * Photos multiples du site. Les anciens champs photo* restent conservés pour compatibilité
     * et sont migrés automatiquement en première photo lors de la lecture/sauvegarde.
     */
    private List<SiteDescriptionPhotoDocument> photos;

    private Instant createdAt;

    private Instant updatedAt;
}
