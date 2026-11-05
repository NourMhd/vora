package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Date;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Durable deduplicated demand queue; no provider calls on HTTP request threads. */
@Service @RequiredArgsConstructor @Slf4j
public class HistoryDemandQueue {
    private static final String COLLECTION = "history_fetch_requests";
    private final MongoTemplate mongo;
    private final SiteRepository sites;
    private final HuaweiHistorySyncService huawei;
    private final ObjectProvider<SolisHistorySyncService> solis;
    private final ObjectProvider<GrowattHistorySyncService> growatt;

    public void enqueue(SiteDocument site, String type, String bucket) {
        String raw = site.getId() + "|" + type + "|" + bucket;
        String id = UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)).toString();
        try {
            mongo.upsert(Query.query(Criteria.where("_id").is(id)), new Update()
                    .setOnInsert("siteId", site.getId()).setOnInsert("brand", site.getBrand().name())
                    .setOnInsert("type", type).setOnInsert("bucket", bucket)
                    .setOnInsert("status", "PENDING").setOnInsert("nextAt", new Date())
                    .setOnInsert("attempts", 0), COLLECTION);
            mongo.updateFirst(Query.query(Criteria.where("_id").is(id).and("status").is("DONE")
                            .and("nextAt").lte(new Date())),
                    new Update().set("status", "PENDING").set("nextAt", new Date()), COLLECTION);
        } catch (org.springframework.dao.DuplicateKeyException concurrentEnqueue) { }
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 30000, scheduler = "demandHistoryScheduler")
    public void huaweiDemand() { run("HUAWEI"); }
    @Scheduled(fixedDelay = 1000, initialDelay = 30000, scheduler = "demandHistoryScheduler")
    public void solisDemand() { run("SOLIS"); }
    @Scheduled(fixedDelay = 1000, initialDelay = 30000, scheduler = "demandHistoryScheduler")
    public void growattDemand() { run("GROWATT"); }

    private void run(String brand) {
        Date now = new Date();
        Query due = Query.query(new Criteria().andOperator(Criteria.where("brand").is(brand),
                Criteria.where("nextAt").lte(now), Criteria.where("status").ne("DONE")));
        due.with(Sort.by(Sort.Direction.ASC, "nextAt"));
        String lease = UUID.randomUUID().toString();
        Document job = mongo.findAndModify(due, new Update().set("status", "RUNNING")
                .set("lease", lease).set("nextAt", Date.from(Instant.now().plusSeconds(900)))
                .inc("attempts", 1), FindAndModifyOptions.options().returnNew(true), Document.class, COLLECTION);
        if (job == null) return;
        Query owned = Query.query(Criteria.where("_id").is(job.getString("_id")).and("lease").is(lease));
        try {
            SiteDocument site = sites.findById(job.getString("siteId")).orElse(null);
            if (site == null || Boolean.FALSE.equals(site.getActive())) { mongo.remove(owned, COLLECTION); return; }
            var points = switch (site.getBrand()) {
                case HUAWEI -> huawei.getHistory(site, job.getString("type"), job.getString("bucket"), true);
                case SOLIS -> solis.getObject().getHistory(site, job.getString("type"), job.getString("bucket"), true);
                case GROWATT -> growatt.getObject().getHistory(site, job.getString("type"), job.getString("bucket"), true);
                default -> throw new IllegalArgumentException("Unsupported history brand");
            };
            // Keep a retry gate; repeated page views cannot immediately requeue empty responses.
            long delay = points == null || points.isEmpty() ? 86400 : 21600;
            mongo.updateFirst(owned, new Update().set("status", points == null || points.isEmpty() ? "EMPTY" : "DONE")
                    .set("nextAt", Date.from(Instant.now().plusSeconds(delay))).unset("lease"), COLLECTION);
        } catch (Exception error) {
            log.warn("Demand history deferred brand={} cause={}", brand, error.getMessage());
            long retrySeconds = Math.min(3600, 60L << Math.min(6, job.getInteger("attempts", 1)));
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof com.fusionsolar.monitoring.service.huawei.HuaweiApiDeferredException deferred) {
                    retrySeconds = Math.max(1, (deferred.getRetryAfterMs() + 999) / 1000);
                    break;
                }
            }
            mongo.updateFirst(owned, new Update().set("status", "FAILED")
                    .set("nextAt", Date.from(Instant.now().plusSeconds(retrySeconds))).unset("lease"), COLLECTION);
        }
    }
}
