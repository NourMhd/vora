package com.fusionsolar.monitoring.controller;

import com.fusionsolar.monitoring.dto.SiteDescriptionDto;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.service.SiteDescriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/brands/{brand}/sites/{siteCode}/description")
public class SiteDescriptionController {

    private final SiteDescriptionService siteDescriptionService;

    @GetMapping
    public SiteDescriptionDto getDescription(
            @PathVariable Brand brand,
            @PathVariable String siteCode
    ) {
        return siteDescriptionService.getDescription(brand, siteCode);
    }

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SiteDescriptionDto saveDescription(
            @PathVariable Brand brand,
            @PathVariable String siteCode,
            @RequestParam(required = false) String note,
            @RequestParam(required = false) String orientationDeg,
            @RequestParam(required = false) MultipartFile photo,
            @RequestParam(required = false) List<MultipartFile> photos,
            @RequestParam(required = false) List<String> retainedPhotoIds,
            @RequestParam(required = false, defaultValue = "false") Boolean removePhoto
    ) {
        return siteDescriptionService.saveDescription(
                brand,
                siteCode,
                note,
                orientationDeg,
                photo,
                photos,
                retainedPhotoIds,
                removePhoto
        );
    }
}
