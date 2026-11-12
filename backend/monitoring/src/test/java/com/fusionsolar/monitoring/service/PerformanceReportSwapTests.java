package com.fusionsolar.monitoring.service;

import org.junit.jupiter.api.Test;
import com.fusionsolar.monitoring.service.*;
import com.fusionsolar.monitoring.service.ProductionEstimationService.*;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.dto.PerformanceReportRowDto;
import java.time.*; import java.util.*; import java.lang.reflect.*;
class PerformanceReportSwapTests {
 static int checks;
 static void eq(Object a,Object b) { checks++; if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b); }
 static final PerformanceReportService service=new PerformanceReportService(null,null,null,new ProductionEstimationService(),null);
 static SiteDocument site=new SiteDocument();
 static Method toRow,load; static Constructor<?> ctor;
 static PerformanceReportRowDto row(LocalDate start,LocalDate end,LocalDate yesterday,Map<LocalDate,Double> values)throws Exception{
  var daily=new DailySiteProduction(site,"ARI_1","ARI_1","ARI","Zone A",10,site.getSwapDate(),values,values.keySet());
  return (PerformanceReportRowDto)toRow.invoke(service,site,ctor.newInstance(start,end),yesterday,daily,List.of(daily));
 }
 @Test
 void appliesSwapBoundariesAndUnavailableValues() throws Exception {
  Class<?> range=Class.forName("com.fusionsolar.monitoring.service.PerformanceReportService$ReportRange");
  ctor=range.getDeclaredConstructors()[0];ctor.setAccessible(true);
  toRow=PerformanceReportService.class.getDeclaredMethod("toRow",SiteDocument.class,range,LocalDate.class,DailySiteProduction.class,List.class);toRow.setAccessible(true);
  site.setSiteCode("ARI_1");site.setSiteName("ARI_1");site.setInstalledPowerKw(10.0);
  LocalDate start=LocalDate.of(2025,11,1),end=LocalDate.of(2025,11,30),swap=LocalDate.of(2025,11,10);
  site.setSwapDate(swap);site.setGridConnectionDate("2026-02-01");
  var r=row(start,end,end,Map.of(start,999.0,swap,0.0,swap.plusDays(1),20.0));
  eq(r.getProductionStartDate(),"2025-11-10");eq(r.getProductionDaysCount(),21);eq(r.getFilterDaysCount(),21);
  eq(r.getCapturedDaysCount(),2);eq(r.getEstimatedDaysCount(),19);eq(r.getCapturedKwh(),20.0);
  eq(r.getEstimatedMissingKwh(),190.0);eq(r.getActualKwh(),210.0);eq(r.getExpectedKwh(),560.0);
  site.setGridConnectionDate(null);eq(row(start,end,end,Map.of(swap,20.0)).getActualKwh(),420.0);
  site.setSwapDate(start.minusDays(10));eq(row(start,end,end,Map.of(start,20.0)).getFilterDaysCount(),30);
  site.setSwapDate(swap);r=row(start,end,swap.plusDays(1),Map.of(swap,20.0));
  eq(r.getProductionEndDate(),"2025-11-11");eq(r.getFilterDaysCount(),2);eq(r.getActualKwh(),40.0);
  r=row(start,end,end,Map.of());eq(r.getActualKwh(),null);eq(r.getEstimatedMissingKwh(),null);eq(r.getEstimatedDaysCount(),21);
  site.setSwapDate(end.plusDays(1));r=row(start,end,end,Map.of());eq(r.getProductionDaysCount(),0);eq(r.getActualKwh(),null);
  site.setSwapDate(null);r=row(start,end,end,Map.of(start,20.0));
  eq(r.getExpectedKwh(),null);eq(r.getCapturedKwh(),null);eq(r.getEstimatedMissingKwh(),null);eq(r.getActualKwh(),null);eq(r.getDataStatus(),"MISSING_SWAP_DATE");
  site.setSwapDate(LocalDate.of(2026,1,1));site.setGridConnectionDate("2026-02-01");
  r=row(LocalDate.of(2025,5,1),LocalDate.of(2026,5,1),LocalDate.of(2026,5,1),Map.of());
  eq(r.getProductionDaysCount(),121);eq(r.getProductionStartDate(),"2026-01-01");
  load=PerformanceReportService.class.getDeclaredMethod("loadSiteProduction",SiteDocument.class,LocalDate.class,LocalDate.class);load.setAccessible(true);
  var loaded=(DailySiteProduction)load.invoke(service,site,null,null);eq(loaded.swapDate(),site.getSwapDate());

 }
}
