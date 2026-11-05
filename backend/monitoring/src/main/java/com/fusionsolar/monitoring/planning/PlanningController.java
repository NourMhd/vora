package com.fusionsolar.monitoring.planning;

import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import com.fusionsolar.monitoring.service.CurrentSiteAccessService;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/planning")
public class PlanningController {
    private final MongoTemplate mongo;
    @org.springframework.beans.factory.annotation.Autowired
    private SiteTrackingService tracking;
    @org.springframework.beans.factory.annotation.Autowired
    private PlanningSiteService planningSites;
    private final SiteRepository siteRepository;
    private final CurrentSiteAccessService access;
    private static final Set<String> STATUSES = Set.of("Planifié", "En cours", "Réalisé", "Partiellement réalisé", "Non réalisé", "Reporté", "Bloqué");
    private static final Set<String> TYPES = Set.of("Étude", "Installation", "Mise en service", "Maintenance / installation modems", "Réception", "Dépannage", "Monitoring");
    private static final Set<String> PRIORITIES = Set.of("Basse", "Normale", "Haute", "Critique");

    public PlanningController(MongoTemplate mongo, SiteRepository siteRepository, CurrentSiteAccessService access) {
        this.mongo = mongo;
        this.siteRepository = siteRepository;
        this.access = access;
    }

    @Document("field_teams")
    @CompoundIndex(name = "owner_name", def = "{'owner': 1, 'name': 1}", unique = true)
    public static class Team {
        @Id public String id;
        public String owner;
        public String name;
        public String manager;
        public List<Person> personnel = new ArrayList<>();
    }
    public record Person(String name, String role, String phone) {}
    public record TeamInput(String name, String manager, List<Person> personnel) {}

    @Document("field_mission_issuers")
    @CompoundIndex(name = "owner_normalized_name", def = "{'owner': 1, 'key': 1}", unique = true)
    public static class MissionIssuer {
        @Id public String id;
        public String owner;
        public String key;
        public String name;
    }

    @GetMapping("/mission-issuers")
    public List<String> missionIssuers() {
        Query query = Query.query(Criteria.where("owner").is(owner()));
        query.with(org.springframework.data.domain.Sort.by("name"));
        Map<String, String> names = new TreeMap<>();
        for (MissionIssuer issuer : mongo.find(query, MissionIssuer.class)) {
            names.putIfAbsent(issuer.key, issuer.name);
        }
        for (Task task : mongo.find(Query.query(Criteria.where("owner").is(owner())), Task.class)) {
            if (task.missionIssuer != null && !task.missionIssuer.isBlank())
                names.putIfAbsent(task.missionIssuer.toLowerCase(Locale.ROOT), task.missionIssuer);
        }
        return new ArrayList<>(names.values());
    }

    private void rememberIssuer(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Query query = Query.query(Criteria.where("owner").is(owner()).and("key").is(key));
        Update update = new Update().setOnInsert("owner", owner()).setOnInsert("key", key).setOnInsert("name", name);
        try { mongo.upsert(query, update, MissionIssuer.class); }
        catch (org.springframework.dao.DuplicateKeyException ignored) {
            // A concurrent request has already registered this name.
        }
    }

    @Document("field_tasks")
    @CompoundIndex(name = "owner_date", def = "{'owner': 1, 'plannedDate': 1}")
    public static class Task {
        @Id public String id;
        @Version public Long version;
        public String owner;
        public String followupOfTaskId;
        public boolean trackingInitialized;
        public LocalDate plannedDate;
        public LocalDate completedDate;
        public LocalDate postponedDate;
        public String teamId, teamName, manager, missionIssuer, site, project, zone, type, description, priority, status, reason, comment, action;
        public List<TaskSite> sites = new ArrayList<>();
        public List<SiteTrackingService.MissionSite> siteFollowups = new ArrayList<>();
        public List<SiteTrackingService.MissionSite> archivedSiteFollowups = new ArrayList<>();
        public Instant createdAt;
        public List<Event> history = new ArrayList<>();
    }
    public record Event(Instant at, String actor, String oldStatus, String newStatus, String reason, String comment, String action,
                        String oldMissionIssuer, String newMissionIssuer) {}
    public record TaskSite(String brand, String siteCode, String siteName) {}
    public record TaskInput(LocalDate plannedDate, LocalDate completedDate, LocalDate postponedDate,
                            String teamId, String missionIssuer, String site, List<TaskSite> sites, String project, String zone, String type,
                            String description, String priority, String status, String reason, String comment, String action,
                            Long version, String followupOfTaskId) {}

