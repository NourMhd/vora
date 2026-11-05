package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import com.fusionsolar.monitoring.persistence.repositories.SiteHistoryRepository;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class PartialWriteRegressionTests {
    @Test void readingHistoryDoesNotPersistAnOlderSiteSnapshot() {
        var sites = mock(SiteRepository.class);
        var histories = mock(SiteHistoryRepository.class);
        var service = new SiteEnergyKpiReconciliationService(sites, histories);
        var snapshot = SiteDocument.builder().brand(Brand.HUAWEI).monthEnergyKwh(0.0).yearEnergyKwh(0.0).build();
        service.reconcileAfterHistoryLoad(snapshot, "month", YearMonth.now(ZoneId.of("Africa/Tunis")).toString(),
                List.of(new HistoryPointDto(1790809200000L, 20.0)));
        assertEquals(20.0, snapshot.getMonthEnergyKwh());
        verifyNoInteractions(sites); // A concurrent status/day/swap in Mongo cannot be replaced by this GET.
    }
    @Test void liveMonthWinsOverIncompleteHistoryWhileYearCanStillBeReconciled() {
        var sites = mock(SiteRepository.class);
        var service = new SiteEnergyKpiReconciliationService(sites, mock(SiteHistoryRepository.class));
        var snapshot = SiteDocument.builder().brand(Brand.HUAWEI).monthEnergyKwh(500.0).yearEnergyKwh(500.0)
                .monthEnergyLastSyncAt(Instant.now()).build();
        service.reconcileAfterHistoryLoad(snapshot, "month", YearMonth.now(ZoneId.of("Africa/Tunis")).toString(),
                List.of(new HistoryPointDto(1790809200000L, 20.0)));
        assertEquals(500.0, snapshot.getMonthEnergyKwh());
        service.reconcileAfterHistoryLoad(snapshot, "year", String.valueOf(Year.now(ZoneId.of("Africa/Tunis")).getValue()),
                List.of(new HistoryPointDto(1767222000000L, 2000.0)));
        assertEquals(2000.0, snapshot.getYearEnergyKwh());
        verifyNoInteractions(sites);
    }
}
