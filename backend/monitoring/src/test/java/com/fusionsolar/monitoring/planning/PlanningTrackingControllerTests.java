package com.fusionsolar.monitoring.planning;

import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.enums.*;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import com.fusionsolar.monitoring.service.CurrentSiteAccessService;
import org.junit.jupiter.api.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.fusionsolar.monitoring.planning.SiteTrackingService.*;

class PlanningTrackingControllerTests {
    MongoTemplate mongo;
    PlanningTrackingController controller;
    @BeforeEach void setup() {
        mongo = mock(MongoTemplate.class);
        CurrentSiteAccessService access = mock(CurrentSiteAccessService.class);
        when(access.canAccess(any())).thenAnswer(i -> !"global-engineering".equals(i.<SiteDocument>getArgument(0).getProviderAccountGroup()));
        controller = new PlanningTrackingController(mongo, mock(SiteRepository.class), access, mock(SiteTrackingService.class));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("owner", "", List.of()));
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    SiteDocument site(String id, boolean active) { return SiteDocument.builder().id(id).brand(Brand.HUAWEI)
        .siteCode("ARI_" + id).siteName("ARI_" + id).active(active).status(SiteStatus.HEALTHY).build(); }
    Change event(String id, String kind, Instant time, boolean active) {
        Change c = new Change(); c.id = UUID.randomUUID().toString(); c.siteId = id; c.kind = kind; c.at = time; c.site = site(id, active); return c;
    }
    Observation observation(String id, boolean active) { Observation o = new Observation(); o.id = id; o.site = site(id, active); return o; }
    @Test void growthCountsAdditionsAndRemovalsSeparatelyAndHonorsTenantScope() {
        LocalDate today = LocalDate.now(TUNIS); Instant beginning = today.minusDays(3).atStartOfDay(TUNIS).toInstant();
        Meta meta = new Meta(); meta.activatedAt = beginning; meta.initialized = true;
        when(mongo.findById("activation", Meta.class)).thenReturn(meta);
        Observation a = observation("a", false), b = observation("b", true), hidden = observation("hidden", true);
        hidden.site.setProviderAccountGroup("global-engineering");
        when(mongo.findAll(Observation.class)).thenReturn(List.of(a,b,hidden));
        Change base = event("a", "BASELINE", beginning, true);
        Change add = event("b", "ADDED", today.minusDays(1).atStartOfDay(TUNIS).toInstant().plusSeconds(60), true);
        Change remove = event("a", "REMOVED", today.atStartOfDay(TUNIS).toInstant(), false);
        Change privateAdd = event("hidden", "ADDED", add.at, true); privateAdd.site.setProviderAccountGroup("global-engineering");
        when(mongo.find(any(Query.class), eq(Change.class))).thenReturn(List.of(base,add,remove,privateAdd));
        Map<String,Object> response = controller.overview(today.minusDays(1).toString(), today.toString());
        assertEquals(1L, response.get("startCount")); assertEquals(1L, response.get("endCount"));
        assertEquals(1L, response.get("added")); assertEquals(1L, response.get("removed")); assertEquals(1L, response.get("currentCount"));
        assertEquals(2, ((List<?>)response.get("sites")).size()); // Removed historical site is still visible to its tenant.
    }
    @Test void historyRejectsSitesFromAnotherTenant() {
        Observation o = observation("private", true); o.site.setProviderAccountGroup("global-engineering");
        when(mongo.findById("private", Observation.class)).thenReturn(o);
        assertThrows(ResponseStatusException.class, () -> controller.history("private", 0));
    }
    @Test void missionHistoryQueryIncludesOwnerRestriction() {
        when(mongo.findById("a", Observation.class)).thenReturn(observation("a", true));
        when(mongo.find(any(Query.class), eq(Change.class))).thenReturn(List.of());
        controller.history("a", 0);
        var query = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(query.capture(), eq(Change.class));
        assertTrue(query.getValue().getQueryObject().toJson().contains("owner"));
        assertEquals(100, query.getValue().getLimit());
    }
    @Test void invalidOrFutureDatesAreRejected() {
        assertThrows(ResponseStatusException.class, () -> controller.overview("invalid", null));
        assertThrows(ResponseStatusException.class, () -> controller.overview(null, LocalDate.now(TUNIS).plusDays(1).toString()));
    }
}
