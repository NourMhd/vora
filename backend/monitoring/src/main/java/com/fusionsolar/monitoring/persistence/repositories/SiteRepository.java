package com.fusionsolar.monitoring.persistence.repositories;

import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface SiteRepository extends MongoRepository<SiteDocument, String> {

    List<SiteDocument> findAllByBrandOrderBySiteNameAsc(Brand brand);

    List<SiteDocument> findAllByOrderBySiteNameAsc();

    default Optional<SiteDocument> findByBrandAndSiteCode(Brand brand, String siteCode) {
        return pickBest(findAllByBrandAndSiteCode(brand, siteCode));
    }

    List<SiteDocument> findAllByBrandAndSiteCode(Brand brand, String siteCode);

    default Optional<SiteDocument> findByBrandAndProviderAccountGroupAndSiteCode(
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

    List<SiteDocument> findAllByBrandAndProviderAccountGroupAndSiteCode(
            Brand brand,
            String providerAccountGroup,
            String siteCode
    );

    default Optional<SiteDocument> findBySiteCode(String siteCode) {
        return pickBest(findAllBySiteCode(siteCode));
    }

    List<SiteDocument> findAllBySiteCode(String siteCode);

    boolean existsByBrandAndSiteCode(Brand brand, String siteCode);

    long countByBrand(Brand brand);

    Page<SiteDocument> findBySiteNameContainingIgnoreCase(
            String siteName,
            Pageable pageable
    );

    Page<SiteDocument> findBySwapDateIsNotNull(
            Pageable pageable
    );

    Page<SiteDocument> findBySwapDateIsNull(
            Pageable pageable
    );

    Page<SiteDocument> findBySiteNameContainingIgnoreCaseAndSwapDateIsNotNull(
            String siteName,
            Pageable pageable
    );

    Page<SiteDocument> findBySiteNameContainingIgnoreCaseAndSwapDateIsNull(
            String siteName,
            Pageable pageable
    );

    @Query("{ 'active': { '$ne': false } }")
    Page<SiteDocument> findVisible(Pageable pageable);

    @Query("{ 'active': { '$ne': false }, 'siteName': { '$regex': ?0, '$options': 'i' } }")
    Page<SiteDocument> findVisibleBySiteNameContainingIgnoreCase(String siteName, Pageable pageable);

    @Query("{ 'active': { '$ne': false }, 'swapDate': { '$exists': true, '$ne': null } }")
    Page<SiteDocument> findVisibleSwapped(Pageable pageable);

    @Query("{ 'active': { '$ne': false }, '$or': [ { 'swapDate': null }, { 'swapDate': { '$exists': false } } ] }")
    Page<SiteDocument> findVisibleUnswapped(Pageable pageable);

    @Query("{ 'active': { '$ne': false }, 'siteName': { '$regex': ?0, '$options': 'i' }, 'swapDate': { '$exists': true, '$ne': null } }")
    Page<SiteDocument> findVisibleSwappedBySiteNameContainingIgnoreCase(String siteName, Pageable pageable);

    @Query("{ 'active': { '$ne': false }, 'siteName': { '$regex': ?0, '$options': 'i' }, '$or': [ { 'swapDate': null }, { 'swapDate': { '$exists': false } } ] }")
    Page<SiteDocument> findVisibleUnswappedBySiteNameContainingIgnoreCase(String siteName, Pageable pageable);

    private static Optional<SiteDocument> pickBest(List<SiteDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return Optional.empty();
        }

        return documents.stream()
                .filter(Objects::nonNull)
                .max(Comparator
                        .comparingInt((SiteDocument site) -> Boolean.FALSE.equals(site.getActive()) ? 0 : 1)
                        .thenComparingInt(SiteRepository::energyRank)
                        .thenComparing(SiteRepository::lastUsefulInstant));
    }

    private static int energyRank(SiteDocument site) {
        return nonZero(site.getDayEnergyKwh())
                + nonZero(site.getMonthEnergyKwh())
                + nonZero(site.getYearEnergyKwh())
                + nonZero(site.getTotalEnergyKwh());
    }

    private static int nonZero(Double value) {
        return value != null && value > 0 ? 1 : 0;
    }

    private static Instant lastUsefulInstant(SiteDocument site) {
        if (site.getUpdatedAt() != null) {
            return site.getUpdatedAt();
        }
        if (site.getLastSyncAt() != null) {
            return site.getLastSyncAt();
        }
        if (site.getCreatedAt() != null) {
            return site.getCreatedAt();
        }
        return Instant.EPOCH;
    }
}
