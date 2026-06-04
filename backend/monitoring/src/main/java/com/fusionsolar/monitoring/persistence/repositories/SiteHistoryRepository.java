package com.fusionsolar.monitoring.persistence.repositories;

import com.fusionsolar.monitoring.persistence.document.SiteHistoryDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.enums.HistoryGranularity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface SiteHistoryRepository extends MongoRepository<SiteHistoryDocument, String> {

    /**
     * Safe lookup: la base peut contenir d'anciens doublons historiques créés avant
     * l'ajout du providerAccountGroup. On retourne alors le document le plus utile
     * au lieu de laisser Spring Data lever IncorrectResultSizeDataAccessException.
     */
    default Optional<SiteHistoryDocument> findByBrandAndSiteCodeAndGranularityAndBucket(
            Brand brand,
            String siteCode,
            HistoryGranularity granularity,
            String bucket
    ) {
        return pickBest(findAllByBrandAndSiteCodeAndGranularityAndBucket(
                brand,
                siteCode,
                granularity,
                bucket
        ));
    }

    List<SiteHistoryDocument> findAllByBrandAndSiteCodeAndGranularityAndBucket(
            Brand brand,
            String siteCode,
            HistoryGranularity granularity,
            String bucket
    );

    default Optional<SiteHistoryDocument> findByBrandAndProviderAccountGroupAndSiteCodeAndGranularityAndBucket(
            Brand brand,
            String providerAccountGroup,
            String siteCode,
            HistoryGranularity granularity,
            String bucket
    ) {
        if (providerAccountGroup == null || providerAccountGroup.isBlank()) {
            return findByBrandAndSiteCodeAndGranularityAndBucket(brand, siteCode, granularity, bucket);
        }

        return pickBest(findAllByBrandAndProviderAccountGroupAndSiteCodeAndGranularityAndBucket(
                brand,
                providerAccountGroup,
                siteCode,
                granularity,
                bucket
        ));
    }

    List<SiteHistoryDocument> findAllByBrandAndProviderAccountGroupAndSiteCodeAndGranularityAndBucket(
            Brand brand,
            String providerAccountGroup,
            String siteCode,
            HistoryGranularity granularity,
            String bucket
    );

    default Optional<SiteHistoryDocument> findBySiteCodeAndGranularityAndBucket(
            String siteCode,
            HistoryGranularity granularity,
            String bucket
    ) {
        return pickBest(findAllBySiteCodeAndGranularityAndBucket(siteCode, granularity, bucket));
    }

    List<SiteHistoryDocument> findAllBySiteCodeAndGranularityAndBucket(
            String siteCode,
            HistoryGranularity granularity,
            String bucket
    );

    boolean existsByBrandAndSiteCodeAndGranularityAndBucket(
            Brand brand,
            String siteCode,
            HistoryGranularity granularity,
            String bucket
    );

    List<SiteHistoryDocument> findAllByBrandAndGranularityAndBucket(
            Brand brand,
            HistoryGranularity granularity,
            String bucket
    );

    List<SiteHistoryDocument> findAllByBrandAndProviderAccountGroupAndGranularityAndBucket(
            Brand brand,
            String providerAccountGroup,
            HistoryGranularity granularity,
            String bucket
    );

    List<SiteHistoryDocument> findAllByBrandAndSiteCodeAndGranularity(
            Brand brand,
            String siteCode,
            HistoryGranularity granularity
    );

    private static Optional<SiteHistoryDocument> pickBest(List<SiteHistoryDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return Optional.empty();
        }

        return documents.stream()
                .filter(Objects::nonNull)
                .max(Comparator
                        .comparingInt(SiteHistoryRepository::pointsRank)
                        .thenComparing(SiteHistoryRepository::lastUsefulInstant));
    }

    private static int pointsRank(SiteHistoryDocument document) {
        return document.getPoints() != null && !document.getPoints().isEmpty() ? 1 : 0;
    }

    private static Instant lastUsefulInstant(SiteHistoryDocument document) {
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
