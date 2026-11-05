package com.fusionsolar.monitoring.planning;

import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.enums.SiteStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.event.AfterSaveEvent;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Durable journal of observations, not a claim to know unobserved provider transitions.
 * All writes are local Mongo writes; no provider API is called here.
 * Run one backend writer (as with the existing provider rate guards).
 */
@Service
public class SiteTrackingService {
    private static final Logger log = LoggerFactory.getLogger(SiteTrackingService.class);
    public static final Duration FRESHNESS = Duration.ofMinutes(15);
    public static final Duration WINDOW = Duration.ofHours(72);
    public static final ZoneId TUNIS = ZoneId.of("Africa/Tunis");
    private final MongoTemplate mongo;
    private boolean initialized;
    private volatile Instant pendingFailureAt;
    public SiteTrackingService(MongoTemplate mongo) { this.mongo = mongo; }

    @Document("field_tracking_meta")
    public static class Meta {
        @Id public String id = "activation";
        public Instant activatedAt, lastScanAt, failureFrom, recoveredAt;
        public boolean initialized;
        public String error;
    }
    @Document("field_site_observations")
    public static class Observation {
        @Id public String id;
        public SiteDocument site;
        public Instant detectedAt, capturedAt;
        public boolean baseline;
        public long revision;
    }
    @Document("field_site_events")
    public static class Change {
        @Id public String id;
        public String siteId, kind, oldStatus, newStatus, source, owner, taskId;
        public SiteDocument site;
        public Instant at, recordedAt, gapStart;
    }
    public static class Reference {
        public String siteId, brand, siteCode, siteName;
        public SiteStatus status;
        public Instant observedAt, capturedAt;
        public boolean active;
    }
    public static class MissionSite {
        public Reference assigned, atCompletion;
        public Instant completionRecordedAt;
        public LocalDate declaredCompletionDate;
        public boolean retrospective, lateReference;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() { scan(); }

    @EventListener
    public void afterSiteSave(AfterSaveEvent<?> event) {
        if (event.getSource() instanceof SiteDocument site) captureSafely(site, "SITE_SAVE");
    }

    /** Partial Mongo writes don't emit AfterSaveEvent. Called after their successful write. */
    public void captureById(String id, String source) {
        try {
            SiteDocument site = mongo.findById(id, SiteDocument.class);
            if (site != null) captureSafely(site, source);
        } catch (Exception ex) { failed(ex); }
    }

    private void captureSafely(SiteDocument site, String source) {
        try { capture(site, source); }
        catch (Exception ex) { failed(ex); }
    }
    private void failed(Exception ex) {
        if (pendingFailureAt == null) pendingFailureAt = Instant.now();
        log.error("Site tracking write failed; next local reconciliation will retry: {}", ex.getMessage());
        try {
            Meta meta = mongo.findById("activation", Meta.class);
            if (meta != null) {
                if (meta.failureFrom == null || pendingFailureAt.isBefore(meta.failureFrom)) meta.failureFrom = pendingFailureAt;
                meta.recoveredAt = null; meta.error = "Journalisation incomplète : consulter les logs backend";
                mongo.save(meta);
            }
        } catch (Exception ignored) { /* The failure is also retained in memory until local recovery. */ }
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 5000)
    public synchronized void scan() {
        try {
            ensureBaseline();
            List<SiteDocument> inventory = mongo.findAll(SiteDocument.class);
            Set<String> present = new HashSet<>();
            for (SiteDocument site : inventory) { present.add(site.getId()); capture(site, "LOCAL_RECONCILIATION"); }
            for (Observation observation : mongo.findAll(Observation.class)) if (active(observation.site) && !present.contains(observation.id)) {
                observation.site.setActive(false);
                capture(observation.site, "LOCAL_DB_REMOVAL");
            }
            Meta meta = mongo.findById("activation", Meta.class);
            meta.lastScanAt = Instant.now();
            if (pendingFailureAt != null && (meta.failureFrom == null || pendingFailureAt.isBefore(meta.failureFrom))) meta.failureFrom = pendingFailureAt;
            if (meta.failureFrom != null && meta.recoveredAt == null) {
                meta.recoveredAt = meta.lastScanAt;
                meta.error = "Une interruption du journal a été détectée ; les bilans concernés sont non vérifiables";
            }
            // Keep a failure visible: a later scan cannot reconstruct intermediate states lost during an outage.
            mongo.save(meta); pendingFailureAt = null;
        } catch (Exception ex) { failed(ex); }
    }

