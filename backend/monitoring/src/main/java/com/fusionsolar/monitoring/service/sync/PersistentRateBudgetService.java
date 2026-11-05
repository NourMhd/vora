package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.persistence.document.ProviderRateBudgetDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class PersistentRateBudgetService {

    private final MongoTemplate mongoTemplate;

    /** Reserve atomiquement une unite. Retourne false quand le plafond est atteint. */
    public boolean tryConsumeDaily(
            String provider,
            String accountScope,
            String endpoint,
            int limit,
            ZoneId zone
    ) {
        int safeLimit = Math.max(1, limit);
        ZoneId safeZone = zone != null ? zone : ZoneId.systemDefault();
        LocalDate day = LocalDate.now(safeZone);
        String rawKey = provider + "|" + accountScope + "|" + endpoint + "|" + day;
        String id = sha256(rawKey);
        Instant now = Instant.now();
        Instant expiresAt = day.plusDays(8).atStartOfDay(safeZone).toInstant();

        Query reserveExisting = new Query(new Criteria().andOperator(
                Criteria.where("_id").is(id),
                Criteria.where("used").lt(safeLimit)
        ));
        Update increment = new Update()
                .inc("used", 1L)
                .set("limit", safeLimit)
                .set("updatedAt", now)
                .set("expiresAt", expiresAt);

        if (mongoTemplate.updateFirst(
                reserveExisting,
                increment,
                ProviderRateBudgetDocument.class
        ).getModifiedCount() == 1L) {
            return true;
        }

        if (mongoTemplate.exists(Query.query(Criteria.where("_id").is(id)), ProviderRateBudgetDocument.class)) {
            return false;
        }

        try {
            mongoTemplate.insert(ProviderRateBudgetDocument.builder()
                    .id(id)
                    .provider(provider)
                    .accountScope(accountScope)
                    .endpoint(endpoint)
                    .period(day.toString())
                    .used(1L)
                    .limit(safeLimit)
                    .updatedAt(now)
                    .expiresAt(expiresAt)
                    .build());
            return true;
        } catch (DuplicateKeyException race) {
            return mongoTemplate.updateFirst(
                    reserveExisting,
                    increment,
                    ProviderRateBudgetDocument.class
            ).getModifiedCount() == 1L;
        }
    }

    public long usedToday(String provider, String accountScope, String endpoint, ZoneId zone) {
        ZoneId safeZone = zone != null ? zone : ZoneId.systemDefault();
        String rawKey = provider + "|" + accountScope + "|" + endpoint + "|" + LocalDate.now(safeZone);
        ProviderRateBudgetDocument document = mongoTemplate.findById(
                sha256(rawKey),
                ProviderRateBudgetDocument.class
        );
        return document == null ? 0L : document.getUsed();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception e) {
            throw new IllegalStateException("Unable to build persistent rate-budget key", e);
        }
    }
}
