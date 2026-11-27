package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.persistence.document.*;
import com.fusionsolar.monitoring.persistence.enums.*;
import com.fusionsolar.monitoring.persistence.repositories.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import static com.fusionsolar.monitoring.service.ProductionEstimationServiceTests.*;

class MonthlyComparisonTests {
    private static final ZoneId TUNIS=ZoneId.of("Africa/Tunis");
    private static final LocalDate JULY=LocalDate.of(2025,7,1);
    private static HistoryPointDto point(LocalDate date,double value) {
        return new HistoryPointDto(date.atStartOfDay(TUNIS).toInstant().toEpochMilli(),value);
    }
    private static SiteHistoryDocument history(String month,String group,String account,HistoryPointDto... points) {
        var h=new SiteHistoryDocument();h.setBucket(month);h.setProviderAccountGroup(group);h.setProviderAccountKey(account);
        h.setPoints(List.of(points));return h;
    }
    private static <T> T proxy(Class<T> type,InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));
    }
    private record Fixture(SiteDocument target,PerformanceReportService report,DailyComparisonService daily) {}
    private Fixture fixture() {
        var target=doc("ARI_0001",10,JAN.plusDays(19),JAN.plusDays(9).toString());
        var peer=doc("ARI_0002",10,JAN,null);
        var forced=doc("SFA_0138",10,JAN,null);
        var missing=doc("ARI_0003",10,null,null);
        var mesOnly=doc("ARI_0004",10,null,JAN.toString());
        var hidden=doc("ARI_0005",10,JAN,null);hidden.setActive(false);
        var inventory=List.of(target,peer,forced,missing,mesOnly,hidden);
        HistoryPointDto partial=point(JAN.plusDays(11),7.0);partial.setStatus("PARTIAL");
        var data=Map.of(target.getSiteCode(),List.of(
                history("2025-01","primary","primary",point(JAN,999),partial,point(JAN.plusDays(11),0),point(JAN.plusDays(19),13)),
                history("2025-01","foreign","foreign",point(JAN.plusDays(11),999)),
                history("2025-01",null,"primary",point(JAN.plusDays(11),888)),
                history("2025-07","primary","primary",point(JULY,57.37*.85),point(JULY.plusDays(1),57.37*.85),point(JULY.plusDays(2),57.37*.85))),
                peer.getSiteCode(),List.of(history("2025-01","primary","primary",point(JAN,1000))),
                forced.getSiteCode(),List.of(history("2025-01","primary","primary",point(JAN,1000))));
        var sites=proxy(SiteRepository.class,(p,m,a)-> {
            if(m.getName().equals("findAllByOrderBySiteNameAsc"))return inventory;
            if(m.getName().equals("findAllBySiteCode"))return inventory.stream().filter(d->d.getSiteCode().equals(a[0])).toList();
            throw new AssertionError("Unexpected site repository call: "+m.getName());
        });
        var histories=proxy(SiteHistoryRepository.class,(p,m,a)->{
            if(!m.getName().equals("findAllByBrandAndSiteCodeAndGranularity")||a[2]!=HistoryGranularity.MONTH)
                throw new AssertionError("Expected a single bulk MONTH history read per site: "+m.getName());
            return data.getOrDefault((String)a[1],List.of());
        });
        var devices=proxy(SiteDeviceRepository.class,(p,m,a)->{throw new AssertionError("Report must never query counters");});
        var access=new CurrentSiteAccessService();
        return new Fixture(target,new PerformanceReportService(sites,histories,null,engine,access,devices),
                new DailyComparisonService(sites,histories,engine,access));
    }
    private static PerformanceReportService.MonthlyProduction find(List<PerformanceReportService.MonthlyProduction> list,String code) {
        return list.stream().filter(s->code.equals(s.siteCode())).findFirst().orElseThrow();
    }
    @Test void monthlyUsesGlobalReferenceOutsideRequestedMonthAndNeverCallsCounter() {
        var f=fixture();var jan=YearMonth.from(JAN);
        var matrix=f.report().getMonthlyComparison(jan.minusMonths(1),jan);
        eq(matrix.size(),5);
        var cells=find(matrix,f.target().getSiteCode()).months();
        eq(cells.get(0).actualKwh(),0.0);eq(cells.get(0).days(),0);
        var cell=cells.get(1);eq(cell.startDate(),"2025-01-10");eq(cell.days(),22);
        near(cell.expectedKwh(),740.93);near(cell.actualKwh(),593.71);near(cell.historicalKwh(),20.0);
        eq(cell.positiveDays(),2);eq(cell.missingDays(),20);
        eq(find(matrix,"SFA_0138").months().get(1).actualKwh(),0.0);
        eq(find(matrix,"ARI_0003").months().get(1).actualKwh(),null);
        near(find(matrix,"ARI_0004").months().get(1).actualKwh(),866.57);
        eq(find(f.report().getMonthlyComparison(jan,jan),f.target().getSiteCode()).months().get(0),cell);
    }
    @Test void annualEqualsSumOfMonthlyResultsWithSameHistory() {
        var f=fixture();var jan=YearMonth.from(JAN);
        var cells=find(f.report().getMonthlyComparison(jan,jan.plusMonths(11)),f.target().getSiteCode()).months();
        var annual=f.report().getReport("YEAR",2025,null,null).stream().filter(r->r.getSiteCode().equals(f.target().getSiteCode())).findFirst().orElseThrow();
        near(annual.getActualKwh(),engine.round2(cells.stream().mapToDouble(c->c.actualKwh()).sum()));
        near(annual.getExpectedKwh(),engine.round2(cells.stream().mapToDouble(c->c.expectedKwh()).sum()));
        near(annual.getCapturedKwh(),engine.round2(cells.stream().mapToDouble(c->c.historicalKwh()).sum()));
        eq(annual.getCapturedDaysCount(),cells.stream().mapToInt(c->c.positiveDays()).sum());
        eq(annual.getEstimatedDaysCount(),cells.stream().mapToInt(c->c.missingDays()).sum());
        near(annual.getActualKwh(),annual.getCapturedKwh()+annual.getEstimatedMissingKwh());
    }
    @Test void dailyComparatifUsesSameReferenceAndMatchesMonth() {
        var f=fixture();var jan=YearMonth.from(JAN);
        var matrix=f.report().getMonthlyComparison(jan,jan);var month=find(matrix,f.target().getSiteCode()).months().get(0);
        var daily=f.daily().getAriComparison(f.target().getSiteCode(),JAN.toString(),jan.atEndOfMonth().toString());
        eq(daily.getEstimatedRows().get(0).getCells().get(0).getValue(),null); // before origin
        near(daily.getEstimatedRows().get(9).getCells().get(0).getValue(),32.30*.85);
        near(daily.getEstimatedRows().get(11).getCells().get(0).getValue(),7.0);
        eq(daily.getEstimatedRows().get(11).getCells().get(0).getEstimated(),false);
        near(daily.getEstimatedTotals().getCells().get(0).getValue(),month.actualKwh());
        near(daily.getHistoricalTotals().getCells().get(0).getValue(),month.historicalKwh());
        var solo=f.daily().getAriComparison(f.target().getSiteCode(),"2025-01-10","2025-01-10");
        near(solo.getEstimatedRows().get(0).getCells().get(0).getValue(),32.30*.85);
    }
    @Test void fullHistoryLoaderRespectsAccountIsolationAndKeepsLargestPositiveDuplicate() {
        var d=doc("ARI_0001",10,JAN,null);var other=doc("ARI_0001",10,JAN,null);other.setProviderAccountGroup("other");
        var data=List.of(history("2025-01","other","other",point(JAN,999)),
                history("2025-01",null,null,point(JAN,888)),
                history("2025-01",null,"primary",point(JAN,0.001),point(JAN,0)),
                history("2025-02","primary","primary",point(LocalDate.of(2025,2,1),15)),
                history("2025-02",null,"primary",point(LocalDate.of(2025,2,1),777)));
        var repo=proxy(SiteHistoryRepository.class,(p,m,a)->data);
        eq(ProductionHistoryLoader.unambiguous(d,List.of(d,other)),false);
        var values=ProductionHistoryLoader.load(d,d.getSiteCode(),d.getSiteName(),repo,engine,false,YESTERDAY).valuesByDate();
        near(values.get(JAN),0.001);near(values.get(LocalDate.of(2025,2,1)),15.0);eq(values.size(),2);
    }
    @Test void futureOnlyDailyFilterReturnsZeroForKnownOrigin() {
        var f=fixture();var tomorrow=LocalDate.now(TUNIS).plusDays(1);
        var result=f.daily().getAriComparison(f.target().getSiteCode(),tomorrow.toString(),tomorrow.plusDays(1).toString());
        eq(result.getEstimatedRows().size(),0);eq(result.getEstimatedTotals().getCells().get(0).getValue(),0.0);
    }
    @Test void legacyHistoryCannotCrossAccountGroups() {
        var foreignGroup=new SiteHistoryDocument();foreignGroup.setProviderAccountGroup("other-group");foreignGroup.setProviderAccountKey("primary");
        var foreignAccount=new SiteHistoryDocument();foreignAccount.setProviderAccountKey("other-account");
        var unattributed=new SiteHistoryDocument();var sameAccount=new SiteHistoryDocument();sameAccount.setProviderAccountKey("primary");
        var histories=proxy(SiteHistoryRepository.class,(p,m,a)->m.isDefault()?InvocationHandler.invokeDefault(p,m,a):List.of(foreignGroup,foreignAccount,unattributed,sameAccount));
        eq(histories.findLegacyHistoryForAccount(Brand.HUAWEI,"ARI_0001",HistoryGranularity.MONTH,"2025-01","primary",false),Optional.of(sameAccount));
        eq(histories.findLegacyHistoryForAccount(Brand.HUAWEI,"ARI_0001",HistoryGranularity.MONTH,"2025-01","different",false),Optional.empty());
    }
}
