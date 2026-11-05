package com.fusionsolar.monitoring.planning;

import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import com.fusionsolar.monitoring.service.CurrentSiteAccessService;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import static com.fusionsolar.monitoring.planning.SiteTrackingService.*;

@RestController
@RequestMapping("/api/planning/tracking")
public class PlanningTrackingController {
    private final MongoTemplate mongo;
    private final SiteRepository sites;
    private final CurrentSiteAccessService access;
    private final SiteTrackingService tracking;
    @org.springframework.beans.factory.annotation.Autowired
    private PlanningSiteService planningSites;
    public PlanningTrackingController(MongoTemplate mongo, SiteRepository sites, CurrentSiteAccessService access, SiteTrackingService tracking) {
        this.mongo = mongo; this.sites = sites; this.access = access; this.tracking = tracking;
    }
    // Reuse exactly the tenant policy, including provider group, for historical/removed sites.
    private boolean visible(SiteDocument site) {
        if (site == null) return false;
        SiteDocument copy = new SiteDocument(); copy.setBrand(site.getBrand()); copy.setSiteCode(site.getSiteCode());
        copy.setSiteName(site.getSiteName()); copy.setProviderSiteId(site.getProviderSiteId());
        copy.setProviderAccountGroup(site.getProviderAccountGroup()); copy.setActive(true);
        return access.canAccess(copy);
    }
    private String owner() { return SecurityContextHolder.getContext().getAuthentication().getName(); }
    private LocalDate date(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return LocalDate.parse(value); } catch (Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date invalide"); }
    }
    public record SiteView(String id, String brand, String siteCode, String siteName, String commissionedAt,
        LocalDate swapDate, String status, Instant observedAt, boolean active, Instant detectedAt, boolean baseline) {}
    private SiteView siteView(Observation o) {
        SiteDocument s = o.site;
        return new SiteView(o.id, s.getBrand().name(), s.getSiteCode(), s.getSiteName(), s.getGridConnectionDate(),
            s.getSwapDate(), state(s), s.getStatusLastSyncAt(), active(s), o.detectedAt, o.baseline);
    }
    public record EventView(String id, String siteId, String siteName, String siteCode, String brand, String kind,
        String before, String after, Instant at, Instant recordedAt, Instant gapStart, String commissionedAt, LocalDate swapDate, String taskId) {}
    private EventView eventView(Change c) {
        return new EventView(c.id, c.siteId, c.site.getSiteName(), c.site.getSiteCode(), c.site.getBrand().name(), c.kind,
            c.oldStatus, c.newStatus, c.at, c.recordedAt, c.gapStart, c.site.getGridConnectionDate(), c.site.getSwapDate(), c.taskId);
    }
    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        LocalDate today = LocalDate.now(TUNIS);
        LocalDate startDate = date(from, today.minusDays(30)), endDate = date(to, today);
        if (endDate.isBefore(startDate) || endDate.isAfter(today)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Période invalide");
        Meta meta = mongo.findById("activation", Meta.class);
        if (meta == null || !meta.initialized) return Map.of("ready", false);
        Instant requestedStart = startDate.atStartOfDay(TUNIS).toInstant();
        Instant start = requestedStart.isBefore(meta.activatedAt) ? meta.activatedAt : requestedStart;
        Instant end = endDate.plusDays(1).atStartOfDay(TUNIS).toInstant();
        List<Observation> observations = mongo.findAll(Observation.class).stream().filter(o -> visible(o.site)).toList();
        List<Change> events = mongo.find(Query.query(Criteria.where("at").gte(start).lt(end))
            .with(Sort.by(Sort.Direction.DESC, "at")), Change.class).stream().filter(c -> visible(c.site)).toList();
        // Calculate starting/ending membership as of the chosen dates, not from today's count alone.
        Map<String, Boolean> atStart = new HashMap<>(), atEnd = new HashMap<>();
        List<Change> memberships = mongo.find(Query.query(Criteria.where("kind").in("BASELINE", "ADDED", "REACTIVATED", "REMOVED", "LOCAL_REMOVED").and("at").lt(end))
            .with(Sort.by(Sort.Direction.ASC, "at")), Change.class);
        for (Change c : memberships) if (visible(c.site)) {
            boolean present = !Set.of("REMOVED", "LOCAL_REMOVED").contains(c.kind) && active(c.site);
            atEnd.put(c.siteId, present);
            if (c.at.isBefore(start) || ("BASELINE".equals(c.kind) && c.at.equals(start))) atStart.put(c.siteId, present);
        }
        Map<String,Object> out = new LinkedHashMap<>(); out.put("ready", true); out.put("activatedAt", meta.activatedAt);
        out.put("effectiveFrom", start); out.put("lastScanAt", meta.lastScanAt); out.put("warning", meta.error);
        out.put("partialPeriod", requestedStart.isBefore(meta.activatedAt));
        out.put("startCount", atStart.values().stream().filter(Boolean::booleanValue).count());
        out.put("endCount", atEnd.values().stream().filter(Boolean::booleanValue).count());
        out.put("currentCount", observations.stream().filter(o -> active(o.site)).count());
        out.put("added", events.stream().filter(c -> "ADDED".equals(c.kind)).count());
        out.put("removed", events.stream().filter(c -> Set.of("REMOVED", "LOCAL_REMOVED").contains(c.kind)).count());
        out.put("reactivated", events.stream().filter(c -> "REACTIVATED".equals(c.kind)).count());
        out.put("stale", observations.stream().filter(o -> active(o.site) && !fresh(o.site.getStatusLastSyncAt(), Instant.now())).count());
        out.put("events", events.stream().filter(c -> Set.of("ADDED", "REMOVED", "LOCAL_REMOVED", "REACTIVATED", "DATES_UPDATED").contains(c.kind)).map(this::eventView).toList());
        out.put("sites", observations.stream().map(this::siteView).toList());
        return out;
    }

