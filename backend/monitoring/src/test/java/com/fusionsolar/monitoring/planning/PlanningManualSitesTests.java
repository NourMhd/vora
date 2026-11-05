package com.fusionsolar.monitoring.planning;

import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import com.fusionsolar.monitoring.service.CurrentSiteAccessService;
import org.junit.jupiter.api.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanningManualSitesTests {
    MongoTemplate mongo;
    SiteRepository monitored;
    CurrentSiteAccessService access;
    PlanningSiteService service;
    Map<String, PlanningSiteDocument> stored;

    void login(String user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "", List.of()));
    }
    @BeforeEach void setup() {
        login("owner");
        mongo = mock(MongoTemplate.class); monitored = mock(SiteRepository.class);
        access = mock(CurrentSiteAccessService.class); stored = new HashMap<>();
        when(access.canAccess(any())).thenReturn(true);
        when(mongo.insert(any(PlanningSiteDocument.class))).thenAnswer(i -> {
            PlanningSiteDocument site = i.getArgument(0);
            if (stored.containsKey(site.id)) throw new DuplicateKeyException("duplicate");
            stored.put(site.id, site); return site;
        });
        when(mongo.findById(anyString(), eq(PlanningSiteDocument.class)))
                .thenAnswer(i -> stored.get(i.<String>getArgument(0)));
        when(mongo.find(any(Query.class), eq(PlanningSiteDocument.class))).thenAnswer(i -> {
            String owner = i.<Query>getArgument(0).getQueryObject().getString("owner");
            return stored.values().stream().filter(s -> s.owner.equals(owner)).toList();
        });
        service = new PlanningSiteService(mongo, monitored, access);
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test void deletionArchivesOnlyTheOwnedPlanningSite() {
        var site = service.create("Site à retirer");
        service.delete(site.siteCode());
        verify(mongo).updateFirst(any(Query.class), any(org.springframework.data.mongodb.core.query.Update.class), eq(PlanningSiteDocument.class));
        verify(mongo, never()).remove(any(Query.class), eq(PlanningController.Task.class));
        verify(mongo, never()).remove(any(Query.class), eq(SiteDocument.class));
        assertNotNull(service.requireOwned(site.siteCode())); // Existing task references remain valid.
    }

    @Test void deletionRejectsAnotherOwnersSite() {
        var site = service.create("Privé");
        login("other");
        assertThrows(ResponseStatusException.class, () -> service.delete(site.siteCode()));
        verify(mongo, never()).updateFirst(any(Query.class), any(org.springframework.data.mongodb.core.query.Update.class), eq(PlanningSiteDocument.class));
    }

    @Test void manualInventoryIsPersistentDeduplicatedAndOwnerScoped() {
        var first = service.create("  Nouveau   Site ");
        assertEquals("Nouveau Site", first.siteName()); assertTrue(first.manual());
        assertEquals(first.siteCode(), service.create("nouveau site").siteCode());
        assertEquals(1, stored.size());
        assertEquals(1, service.choices().size());
        login("other");
        assertTrue(service.choices().isEmpty());
        assertThrows(ResponseStatusException.class, () -> service.requireOwned(first.siteCode()));
        var second = service.create("Nouveau Site");
        assertNotEquals(first.siteCode(), second.siteCode());
        assertEquals(1, service.choices().size());
        verify(mongo, never()).insert(any(SiteDocument.class));
        verify(mongo, never()).save(any(SiteDocument.class));
    }

    @Test void rejectsEmptyLongAndControlNames() {
        for (String name : Arrays.asList(null, "", "  ", "x".repeat(121), "invalid\u0001name"))
            assertThrows(ResponseStatusException.class, () -> service.create(name));
        assertEquals(PlanningSiteService.normalizedName("École"), PlanningSiteService.normalizedName("E\u0301COLE"));
        assertTrue(stored.isEmpty());
    }

    @Test void reusesAccessibleMonitoredSiteWithoutCreatingManualDuplicate() {
        var existing = SiteDocument.builder().brand(Brand.HUAWEI).siteCode("ARI_TEST")
                .siteName("ARI_Test").active(true).build();
        when(monitored.findAllByOrderBySiteNameAsc()).thenReturn(List.of(existing));
        var choice = service.create("ari_test");
        assertFalse(choice.manual()); assertEquals("ARI_TEST", choice.siteCode());
        assertTrue(stored.isEmpty());
        when(access.canAccess(existing)).thenReturn(false);
        assertTrue(service.create("ari_test").manual());
    }

    PlanningController.TaskInput input(List<PlanningController.TaskSite> refs, Long version, String status) {
        return new PlanningController.TaskInput(LocalDate.now(),
                status.equals("Réalisé") ? LocalDate.now() : null, null,
                "team", "Donneur", null, refs, "Projet", "Zone", "Étude", "Intervention",
                "Normale", status, null, null, null, version, null);
    }
    PlanningController controller() {
        var team = new PlanningController.Team(); team.id = "team"; team.owner = "owner"; team.name = "Terrain";
        when(mongo.findById("team", PlanningController.Team.class)).thenReturn(team);
        when(mongo.insert(any(PlanningController.Task.class))).thenAnswer(i -> {
            PlanningController.Task task = i.getArgument(0); task.id = "task"; task.version = 0L; return task;
        });
        when(mongo.save(any(PlanningController.Task.class))).thenAnswer(i -> i.getArgument(0));
        var controller = new PlanningController(mongo, monitored, access);
        ReflectionTestUtils.setField(controller, "planningSites", service);
        ReflectionTestUtils.setField(controller, "tracking", new SiteTrackingService(mongo));
        return controller;
    }

    @Test void createsAndEditsManualMissionWithoutLookingUpProviderBrand() {
        var manual = service.create("Site avant installation");
        var ref = new PlanningController.TaskSite("MANUAL", manual.siteCode(), "Nom falsifié");
        var controller = controller();
        var task = controller.create(input(List.of(ref), null, "Planifié"));
        assertEquals("Site avant installation", task.sites.get(0).siteName());
        assertEquals("MANUAL", task.siteFollowups.get(0).assigned.brand);
        assertNull(task.siteFollowups.get(0).assigned.status);
        when(mongo.findById("task", PlanningController.Task.class)).thenReturn(task);
        controller.update("task", input(List.of(ref), 0L, "Réalisé"));
        assertNotNull(task.siteFollowups.get(0).atCompletion);
        assertEquals(2, task.history.size());
        verify(monitored, never()).findAllByBrandAndSiteCode(any(), anyString());
        verify(mongo, never()).insert(any(SiteDocument.class));
        verify(mongo, never()).save(any(SiteDocument.class));
    }

    @Test void mixedMissionKeepsMonitoringReferenceAndManualReferenceSeparate() {
        var manual = service.create("Nouveau");
        var existing = SiteDocument.builder().id("monitor").brand(Brand.HUAWEI)
                .siteCode("ARI_TEST").siteName("ARI_TEST").active(true).build();
        when(monitored.findAllByBrandAndSiteCode(Brand.HUAWEI, "ARI_TEST")).thenReturn(List.of(existing));
        var task = controller().create(input(List.of(
                new PlanningController.TaskSite("MANUAL", manual.siteCode(), ""),
                new PlanningController.TaskSite("HUAWEI", "ARI_TEST", "")), null, "Planifié"));
        assertEquals(2, task.sites.size()); assertEquals(2, task.siteFollowups.size());
        assertEquals("MANUAL", task.siteFollowups.get(0).assigned.brand);
        assertEquals("HUAWEI", task.siteFollowups.get(1).assigned.brand);
    }

    @Test void forgedManualSiteIsRejectedWhenSavingMission() {
        var manual = service.create("Privé");
        login("other");
        var controller = controller();
        var team = new PlanningController.Team(); team.id = "team"; team.owner = "other";
        when(mongo.findById("team", PlanningController.Team.class)).thenReturn(team);
        assertThrows(ResponseStatusException.class, () -> controller.create(input(
                List.of(new PlanningController.TaskSite("MANUAL", manual.siteCode(), "Privé")), null, "Planifié")));
    }

    @Test void resultsIncludeManualMissionWithoutMonitoringObservations() {
        var manual = service.create("Sans plateforme");
        var task = controller().create(input(List.of(
                new PlanningController.TaskSite("MANUAL", manual.siteCode(), manual.siteName())), null, "Planifié"));
        when(mongo.find(any(Query.class), eq(PlanningController.Task.class))).thenReturn(List.of(task));
        var tracking = new PlanningTrackingController(mongo, monitored, access, new SiteTrackingService(mongo));
        ReflectionTestUtils.setField(tracking, "planningSites", service);
        var results = tracking.results();
        assertEquals(1, results.size());
        assertEquals("Sans monitoring · validation terrain", results.get(0).get("assessment"));
        assertNull(results.get(0).get("windowEndsAt"));
        login("other");
        assertTrue(tracking.results().isEmpty());
    }
}
