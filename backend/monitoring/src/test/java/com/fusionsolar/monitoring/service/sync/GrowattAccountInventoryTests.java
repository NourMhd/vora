package com.fusionsolar.monitoring.service.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusionsolar.monitoring.client.growatt.GrowattCloudClient;
import com.fusionsolar.monitoring.config.ProvidersProperties;
import com.fusionsolar.monitoring.provider.growatt.GrowattMonitoringProvider;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GrowattAccountInventoryTests {
    private final ObjectMapper json = new ObjectMapper();
    private ProvidersProperties.HuaweiAccountProperties account(String key) {
        var a = new ProvidersProperties.HuaweiAccountProperties();
        a.setKey(key); a.setToken("test"); return a;
    }

    @Test void failureIsIsolatedAndEmptyAccountIsComplete() throws Exception {
        var props = new ProvidersProperties();
        var its = account("ITS"); var second = account("COMPTE2"); var third = account("THIRD");
        props.getGrowatt().setAccounts(List.of(its, second, third));
        var client = mock(GrowattCloudClient.class);
        when(client.get(eq(its), eq("/v1/plant/list"), anyMap())).thenThrow(new IllegalStateException("unavailable"));
        when(client.get(eq(second), eq("/v1/plant/list"), anyMap())).thenReturn(json.readTree("{\"plants\":[],\"total\":0}"));
        when(client.get(eq(third), eq("/v1/plant/list"), anyMap())).thenReturn(json.readTree("{\"plants\":[{\"plant_id\":12}],\"total\":1}"));
        when(client.get(eq(third), eq("/v1/plant/data"), anyMap())).thenReturn(json.readTree("{\"plant_id\":999,\"status\":1}"));
        var result = new GrowattMonitoringProvider(props, client).getSitesInventory();
        assertEquals(Set.of("ITS"), result.failedAccountGroups());
        assertEquals(Set.of("COMPTE2", "THIRD"), result.completedAccountGroups());
        assertEquals("GROWATT_THIRD_12", result.sites().get(0).getSiteCode());
    }

    @Test void incompletePaginationCannotConfirmMissingSites() throws Exception {
        var props = new ProvidersProperties(); var a = account("ITS");
        props.getGrowatt().setAccounts(List.of(a));
        var client = mock(GrowattCloudClient.class);
        when(client.get(eq(a), eq("/v1/plant/list"), anyMap())).thenReturn(
                json.readTree("{\"plants\":[{\"plant_id\":1}],\"total\":2}"),
                json.readTree("{\"plants\":[],\"total\":2}"));
        when(client.get(eq(a), eq("/v1/plant/data"), anyMap())).thenThrow(new IllegalStateException("offline"));
        var result = new GrowattMonitoringProvider(props, client).getSitesInventory();
        assertTrue(result.completedAccountGroups().isEmpty());
        assertTrue(result.sites().isEmpty());
        assertEquals(Set.of("ITS"), result.failedAccountGroups());
    }

    @Test void longestAccountPrefixWins() {
        var props = new ProvidersProperties();
        props.getGrowatt().setAccounts(List.of(account("ITS"), account("ITS_EXTRA")));
        var provider = new GrowattMonitoringProvider(props, mock(GrowattCloudClient.class));
        assertEquals("ITS_EXTRA", provider.inventoryAccountGroup("GROWATT_ITS_EXTRA_12"));
        assertEquals("12", provider.parseSiteCode("GROWATT_ITS_EXTRA_12").plantId());
    }
    @Test void removalNeedsTwoCompleteInventoriesAndFailedAccountsStayVisible() {
        var provider = mock(GrowattMonitoringProvider.class);
        var sites = mock(com.fusionsolar.monitoring.persistence.repositories.SiteRepository.class);
        var runs = mock(com.fusionsolar.monitoring.persistence.repositories.SyncRunRepository.class);
        var mapper = mock(com.fusionsolar.monitoring.provider.mapper.SiteMongoMapper.class);
        when(runs.save(any())).thenAnswer(i -> i.getArgument(0));
        var removed = com.fusionsolar.monitoring.persistence.document.SiteDocument.builder()
                .siteCode("GROWATT_ITS_1").active(true).build();
        var protectedSite = com.fusionsolar.monitoring.persistence.document.SiteDocument.builder()
                .siteCode("GROWATT_COMPTE2_2").active(true).build();
        when(sites.findAllByBrandOrderBySiteNameAsc(com.fusionsolar.monitoring.persistence.enums.Brand.GROWATT))
                .thenReturn(List.of(removed, protectedSite));
        when(provider.inventoryAccountGroup("GROWATT_ITS_1")).thenReturn("ITS");
        when(provider.inventoryAccountGroup("GROWATT_COMPTE2_2")).thenReturn("COMPTE2");
        when(provider.getSitesInventory()).thenReturn(new GrowattMonitoringProvider.InventorySnapshot(
                List.of(), Set.of("ITS"), Set.of("COMPTE2")));
        var service = new GrowattSyncService(provider, sites, runs, mapper);
        service.syncSitesSummary();
        assertTrue(removed.getActive()); assertEquals(1, removed.getMissingConfirmations());
        service.syncSitesSummary();
        assertFalse(removed.getActive()); assertNotNull(removed.getDeletedAt());
        assertTrue(protectedSite.getActive()); assertNull(protectedSite.getMissingConfirmations());
        var summary = new com.fusionsolar.monitoring.dto.SiteSummaryDto();
        summary.setSiteCode("GROWATT_ITS_1"); summary.setStatus("healthy");
        when(provider.getSitesInventory()).thenReturn(new GrowattMonitoringProvider.InventorySnapshot(
                List.of(summary), Set.of("ITS"), Set.of("COMPTE2")));
        when(sites.findByBrandAndSiteCode(com.fusionsolar.monitoring.persistence.enums.Brand.GROWATT, summary.getSiteCode()))
                .thenReturn(Optional.of(removed));
        when(mapper.toSiteDocument(any(), eq(summary), eq(removed), any())).thenReturn(removed);
        service.syncSitesSummary();
        assertTrue(removed.getActive()); assertNull(removed.getDeletedAt());
        assertEquals(0, removed.getMissingConfirmations());
    }

}
