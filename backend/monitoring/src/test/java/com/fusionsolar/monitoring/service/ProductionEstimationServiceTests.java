package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.service.ProductionEstimationService.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;

class ProductionEstimationServiceTests {
    static final ProductionEstimationService engine = new ProductionEstimationService();
    static final LocalDate JAN = LocalDate.of(2025,1,1), YESTERDAY = LocalDate.of(2026,11,26);
    static void eq(Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) throw new AssertionError(actual + " != " + expected);
    }
    static void near(double actual, double expected) {
        if (!Double.isFinite(actual) || Math.abs(actual-expected)>1e-8) throw new AssertionError(actual + " != " + expected);
    }
    static SiteDocument doc(String name, double power, LocalDate swap, String mes) {
        var d = new SiteDocument(); d.setSiteCode(name); d.setSiteName(name); d.setBrand(Brand.HUAWEI);
        d.setProviderAccountKey("primary"); d.setInstalledPowerKw(power); d.setSwapDate(swap); d.setGridConnectionDate(mes);
        return d;
    }
    static DailySiteProduction site(SiteDocument d, Map<LocalDate,Double> values) {
        String gov=engine.governorateForSite(d);
        return new DailySiteProduction(d,d.getSiteCode(),d.getSiteName(),gov,engine.zoneForGovernorate(gov),
                d.getInstalledPowerKw(),d.getSwapDate(),values,values.keySet());
    }
    static DailySiteProduction target(Map<LocalDate,Double> values) { return site(doc("ARI_0001",10,JAN,null),values); }
    static SiteReference ref(DailySiteProduction site) { return engine.continuousReference(site,YESTERDAY); }
    @Test void choosesLongestRatherThanHighestPerformance() {
        var values=Map.of(JAN,20.0,JAN.plusDays(1),20.0,JAN.plusDays(2),20.0,
                JAN.plusDays(4),100.0,JAN.plusDays(5),100.0);
        var r=ref(target(values)); eq(r.days(),3);eq(r.start(),JAN);near(r.coefficient(),20.0/32.3);
    }
    @Test void equalLengthChoosesBestRelativeYieldNotRawEnergy() {
        var july=LocalDate.of(2025,7,1);
        var r=ref(target(Map.of(JAN,30.0,JAN.plusDays(1),30.0,july,40.0,july.plusDays(1),40.0)));
        eq(r.start(),JAN);eq(r.days(),2);near(r.coefficient(),30.0/32.3);
    }
    @Test void perfectTieChoosesMostRecent() {
        var r=ref(target(Map.of(JAN,20.0,JAN.plusDays(1),20.0,JAN.plusDays(4),20.0,JAN.plusDays(5),20.0)));
        eq(r.start(),JAN.plusDays(4));eq(r.end(),JAN.plusDays(5));
    }
    @Test void equalPerformanceAcrossDifferentSeasonsUsesLatest() {
        var july=LocalDate.of(2025,7,1);
        var s=target(Map.of(JAN,32.3*.85,JAN.plusDays(1),32.3*.85,july,57.37*.85,july.plusDays(1),57.37*.85));
        eq(ref(s).start(),july);near(ref(s).coefficient(),.85);
    }
    @Test void zeroAndAbsentAndInvalidBreakStreak() {
        Map<LocalDate,Double> v=new HashMap<>();v.put(JAN,1.0);v.put(JAN.plusDays(1),0.0);
        v.put(JAN.plusDays(2),1.0);v.put(JAN.plusDays(3),Double.NaN);v.put(JAN.plusDays(4),1.0);
        v.put(JAN.plusDays(6),2.0);v.put(JAN.plusDays(7),3.0);v.put(JAN.plusDays(8),-1.0);
        eq(ref(target(v)).start(),JAN.plusDays(6));eq(ref(target(v)).days(),2);
    }
    @Test void tinyPositiveAndPartialArePreservedWithoutRounding() {
        var d=doc("ARI_0001",10,JAN,null);var v=Map.of(JAN,0.001,JAN.plusDays(1),2.0);
        var s=new DailySiteProduction(d,d.getSiteCode(),d.getSiteName(),"ARI","Zone A",10,JAN,v,v.keySet(),Map.of(JAN,DailyCoverage.PARTIAL));
        var r=ref(s);eq(r.days(),2);var x=engine.continuousDailyDecision(s,JAN,YESTERDAY,r);
        eq(x.monitored(),true);near(x.effectiveValue(),0.001);
    }
    @Test void noPositiveUsesEightyFourPercentAndIgnoresPeers() {
        var s=target(Map.of(JAN,0.0));var r=ref(s);eq(r.days(),0);near(r.coefficient(),0.84);
        var result=engine.computeDisplayedProduction(s,List.of(target(Map.of(JAN,999.0))),JAN,JAN.plusDays(1));
        near(result.estimatedKwh(),54.26);eq(result.estimatedDays(),2);eq(result.monitoredDays(),0);
    }
    @Test void coefficientAboveOneIsNotCapped() {
        var s=target(Map.of(JAN,64.6));var r=ref(s);near(r.coefficient(),2.0);
        near(engine.continuousDailyDecision(s,JAN.plusDays(10),YESTERDAY,r).effectiveValue(),65.98);
    }
    @Test void usesWeightedRatioOfSumsNotAverageOfDailyRatios() {
        var a=JAN.plusDays(9);var b=a.plusDays(1);
        var r=ref(target(Map.of(a,20.0,b,30.0)));near(r.coefficient(),50.0/(32.30+32.99));
    }
    @Test void referenceOutsideFilterAppliesSeasonalTheoreticalForEachDay() {
        var july=LocalDate.of(2025,7,1);
        var s=target(Map.of(july,57.37*0.85,july.plusDays(1),57.37*0.85));var r=ref(s);
        near(r.coefficient(),0.85);near(engine.continuousDailyDecision(s,JAN,YESTERDAY,r).effectiveValue(),32.3*0.85);
        near(engine.continuousDailyDecision(s,JAN.plusDays(20),YESTERDAY,r).effectiveValue(),34.43*0.85);
    }
    @Test void clipsReferenceBeforeOriginAndAfterYesterday() {
        var d=doc("ARI_0001",10,JAN.plusDays(3),"2025-01-02");
        var s=site(d,Map.of(JAN,999.0,JAN.plusDays(1),32.3,YESTERDAY.plusDays(1),999.0));
        var r=ref(s);eq(r.start(),JAN.plusDays(1));eq(r.days(),1);near(r.coefficient(),1);
    }
    @Test void oneKnownDateIsSufficientAndEarlierWins() {
        var d=doc("ARI_0001",10,null,"05/01/2025");eq(ProductionEstimationService.productionOrigin(d),JAN.plusDays(4));
        d.setSwapDate(JAN.plusDays(1));eq(ProductionEstimationService.productionOrigin(d),JAN.plusDays(1));
        d.setGridConnectionDate("2024-12-12");eq(ProductionEstimationService.productionOrigin(d),LocalDate.of(2024,12,12));
        d.setGridConnectionDate(null);eq(ProductionEstimationService.productionOrigin(d),JAN.plusDays(1));
        d.setSwapDate(null);eq(ProductionEstimationService.productionOrigin(d),null);
    }
    @Test void noDatesOrInvalidPowerOrUnknownZoneAreUnavailable() {
        for(var d:List.of(doc("ARI_0001",10,null,null),doc("ARI_0001",0,JAN,null),
                doc("ARI_0001",Double.NaN,JAN,null),doc("UNKNOWN",10,JAN,null))) {
            var s=site(d,Map.of(JAN,20.0));eq(ref(s),null);
            eq(engine.continuousProduction(s,JAN,JAN,YESTERDAY,null).displayedKwh(),null);
        }
    }
    @Test void beforeOriginAndFutureAreZeroEnergy() {
        var s=site(doc("ARI_0001",10,JAN.plusDays(2),null),Map.of());
        eq(engine.continuousProduction(s,JAN,JAN.plusDays(1),YESTERDAY,ref(s)).displayedKwh(),0.0);
        eq(engine.continuousProduction(s,YESTERDAY.plusDays(1),YESTERDAY.plusDays(2),YESTERDAY,ref(s)).displayedKwh(),0.0);
    }
    @Test void forcedZeroRemainsZeroWithValidOrigin() {
        var s=site(doc("SFA_0138",10,JAN,null),Map.of(JAN,999.0));
        eq(engine.continuousProduction(s,JAN,JAN.plusDays(2),YESTERDAY,ref(s)).displayedKwh(),0.0);
        eq(engine.continuousDailyDecision(s,JAN,YESTERDAY,ref(s)).effectiveValue(),0.0);
    }
}
