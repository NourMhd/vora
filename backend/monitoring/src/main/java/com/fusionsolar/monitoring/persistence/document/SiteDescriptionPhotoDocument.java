package com.fusionsolar.monitoring.persistence.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SiteDescriptionPhotoDocument {
    private String id;
    private String filename;
    private String contentType;
    private String base64;
    private Instant updatedAt;
}
