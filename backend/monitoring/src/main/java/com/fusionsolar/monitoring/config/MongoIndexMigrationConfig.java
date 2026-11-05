package com.fusionsolar.monitoring.config;

import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Duration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class MongoIndexMigrationConfig {

    /**
     * Version safe: on ne supprime aucun document MongoDB.
     * On supprime uniquement les index uniques qui peuvent bloquer le démarrage
     * quand la base contient déjà des doublons historiques.
     */
    private static final Set<String> INDEXES_TO_DROP = Set.of(
            "uk_brand_sitecode",
            "uk_brand_sitecode_granularity_bucket",
            "uk_brand_sitecode_devices",
            "uk_brand_account_sitecode",
            "uk_brand_account_sitecode_granularity_bucket",
            "uk_brand_account_sitecode_devices",
            "uk_brand_group_sitecode",
            "uk_brand_group_sitecode_granularity_bucket",
            "uk_brand_group_sitecode_devices",
            "idx_brand_account_granularity_bucket"
    );

    private final MongoTemplate mongoTemplate;

    @Bean
    public ApplicationRunner migrateMongoTenantIsolation() {
        return args -> {
            dropConflictingIndexes("sites");
            dropConflictingIndexes("site_history");
            dropConflictingIndexes("site_devices");
            dropConflictingIndexes("site_descriptions");

            migrateLegacyHistoryAndDevicesTenantFieldsSafely();
            ensureSafeNonUniqueIndexes();
        };
    }

    private void dropConflictingIndexes(String collectionName) {
        if (!mongoTemplate.collectionExists(collectionName)) {
            return;
        }

        List<IndexInfo> indexes = mongoTemplate.indexOps(collectionName).getIndexInfo();
        for (IndexInfo index : indexes) {
            String indexName = index.getName();
            if (!INDEXES_TO_DROP.contains(indexName)) {
                continue;
            }

            try {
                mongoTemplate.indexOps(collectionName).dropIndex(indexName);
                log.warn("Dropped Mongo index {}.{} only. No document was deleted.", collectionName, indexName);
            } catch (Exception e) {
                log.warn("Unable to drop Mongo index {}.{}: {}", collectionName, indexName, e.getMessage());
            }
        }
    }

    /**
     * Migration douce sans suppression:
     * les anciens documents site_history/site_devices ne portaient pas providerAccountKey/providerAccountGroup.
     * Quand un siteCode est unique côté sites, on rattache ses historiques/appareils au même group.
     * Si un cas est ambigu ou provoque une erreur Mongo, on ignore ce site pour ne pas bloquer le démarrage.
     */
    private void migrateLegacyHistoryAndDevicesTenantFieldsSafely() {
        if (!mongoTemplate.collectionExists("sites")) {
            return;
        }

        Map<String, TenantIdentity> uniqueByBrandAndSiteCode = new HashMap<>();
        Set<String> ambiguousKeys = new java.util.HashSet<>();

        for (SiteDocument site : mongoTemplate.findAll(SiteDocument.class, "sites")) {
            if (site == null || isBlank(site.getSiteCode()) || site.getBrand() == null) {
                continue;
            }

            String accountKey = firstNonBlank(site.getProviderAccountKey(), site.getProviderAccountGroup(), "primary");
            String group = firstNonBlank(site.getProviderAccountGroup(), site.getProviderAccountKey(), "primary");
            String key = site.getBrand().name() + "|" + site.getSiteCode().trim();
            TenantIdentity incoming = new TenantIdentity(accountKey, group);
            TenantIdentity existing = uniqueByBrandAndSiteCode.putIfAbsent(key, incoming);

            if (existing != null && !existing.group().equals(incoming.group())) {
                ambiguousKeys.add(key);
            }
        }

        ambiguousKeys.forEach(uniqueByBrandAndSiteCode::remove);

        for (Map.Entry<String, TenantIdentity> entry : uniqueByBrandAndSiteCode.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            if (parts.length != 2) {
                continue;
            }

            migrateCollectionTenantFieldsSafely("site_history", parts[0], parts[1], entry.getValue());
            migrateCollectionTenantFieldsSafely("site_devices", parts[0], parts[1], entry.getValue());
        }
    }

    private void migrateCollectionTenantFieldsSafely(
            String collectionName,
            String brand,
            String siteCode,
            TenantIdentity identity
    ) {
        if (!mongoTemplate.collectionExists(collectionName)) {
            return;
        }

        Query query = new Query(new Criteria().andOperator(
                Criteria.where("brand").is(brand),
                Criteria.where("siteCode").is(siteCode),
                new Criteria().orOperator(
                        Criteria.where("providerAccountKey").exists(false),
                        Criteria.where("providerAccountKey").is(null),
                        Criteria.where("providerAccountKey").is("")
                )
        ));

        Update update = new Update()
                .set("providerAccountKey", identity.accountKey())
                .set("providerAccountGroup", identity.group());

        try {
            long modified = mongoTemplate.updateMulti(query, update, collectionName).getModifiedCount();
            if (modified > 0) {
                log.info(
                        "Migrated {} legacy document(s) in {} for {} {} to group {}. No document was deleted.",
                        modified,
                        collectionName,
                        brand,
                        siteCode,
                        identity.group()
                );
            }
        } catch (Exception e) {
            log.warn(
                    "Skipped legacy tenant migration for {} {} {} because Mongo refused the update: {}. No document was deleted.",
                    collectionName,
                    brand,
                    siteCode,
                    e.getMessage()
            );
        }
    }

    private void ensureSafeNonUniqueIndexes() {
        ensureNonUniqueIndex(
                "sites",
                "idx_brand_group_sitecode",
                new Document("brand", 1)
                        .append("providerAccountGroup", 1)
                        .append("siteCode", 1)
        );

        ensureNonUniqueIndex(
                "site_history",
                "idx_brand_group_sitecode_granularity_bucket",
                new Document("brand", 1)
                        .append("providerAccountGroup", 1)
                        .append("siteCode", 1)
                        .append("granularity", 1)
                        .append("bucket", 1)
        );

        ensureNonUniqueIndex(
                "site_history",
                "idx_brand_group_granularity_bucket",
                new Document("brand", 1)
                        .append("providerAccountGroup", 1)
                        .append("granularity", 1)
                        .append("bucket", 1)
        );

        ensureNonUniqueIndex(
                "site_devices",
                "idx_brand_group_sitecode_devices",
                new Document("brand", 1)
                        .append("providerAccountGroup", 1)
                        .append("siteCode", 1)
        );

        ensureNonUniqueIndex(
                "site_descriptions",
                "idx_brand_sitecode_description",
                new Document("brand", 1)
                        .append("siteCode", 1)
        );

        try {
            mongoTemplate.indexOps("provider_rate_budget").ensureIndex(
                    new Index()
                            .on("expiresAt", org.springframework.data.domain.Sort.Direction.ASC)
                            .expire(Duration.ZERO)
                            .named("ttl_provider_rate_budget")
            );
        } catch (Exception e) {
            log.warn("Unable to ensure provider rate-budget TTL index: {}", e.getMessage());
        }
    }

    private void ensureNonUniqueIndex(String collectionName, String indexName, Document definition) {
        try {
            mongoTemplate.indexOps(collectionName).ensureIndex(
                    new CompoundIndexDefinition(definition).named(indexName)
            );
        } catch (Exception e) {
            log.warn("Unable to ensure Mongo index {}.{}: {}", collectionName, indexName, e.getMessage());
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "primary";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record TenantIdentity(String accountKey, String group) {
    }
}
