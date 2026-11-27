package com.fusionsolar.monitoring.service;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static com.fusionsolar.monitoring.service.ProductionEstimationServiceTests.*;

/** The new site-only policy must never use any regional reference. */
class GovernorateProductionTests {
    @Test void unrelatedHighYieldSiteCannotChangeEstimate() {
        var s=target(Map.of(JAN,16.15));var peer=site(doc("ARI_0002",1,JAN,null),Map.of(JAN,1000.0));
        var r=engine.computeDisplayedProduction(s,List.of(s,peer),JAN,JAN.plusDays(1));
        near(r.realKwh(),16.15);near(r.estimatedKwh(),16.15);near(r.displayedKwh(),32.30);
    }
    @Test void noHistoryNeverBorrowsFromRegionalSites() {
        var s=target(Map.of());var peer=site(doc("ARI_0002",1,JAN,null),Map.of(JAN,1000.0));
        near(engine.computeDisplayedProduction(s,List.of(peer),JAN,JAN).displayedKwh(),27.13);
    }
    @Test void differentZonesUseTheirOwnTheoreticalValues() {
        var b=site(doc("SFA_0001",10,JAN,null),Map.of());var c=site(doc("MED_0001",10,JAN,null),Map.of());
        near(engine.continuousProduction(b,JAN,JAN,YESTERDAY,ref(b)).displayedKwh(),31.87);
        near(engine.continuousProduction(c,JAN,JAN,YESTERDAY,ref(c)).displayedKwh(),34.01);
    }
    @Test void noHistoryFollowsDailySeasonalBands() {
        var s=target(Map.of());var r=ref(s);
        near(engine.continuousProduction(s,JAN.plusDays(8),JAN.plusDays(11),YESTERDAY,r).displayedKwh(),109.69);
    }
    @Test void historyDoesNotInfluenceOtherBrandsOrSites() {
        var s=target(Map.of(JAN,32.3));var d=doc("MED_0001",10,JAN,null);d.setBrand(com.fusionsolar.monitoring.persistence.enums.Brand.GROWATT);
        var peer=site(d,Map.of(JAN,1.0));
        eq(engine.computeDisplayedProduction(s,List.of(peer),JAN,JAN.plusDays(1)),engine.computeDisplayedProduction(s,JAN,JAN.plusDays(1)));
    }
    @Test void dateBoundedByOriginNotSwapAlone() {
        var s=site(doc("ARI_0001",10,JAN.plusDays(2),JAN.toString()),Map.of(JAN,32.3));
        var r=engine.continuousProduction(s,JAN,JAN.plusDays(2),YESTERDAY,ref(s));
        eq(r.monitoredDays(),1);eq(r.estimatedDays(),2);near(r.displayedKwh(),96.9);
    }
}