    private synchronized void ensureBaseline() {
        if (initialized) return;
        Meta meta = mongo.findById("activation", Meta.class);
        if (meta == null) { meta = new Meta(); meta.activatedAt = Instant.now(); mongo.insert(meta); }
        if (!meta.initialized) {
            for (SiteDocument site : mongo.findAll(SiteDocument.class)) {
                if (site.getId() == null || mongo.findById(site.getId(), Observation.class) != null) continue;
                Observation observation = new Observation();
                observation.id = site.getId(); observation.site = site; observation.baseline = true;
                observation.detectedAt = meta.activatedAt; observation.capturedAt = Instant.now();
                emit(observation, "BASELINE", null, state(site), meta.activatedAt, null, "INITIAL_REFERENCE");
                mongo.save(observation);
            }
            meta.initialized = true; mongo.save(meta);
        }
        mongo.indexOps(Change.class).createIndex(new org.springframework.data.mongodb.core.index.Index().on("siteId", Sort.Direction.ASC).on("at", Sort.Direction.ASC));
        mongo.indexOps(Change.class).createIndex(new org.springframework.data.mongodb.core.index.Index().on("kind", Sort.Direction.ASC).on("at", Sort.Direction.ASC));
        initialized = true;
    }

    public synchronized void capture(SiteDocument site, String source) {
        if (site == null || site.getId() == null || site.getBrand() == null) return;
        ensureBaseline();
        Observation old = mongo.findById(site.getId(), Observation.class);
        Instant now = Instant.now();
        if (old == null) {
            Observation next = new Observation(); next.id = site.getId(); next.site = site;
            next.detectedAt = now; next.capturedAt = now;
            emit(next, "ADDED", null, state(site), now, null, source);
            mongo.save(next); return;
        }
        Observation next = new Observation(); next.id = old.id; next.detectedAt = old.detectedAt;
        next.baseline = old.baseline; next.revision = old.revision + 1; next.site = site; next.capturedAt = now;
        SiteDocument previous = old.site;
        Instant before = previous.getStatusLastSyncAt(), observed = site.getStatusLastSyncAt();
        boolean newer = observed != null && (before == null || observed.isAfter(before));
        if (newer) {
            if (before != null && Duration.between(before, observed).compareTo(FRESHNESS) > 0)
                emit(next, "GAP", state(previous), state(site), observed, before, source);
            if (!Objects.equals(previous.getStatus(), site.getStatus()))
                emit(next, "STATE_CHANGED", state(previous), state(site), observed, null, source);
        } else {
            // Do not turn old inventory data into a new state observation.
            site.setStatus(previous.getStatus()); site.setStatusLastSyncAt(before);
        }
        if (active(previous) != active(site))
            emit(next, active(site) ? "REACTIVATED" : "LOCAL_DB_REMOVAL".equals(source) ? "LOCAL_REMOVED" : "REMOVED", state(previous), state(site), now, null, source);
        if (!Objects.equals(previous.getSwapDate(), site.getSwapDate()) || !Objects.equals(previous.getGridConnectionDate(), site.getGridConnectionDate()))
            emit(next, "DATES_UPDATED", state(previous), state(site), now, null, source);
        // No permanent heartbeat rows: only transitions/gaps are journaled, latest observation remains durable.
        mongo.save(next);
    }

    private void emit(Observation o, String kind, String before, String after, Instant at, Instant gapStart, String source) {
        Change c = new Change(); c.id = o.id + "/" + o.revision + "/" + kind;
        c.siteId = o.id; c.site = o.site; c.kind = kind; c.oldStatus = before; c.newStatus = after;
        c.at = at; c.gapStart = gapStart; c.recordedAt = Instant.now(); c.source = source;
        try { mongo.insert(c); } catch (org.springframework.dao.DuplicateKeyException ignored) { /* Idempotent retry. */ }
    }
    public static boolean active(SiteDocument s) { return !Boolean.FALSE.equals(s.getActive()); }
    public static String state(SiteDocument s) { return s.getStatus() == null ? "UNKNOWN" : s.getStatus().name(); }
    public static boolean fresh(Instant observation, Instant reference) {
        return observation != null && !observation.isAfter(reference) && !observation.isBefore(reference.minus(FRESHNESS));
    }
    public Reference reference(SiteDocument s, Instant now) {
        Reference r = new Reference(); r.siteId = s.getId(); r.brand = s.getBrand().name();
        r.siteCode = s.getSiteCode(); r.siteName = s.getSiteName(); r.status = s.getStatus();
        r.observedAt = s.getStatusLastSyncAt(); r.capturedAt = now; r.active = active(s); return r;
    }

    private Reference manualReference(PlanningController.TaskSite site, Instant now) {
        Reference r = new Reference(); r.siteId = site.siteCode(); r.brand = PlanningSiteService.MANUAL;
        r.siteCode = site.siteCode(); r.siteName = site.siteName(); r.active = true; r.capturedAt = now;
        return r; // No provider state or observation timestamp exists.
    }

