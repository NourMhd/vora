package com.fusionsolar.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SiteDescriptionPhotoDto {
    private String id;
    private String filename;
    private String dataUrl;
    private String updatedAt;
}
