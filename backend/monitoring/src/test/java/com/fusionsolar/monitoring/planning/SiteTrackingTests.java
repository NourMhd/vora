package com.fusionsolar.monitoring.planning;

import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.enums.*;
import org.junit.jupiter.api.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.fusionsolar.monitoring.planning.SiteTrackingService.*;

class SiteTrackingTests {
    private final Instant at = Instant.parse("2026-10-23T10:00:00Z");
    private MongoTemplate mongo;
    private SiteTrackingService service;
    private final Map<String, Observation> observations = new HashMap<>();
    private final Map<String, Change> events = new LinkedHashMap<>();
    private Meta meta;
    @BeforeEach void setup() {
        mongo = mock(MongoTemplate.class); service = new SiteTrackingService(mongo);
        when(mongo.indexOps(Change.class)).thenReturn(mock(IndexOperations.class));
        when(mongo.findById("activation", Meta.class)).thenAnswer(i -> meta);
        when(mongo.insert(any(Meta.class))).thenAnswer(i -> meta = i.getArgument(0));
        when(mongo.save(any(Meta.class))).thenAnswer(i -> meta = i.getArgument(0));
        when(mongo.findAll(SiteDocument.class)).thenReturn(List.of(site("existing", SiteStatus.HEALTHY, at)));
        when(mongo.findById(anyString(), eq(Observation.class))).thenAnswer(i -> observations.get(i.<String>getArgument(0)));
        when(mongo.save(any(Observation.class))).thenAnswer(i -> { Observation o = i.getArgument(0); observations.put(o.id, o); return o; });
        when(mongo.insert(any(Change.class))).thenAnswer(i -> {
            Change c = i.getArgument(0);
            if (events.containsKey(c.id)) throw new org.springframework.dao.DuplicateKeyException("duplicate");
            events.put(c.id, c); return c;
        });
    }
    private SiteDocument site(String id, SiteStatus status, Instant observation) {
        return SiteDocument.builder().id(id).brand(Brand.HUAWEI).siteCode("ARI_" + id).siteName("ARI_" + id)
            .status(status).statusLastSyncAt(observation).active(true).build();
    }
    @Test void existingSitesAreReferenceNotAdditions() {
        service.capture(site("existing", SiteStatus.HEALTHY, at), "TEST");
        assertTrue(meta.initialized); assertTrue(observations.get("existing").baseline);
        assertEquals(List.of("BASELINE"), events.values().stream().map(c -> c.kind).toList());
    }
    @Test void newSiteIsRecordedOnceAndSameStateDoesNotInventTransitions() {
        service.capture(site("new", SiteStatus.DISCONNECTED, at), "TEST");
        service.capture(site("new", SiteStatus.DISCONNECTED, at.plusSeconds(300)), "TEST");
        assertEquals(1, events.values().stream().filter(c -> c.kind.equals("ADDED")).count());
        assertEquals(0, events.values().stream().filter(c -> c.kind.equals("STATE_CHANGED")).count());
        assertFalse(observations.get("new").baseline);
    }
    @Test void newerTransitionsAreJournaledButOlderInventoryCannotOverwriteThem() {
        service.capture(site("existing", SiteStatus.DISCONNECTED, at.plusSeconds(300)), "PROVIDER");
        service.capture(site("existing", SiteStatus.HEALTHY, at), "OLD_INVENTORY");
        assertEquals(SiteStatus.DISCONNECTED, observations.get("existing").site.getStatus());
        assertEquals(1, events.values().stream().filter(c -> c.kind.equals("STATE_CHANGED")).count());
    }
    @Test void missingObservationsAreExplicitGapsAndNotOfflineStates() {
        service.capture(site("existing", SiteStatus.HEALTHY, at.plusSeconds(3600)), "PROVIDER");
        Change gap = events.values().stream().filter(c -> c.kind.equals("GAP")).findFirst().orElseThrow();
        assertEquals(at, gap.gapStart); assertEquals("HEALTHY", gap.newStatus);
        assertEquals(0, events.values().stream().filter(c -> c.kind.equals("STATE_CHANGED")).count());
    }
    @Test void confirmedMembershipChangesAreSeparateFromAdditions() {
        SiteDocument removed = site("existing", SiteStatus.HEALTHY, at); removed.setActive(false);
        service.capture(removed, "CONFIRMED_INVENTORY");
        service.capture(site("existing", SiteStatus.HEALTHY, at), "CONFIRMED_INVENTORY");
        assertEquals(1, events.values().stream().filter(c -> c.kind.equals("REMOVED")).count());
        assertEquals(1, events.values().stream().filter(c -> c.kind.equals("REACTIVATED")).count());
        assertEquals(0, events.values().stream().filter(c -> c.kind.equals("ADDED")).count());
    }
    @Test void ordinaryMissionEditsKeepTheAssignmentBaseline() {
        PlanningController.Task task = new PlanningController.Task();
        task.sites = List.of(new PlanningController.TaskSite("HUAWEI", "ARI_existing", "Existing")); task.status = "Planifié";
        service.prepare(task, s -> site("existing", SiteStatus.DISCONNECTED, Instant.now()));
        Reference original = task.siteFollowups.get(0).assigned;
        service.prepare(task, s -> site("existing", SiteStatus.HEALTHY, Instant.now()));
        assertSame(original, task.siteFollowups.get(0).assigned);
        assertEquals(SiteStatus.DISCONNECTED, original.status);
        task.sites = List.of(); service.prepare(task, s -> null);
        assertSame(original, task.archivedSiteFollowups.get(0).assigned);
    }
    private MissionSite mission() {
        MissionSite m = new MissionSite();
        m.assigned = new Reference(); m.assigned.status = SiteStatus.DISCONNECTED;
        m.assigned.observedAt = at; m.assigned.capturedAt = at;
        m.atCompletion = new Reference(); m.atCompletion.status = SiteStatus.HEALTHY;
        m.atCompletion.observedAt = at; m.completionRecordedAt = at;
        return m;
    }
    private Reference current(SiteStatus state, Instant time) {
        Reference r = new Reference(); r.status = state; r.observedAt = time; r.active = true; return r;
    }
    @Test void recoveredStateNeeds72HoursOfObservations() {
        assertEquals("Connecté · contrôle 72 h en cours", assess(mission(), current(SiteStatus.HEALTHY, at.plusSeconds(600)), List.of(), at.plusSeconds(600), "Monitoring"));
        Instant later = at.plus(Duration.ofHours(73));
        assertEquals("Rétabli · contrôle 72 h observé", assess(mission(), current(SiteStatus.HEALTHY, later), List.of(), later, "Monitoring"));
    }
    @Test void staleDataNeverProvesSuccess() {
        assertTrue(assess(mission(), current(SiteStatus.HEALTHY, at), List.of(), at.plus(Duration.ofDays(4)), "Monitoring").startsWith("Non vérifiable"));
    }
    @Test void gapsPreventSuccessfulCoverageClaim() {
        Change gap = new Change(); gap.kind = "GAP";
        assertEquals("Connecté · suivi incomplet", assess(mission(), current(SiteStatus.HEALTHY, at), List.of(gap), at, "Monitoring"));
    }
    @Test void relapseIsDistinctFromNoImprovement() {
        assertEquals("Rechute observée", assess(mission(), current(SiteStatus.DISCONNECTED, at), List.of(), at, "Dépannage"));
        MissionSite m = mission(); m.atCompletion.status = SiteStatus.DISCONNECTED;
        Instant later = at.plus(Duration.ofDays(4));
        assertEquals("Sans amélioration observée", assess(m, current(SiteStatus.DISCONNECTED, later), List.of(), later, "Dépannage"));
    }
    @Test void retrospectiveAndLegacyMissionsDoNotInventBeforeStates() {
        assertTrue(assess(null, null, List.of(), at, "Monitoring").startsWith("Non vérifiable"));
        MissionSite m = mission(); m.retrospective = true;
        assertTrue(assess(m, current(SiteStatus.HEALTHY, at), List.of(), at, "Monitoring").startsWith("Non vérifiable"));
    }
    @Test void studiesAndReceptionsAreNotJudgedByConnectivity() {
        assertEquals("À valider selon l’objectif", assess(mission(), current(SiteStatus.DISCONNECTED, at), List.of(), at, "Réception"));
    }
    @Test void legacyMissionEditDoesNotRecreateAnOriginalAssignment() {
        PlanningController.Task task = new PlanningController.Task(); task.id = "legacy"; task.status = "Planifié";
        task.sites = List.of(new PlanningController.TaskSite("HUAWEI", "ARI_existing", "Existing"));
        service.prepare(task, s -> site("existing", SiteStatus.HEALTHY, Instant.now()));
        assertTrue(task.siteFollowups.get(0).lateReference);
        assertTrue(task.trackingInitialized);
    }
    @Test void freshnessRejectsFutureOrMissingObservations() {
        assertFalse(fresh(null, at)); assertFalse(fresh(at.plusSeconds(1), at));
        assertFalse(fresh(at.minusSeconds(901), at)); assertTrue(fresh(at.minusSeconds(900), at));
    }
    @Test void journalFailureIsVisibleAndRecoveryKeepsTheIncompleteInterval() {
        service.capture(site("existing", SiteStatus.HEALTHY, at), "TEST");
        var failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(mongo.insert(any(Change.class))).thenAnswer(i -> {
            Change c = i.getArgument(0);
            if ("STATE_CHANGED".equals(c.kind) && failOnce.getAndSet(false)) throw new RuntimeException("write unavailable");
            if (events.putIfAbsent(c.id, c) != null) throw new org.springframework.dao.DuplicateKeyException("duplicate");
            return c;
        });
        SiteDocument changed = site("existing", SiteStatus.DISCONNECTED, at.plusSeconds(300));
        service.afterSiteSave(new org.springframework.data.mongodb.core.mapping.event.AfterSaveEvent<>(changed, new org.bson.Document(), "sites"));
        assertNotNull(meta.error); assertNotNull(meta.failureFrom); assertNull(meta.recoveredAt);
        when(mongo.findAll(SiteDocument.class)).thenReturn(List.of(changed));
        when(mongo.findAll(Observation.class)).thenAnswer(i -> new ArrayList<>(observations.values()));
        service.scan();
        assertNotNull(meta.recoveredAt); assertNotNull(meta.error);
        assertEquals(SiteStatus.DISCONNECTED, observations.get("existing").site.getStatus());
    }
    @Test void nonSiteMongoEventsCannotRecursivelyEnterTheJournal() {
        service.afterSiteSave(new org.springframework.data.mongodb.core.mapping.event.AfterSaveEvent<>("other", new org.bson.Document(), "other"));
        assertNull(meta); assertTrue(events.isEmpty());
    }
}