    /** Called before the task is saved. Existing baselines never move on routine edits. */
    public void prepare(PlanningController.Task task, java.util.function.Function<PlanningController.TaskSite, SiteDocument> resolver) {
        Instant now = Instant.now();
        Map<String, MissionSite> previous = new HashMap<>();
        if (task.siteFollowups != null) for (MissionSite m : task.siteFollowups)
            previous.put(m.assigned.brand + ":" + m.assigned.siteCode, m);
        List<MissionSite> next = new ArrayList<>();
        for (PlanningController.TaskSite item : task.sites) {
            MissionSite m = previous.get(item.brand() + ":" + item.siteCode());
            Reference current = PlanningSiteService.MANUAL.equals(item.brand())
                    ? manualReference(item, now) : reference(resolver.apply(item), now);
            if (m == null) { m = new MissionSite(); m.assigned = current; m.lateReference = task.id != null && !task.trackingInitialized; }
            if ("Réalisé".equals(task.status) || "Partiellement réalisé".equals(task.status)) {
                if (m.atCompletion == null || !Objects.equals(m.declaredCompletionDate, task.completedDate)) {
                    m.atCompletion = current; m.completionRecordedAt = now;
                    m.declaredCompletionDate = task.completedDate;
                    m.retrospective = task.completedDate == null || !task.completedDate.equals(LocalDate.now(TUNIS));
                }
            } else { m.atCompletion = null; m.completionRecordedAt = null; m.declaredCompletionDate = null; }
            next.add(m);
        }
        if (task.archivedSiteFollowups == null) task.archivedSiteFollowups = new ArrayList<>();
        for (MissionSite old : previous.values()) if (next.stream().noneMatch(v -> v == old)) task.archivedSiteFollowups.add(old);
        task.siteFollowups = next;
        task.trackingInitialized = true;
    }

    public void recordMission(PlanningController.Task task) {
        try {
            if (task.siteFollowups == null) return;
            for (MissionSite m : task.siteFollowups) {
                missionEvent(task, m, "MISSION_ASSIGNED", m.assigned.capturedAt);
                if (m.atCompletion != null) missionEvent(task, m, "MISSION_COMPLETED", m.completionRecordedAt);
            }
        } catch (Exception ex) { failed(ex); }
    }
    private void missionEvent(PlanningController.Task task, MissionSite m, String kind, Instant at) {
        // Manual interventions are audited in field_tasks.history, without monitoring events.
        if (PlanningSiteService.MANUAL.equals(m.assigned.brand)) return;
        SiteDocument site = mongo.findById(m.assigned.siteId, SiteDocument.class);
        if (site == null) return;
        Change c = new Change(); c.id = "mission/" + task.id + "/" + m.assigned.siteId + "/" + kind + "/" + at;
        c.siteId = m.assigned.siteId; c.kind = kind; c.site = site; c.owner = task.owner; c.taskId = task.id;
        c.oldStatus = m.assigned.status == null ? "UNKNOWN" : m.assigned.status.name();
        c.newStatus = m.atCompletion == null || m.atCompletion.status == null ? c.oldStatus : m.atCompletion.status.name();
        c.at = at; c.recordedAt = Instant.now(); c.source = "FIELD_MISSION";
        try { mongo.insert(c); } catch (org.springframework.dao.DuplicateKeyException ignored) { }
    }

    public List<Change> changes(String siteId, Instant from) {
        return mongo.find(Query.query(Criteria.where("siteId").is(siteId).and("at").gte(from))
            .with(Sort.by(Sort.Direction.ASC, "at")), Change.class);
    }
    public static String assess(MissionSite m, Reference current, List<Change> changes, Instant now, String type) {
        if (m == null || m.assigned == null) return "Non vérifiable · mission antérieure au suivi";
        if (m.atCompletion == null) return "En attente d’intervention";
        if (type == null || !Set.of("Monitoring", "Maintenance / installation modems", "Dépannage", "Mise en service").contains(type))
            return "À valider selon l’objectif";
        if (m.retrospective || m.lateReference || !fresh(m.assigned.observedAt, m.assigned.capturedAt)
            || m.assigned.status == null || m.assigned.status == SiteStatus.UNKNOWN)
            return "Non vérifiable · référence insuffisante";
        if (current == null || !current.active || !fresh(current.observedAt, now) || current.status == null || current.status == SiteStatus.UNKNOWN)
            return "Non vérifiable · données non récentes";
        boolean healthySeen = (m.atCompletion.status == SiteStatus.HEALTHY && fresh(m.atCompletion.observedAt, m.completionRecordedAt)) || changes.stream()
            .anyMatch(c -> "STATE_CHANGED".equals(c.kind) && "HEALTHY".equals(c.newStatus));
        if (current.status != SiteStatus.HEALTHY) {
            if (healthySeen) return "Rechute observée";
            return now.isBefore(m.completionRecordedAt.plus(WINDOW)) ? "Observation en cours" : "Sans amélioration observée";
        }
        if (changes.stream().anyMatch(c -> "GAP".equals(c.kind))) return "Connecté · suivi incomplet";
        if (!fresh(m.atCompletion.observedAt, m.completionRecordedAt)) return "Connecté · référence de clôture insuffisante";
        if (now.isBefore(m.completionRecordedAt.plus(WINDOW))) return "Connecté · contrôle 72 h en cours";
        if (changes.stream().anyMatch(c -> "STATE_CHANGED".equals(c.kind) && !"HEALTHY".equals(c.newStatus)))
            return "Connecté · anomalies observées depuis la clôture";
        return m.assigned.status == SiteStatus.HEALTHY ? "Maintien connecté aux observations" : "Rétabli · contrôle 72 h observé";
    }
}
