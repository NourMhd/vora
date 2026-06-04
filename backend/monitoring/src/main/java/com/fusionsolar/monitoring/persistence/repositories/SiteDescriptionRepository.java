package com.fusionsolar.monitoring.persistence.repositories;

import com.fusionsolar.monitoring.persistence.document.SiteDescriptionDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface SiteDescriptionRepository extends MongoRepository<SiteDescriptionDocument, String> {

    default Optional<SiteDescriptionDocument> findByBrandAndSiteCode(Brand brand, String siteCode) {
        return pickBest(findAllByBrandAndSiteCode(brand, siteCode));
    }

    List<SiteDescriptionDocument> findAllByBrandAndSiteCode(Brand brand, String siteCode);

    private static Optional<SiteDescriptionDocument> pickBest(List<SiteDescriptionDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return Optional.empty();
        }

        return documents.stream()
                .filter(Objects::nonNull)
                .max(Comparator.comparing(SiteDescriptionRepository::lastUsefulInstant));
    }

    private static Instant lastUsefulInstant(SiteDescriptionDocument document) {
        if (document.getUpdatedAt() != null) {
            return document.getUpdatedAt();
        }
        if (document.getPhotoUpdatedAt() != null) {
            return document.getPhotoUpdatedAt();
        }
        if (document.getCreatedAt() != null) {
            return document.getCreatedAt();
        }
        return Instant.EPOCH;
    }
}
