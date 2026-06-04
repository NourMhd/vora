package com.fusionsolar.monitoring.persistence.repositories;

import com.fusionsolar.monitoring.persistence.document.SiteDeviceDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface SiteDeviceRepository extends MongoRepository<SiteDeviceDocument, String> {

    default Optional<SiteDeviceDocument> findByBrandAndSiteCode(Brand brand, String siteCode) {
        return pickBest(findAllByBrandAndSiteCode(brand, siteCode));
    }

    List<SiteDeviceDocument> findAllByBrandAndSiteCode(Brand brand, String siteCode);

    default Optional<SiteDeviceDocument> findByBrandAndProviderAccountGroupAndSiteCode(
            Brand brand,
            String providerAccountGroup,
            String siteCode
    ) {
        if (providerAccountGroup == null || providerAccountGroup.isBlank()) {
            return findByBrandAndSiteCode(brand, siteCode);
        }

        return pickBest(findAllByBrandAndProviderAccountGroupAndSiteCode(
                brand,
                providerAccountGroup,
                siteCode
        ));
    }

    List<SiteDeviceDocument> findAllByBrandAndProviderAccountGroupAndSiteCode(
            Brand brand,
            String providerAccountGroup,
            String siteCode
    );

    default Optional<SiteDeviceDocument> findBySiteCode(String siteCode) {
        return pickBest(findAllBySiteCode(siteCode));
    }

    List<SiteDeviceDocument> findAllBySiteCode(String siteCode);

    List<SiteDeviceDocument> findAllByBrand(Brand brand);

    private static Optional<SiteDeviceDocument> pickBest(List<SiteDeviceDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return Optional.empty();
        }

        return documents.stream()
                .filter(Objects::nonNull)
                .max(Comparator
                        .comparingInt(SiteDeviceRepository::devicesRank)
                        .thenComparing(SiteDeviceRepository::lastUsefulInstant));
    }

    private static int devicesRank(SiteDeviceDocument document) {
        return document.getDevices() != null && !document.getDevices().isEmpty() ? 1 : 0;
    }

    private static Instant lastUsefulInstant(SiteDeviceDocument document) {
        if (document.getUpdatedAt() != null) {
            return document.getUpdatedAt();
        }
        if (document.getLastSyncAt() != null) {
            return document.getLastSyncAt();
        }
        if (document.getCreatedAt() != null) {
            return document.getCreatedAt();
        }
        return Instant.EPOCH;
    }
}