    @GetMapping("/sites/{id}/history")
    public List<EventView> history(@PathVariable String id, @RequestParam(defaultValue = "0") int page) {
        Observation o = mongo.findById(id, Observation.class);
        if (o == null || !visible(o.site)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (page < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return mongo.find(Query.query(new Criteria().andOperator(Criteria.where("siteId").is(id), new Criteria().orOperator(Criteria.where("owner").is(null), Criteria.where("owner").is(owner())))).with(Sort.by(Sort.Direction.DESC,"at"))
            .skip((long)page * 100).limit(100), Change.class).stream().map(this::eventView).toList();
    }

    @GetMapping("/results")
    public List<Map<String, Object>> results() {
        List<PlanningController.Task> tasks = mongo.find(Query.query(Criteria.where("owner").is(owner()))
            .with(Sort.by(Sort.Direction.DESC, "plannedDate")), PlanningController.Task.class);
        Map<String, Observation> observations = new HashMap<>();
        for (Observation o : mongo.findAll(Observation.class)) if (visible(o.site)) observations.put(o.id, o);
        Instant now = Instant.now();
        Meta quality = mongo.findById("activation", Meta.class);
        Instant earliest = tasks.stream().filter(t -> t.siteFollowups != null).flatMap(t -> t.siteFollowups.stream())
            .map(m -> m.completionRecordedAt == null ? m.assigned.capturedAt : m.completionRecordedAt)
            .filter(Objects::nonNull).min(Instant::compareTo).orElse(now);
        Map<String, List<Change>> observationsBySite = mongo.find(Query.query(Criteria.where("siteId").in(observations.keySet())
            .and("kind").in("STATE_CHANGED", "GAP").and("at").gte(earliest)).with(Sort.by(Sort.Direction.ASC,"at")), Change.class)
            .stream().collect(java.util.stream.Collectors.groupingBy(c -> c.siteId));
        List<Map<String,Object>> rows = new ArrayList<>();
        for (PlanningController.Task task : tasks) for (PlanningController.TaskSite selected : task.sites == null ? List.<PlanningController.TaskSite>of() : task.sites) {
            MissionSite m = task.siteFollowups == null ? null : task.siteFollowups.stream()
                .filter(v -> selected.brand().equals(v.assigned.brand) && selected.siteCode().equals(v.assigned.siteCode)).findFirst().orElse(null);
            if (PlanningSiteService.MANUAL.equals(selected.brand())) {
                PlanningSiteDocument manual;
                try { manual = planningSites.requireOwned(selected.siteCode()); }
                catch (ResponseStatusException ex) { continue; }
                Map<String,Object> site = new LinkedHashMap<>();
                site.put("id", manual.id); site.put("brand", PlanningSiteService.MANUAL);
                site.put("siteCode", manual.id); site.put("siteName", manual.name);
                site.put("manual", true); site.put("active", true); site.put("status", "WITHOUT_MONITORING");
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("taskId", task.id); row.put("followupOfTaskId", task.followupOfTaskId);
                row.put("project", task.project); row.put("team", task.teamName); row.put("type", task.type);
                row.put("missionStatus", task.status); row.put("plannedDate", task.plannedDate);
                row.put("completedDate", task.completedDate); row.put("site", site);
                row.put("assigned", m == null ? null : m.assigned);
                row.put("atCompletion", m == null ? null : m.atCompletion);
                row.put("completionRecordedAt", m == null ? null : m.completionRecordedAt);
                row.put("assessment", "Sans monitoring · validation terrain");
                row.put("needsFollowup", Set.of("Partiellement réalisé", "Non réalisé", "Reporté", "Bloqué").contains(task.status));
                row.put("nearbyMissions", tasks.stream().filter(t -> !Objects.equals(t.id, task.id)
                        && t.sites != null && t.sites.stream().anyMatch(v -> PlanningSiteService.MANUAL.equals(v.brand())
                        && manual.id.equals(v.siteCode())))
                        .filter(t -> Math.abs(java.time.temporal.ChronoUnit.DAYS.between(t.plannedDate, task.plannedDate)) <= 3).count());
                row.put("changes", List.of());
                rows.add(row);
                continue;
            }
            Observation o = m == null ? observations.values().stream().filter(v -> selected.brand().equals(v.site.getBrand().name()) && selected.siteCode().equals(v.site.getSiteCode())).findFirst().orElse(null) : observations.get(m.assigned.siteId);
            if (o == null) continue; // No cross-tenant leakage even for old task links.
            Reference current = tracking.reference(o.site, now);
            Instant anchor = m == null ? now : m.completionRecordedAt == null ? m.assigned.capturedAt : m.completionRecordedAt;
            List<Change> changes = observationsBySite.getOrDefault(o.id, List.of()).stream().filter(c -> !c.at.isBefore(anchor)).toList();
            String assessment = assess(m, current, changes, now, task.type);
            if (m != null && m.completionRecordedAt != null && quality != null && quality.failureFrom != null
                && now.isAfter(quality.failureFrom) && anchor.isBefore(quality.recoveredAt == null ? now : quality.recoveredAt))
                assessment = "Non vérifiable · interruption du journal";
            long overlap = tasks.stream().filter(t -> !Objects.equals(t.id, task.id) && t.sites != null && t.sites.stream()
                .anyMatch(s -> selected.brand().equals(s.brand()) && selected.siteCode().equals(s.siteCode())))
                .filter(t -> Math.abs(java.time.temporal.ChronoUnit.DAYS.between(t.plannedDate, task.plannedDate)) <= 3).count();
            Map<String,Object> r = new LinkedHashMap<>();
            r.put("taskId", task.id); r.put("followupOfTaskId", task.followupOfTaskId); r.put("referenceLate", m != null && m.lateReference); r.put("project", task.project); r.put("team", task.teamName); r.put("type", task.type);
            r.put("missionStatus", task.status); r.put("plannedDate", task.plannedDate); r.put("completedDate", task.completedDate);
            r.put("site", siteView(o)); r.put("assigned", m == null ? null : m.assigned); r.put("atCompletion", m == null ? null : m.atCompletion);
            r.put("completionRecordedAt", m == null ? null : m.completionRecordedAt); r.put("assessment", assessment);
            r.put("windowEndsAt", m == null || m.completionRecordedAt == null ? null : m.completionRecordedAt.plus(WINDOW));
            r.put("needsFollowup", assessment.startsWith("Non vérifiable") || assessment.startsWith("Rechute") || assessment.startsWith("Sans amélioration") || assessment.contains("incomplet") || assessment.contains("anomalies"));
            r.put("nearbyMissions", overlap); r.put("changes", changes.stream().map(this::eventView).toList());
            rows.add(r);
        }
        return rows;
    }
}