    private String owner() { return SecurityContextHolder.getContext().getAuthentication().getName(); }
    private String required(String value, String label) {
        if (value == null || value.trim().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " obligatoire");
        return value.trim();
    }
    private Team team(String id) {
        Team team = mongo.findById(required(id, "Équipe"), Team.class);
        if (team == null || !owner().equals(team.owner)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Équipe introuvable");
        return team;
    }
    private Task task(String id) {
        Task task = mongo.findById(id, Task.class);
        if (task == null || !owner().equals(task.owner)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tâche introuvable");
        return task;
    }

    @GetMapping("/teams")
    public List<Team> teams() {
        return mongo.find(new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("owner").is(owner())), Team.class);
    }
    @PostMapping("/teams")
    public Team createTeam(@RequestBody TeamInput input) {
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Team t = new Team();
        t.owner = owner();
        t.name = required(input.name(), "Nom d'équipe");
        t.manager = required(input.manager(), "Responsable");
        t.personnel = personnel(input.personnel());
        try { return mongo.insert(t); }
        catch (org.springframework.dao.DuplicateKeyException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Équipe déjà existante"); }
    }
    @PutMapping("/teams/{id}")
    public Team updateTeam(@PathVariable String id, @RequestBody TeamInput input) {
        Team t = team(id);
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        t.name = required(input.name(), "Nom d'équipe");
        t.manager = required(input.manager(), "Responsable");
        t.personnel = personnel(input.personnel());
        try { return mongo.save(t); }
        catch (org.springframework.dao.DuplicateKeyException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Équipe déjà existante"); }
    }
    @DeleteMapping("/teams/{id}")
    public void deleteTeam(@PathVariable String id) {
        Team existing = team(id);
        Query linkedTasks = Query.query(Criteria.where("owner").is(owner()).and("teamId").is(existing.id));
        if (mongo.exists(linkedTasks, Task.class)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cette équipe possède des tâches. Supprimez-les d'abord pour conserver un suivi cohérent.");
        }
        mongo.remove(Query.query(Criteria.where("_id").is(existing.id).and("owner").is(owner())), Team.class);
    }
    private List<Person> personnel(List<Person> input) {
        if (input == null) return new ArrayList<>();
        return input.stream().map(p -> new Person(required(p.name(), "Nom du personnel"), p.role() == null ? "" : p.role().trim(), p.phone() == null ? "" : p.phone().trim())).toList();
    }

    @GetMapping("/tasks")
    public List<Task> tasks() {
        var query = new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("owner").is(owner()));
        query.with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "plannedDate"));
        return mongo.find(query, Task.class);
    }
    @PostMapping("/tasks")
    public Task create(@RequestBody TaskInput input) {
        Task t = new Task();
        t.owner = owner();
        t.createdAt = Instant.now();
        apply(t, input);
        t.history.add(new Event(Instant.now(), owner(), null, t.status, t.reason, t.comment, t.action, null, t.missionIssuer));
        Task saved = mongo.insert(t);
        rememberIssuer(saved.missionIssuer);
        if (tracking != null) tracking.recordMission(saved);
        return saved;
    }
    @PutMapping("/tasks/{id}")
    public Task update(@PathVariable String id, @RequestBody TaskInput input) {
        Task t = task(id);
        if (input == null || input.version() == null || !input.version().equals(t.version))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tâche modifiée ailleurs : actualisez la page");
        String oldStatus = t.status;
        String oldMissionIssuer = t.missionIssuer;
        apply(t, input);
        // Every manual edit is recorded, including a free-form reason and changes without status transition.
        t.history.add(new Event(Instant.now(), owner(), oldStatus, t.status, t.reason, t.comment, t.action,
                oldMissionIssuer, t.missionIssuer));
        try {
            Task saved = mongo.save(t);
            rememberIssuer(saved.missionIssuer);
        if (tracking != null) tracking.recordMission(saved);
            return saved;
        }
        catch (org.springframework.dao.OptimisticLockingFailureException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tâche modifiée ailleurs : actualisez la page");
        }
    }
    @DeleteMapping("/tasks/{id}")
    public void deleteTask(@PathVariable String id, @RequestParam Long version) {
        Task existing = task(id);
        if (!Objects.equals(existing.version, version)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tâche modifiée ailleurs : actualisez la page");
        }
        Query matching = Query.query(Criteria.where("_id").is(id)
                .and("owner").is(owner()).and("version").is(version));
        if (mongo.remove(matching, Task.class).getDeletedCount() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tâche modifiée ailleurs : actualisez la page");
        }
    }
    private void apply(Task t, TaskInput i) {
        if (i == null || i.plannedDate() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date programmée obligatoire");
        Team team = team(i.teamId());
        String status = required(i.status(), "Statut");
        if (!STATUSES.contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Statut invalide");
        if ("Réalisé".equals(status) && i.completedDate() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date de réalisation obligatoire");
        if ("Reporté".equals(status) && i.postponedDate() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nouvelle date obligatoire");
        if (i.postponedDate() != null && i.postponedDate().isBefore(i.plannedDate())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nouvelle date avant la date initiale");
        if (i.followupOfTaskId() != null && !i.followupOfTaskId().isBlank()) {
            if (i.followupOfTaskId().equals(t.id)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Une mission ne peut pas se référencer elle-même");
            task(i.followupOfTaskId()); // owner validation
            t.followupOfTaskId = i.followupOfTaskId();
        }
        t.plannedDate = i.plannedDate();
        t.completedDate = "Réalisé".equals(status) ? i.completedDate() : null;
        t.postponedDate = "Reporté".equals(status) ? i.postponedDate() : null;
        t.teamId = team.id; t.teamName = team.name; t.manager = team.manager;
        t.missionIssuer = required(i.missionIssuer(), "Donneur d'ordre de mission").replaceAll("\\s+", " ");
        if (t.missionIssuer.length() > 120) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nom du donneur d'ordre trop long");
        if (i.sites() == null || i.sites().isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sélectionnez au moins un site");
        LinkedHashMap<String, TaskSite> selected = new LinkedHashMap<>();
        for (TaskSite candidate : i.sites()) {
            if (candidate == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Site invalide");
            if (PlanningSiteService.MANUAL.equals(candidate.brand())) {
                PlanningSiteDocument manual = planningSites.requireOwned(required(candidate.siteCode(), "Site manuel"));
                selected.put(PlanningSiteService.MANUAL + ":" + manual.id,
                        new TaskSite(PlanningSiteService.MANUAL, manual.id, manual.name));
                continue;
            }
            Brand brand;
            try { brand = Brand.valueOf(required(candidate.brand(), "Marque")); }
            catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Marque invalide"); }
            String code = required(candidate.siteCode(), "Code site");
            SiteDocument site = siteRepository.findAllByBrandAndSiteCode(brand, code).stream()
                    .filter(s -> !Boolean.FALSE.equals(s.getActive()) && access.canAccess(s))
                    .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Site indisponible : " + code));
            selected.put(brand.name() + ":" + code, new TaskSite(brand.name(), code, site.getSiteName()));
        }
        t.sites = new ArrayList<>(selected.values());
        t.site = String.join(", ", t.sites.stream().map(TaskSite::siteName).toList());
        t.project = i.project(); t.zone = i.zone();
        t.type = required(i.type(), "Type d'intervention");
        if (!TYPES.contains(t.type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type d'intervention invalide");
        t.description = required(i.description(), "Description");
        t.priority = required(i.priority(), "Priorité");
        if (!PRIORITIES.contains(t.priority)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Priorité invalide");
        t.status = status;
        if (tracking != null) tracking.prepare(t, selectedSite -> siteRepository.findAllByBrandAndSiteCode(Brand.valueOf(selectedSite.brand()), selectedSite.siteCode()).stream()
                .filter(s -> !Boolean.FALSE.equals(s.getActive()) && access.canAccess(s)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Site indisponible")));
        t.reason = i.reason(); // Preserve older V1 comment/action data without exposing new inputs.
    }
}
