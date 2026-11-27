package com.fusionsolar.monitoring.service;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static com.fusionsolar.monitoring.service.ProductionEstimationServiceTests.*;
class PerformanceReportSwapTests {
 @Test void appliesOriginAndFilterBoundaries() throws Exception {
  var service=new PerformanceReportService(null,null,null,engine,null,null);
  var range=Class.forName(PerformanceReportService.class.getName()+"$ReportRange");
  var ctor=range.getDeclaredConstructor(LocalDate.class,LocalDate.class);ctor.setAccessible(true);
  var method=PerformanceReportService.class.getDeclaredMethod("toRow",com.fusionsolar.monitoring.persistence.document.SiteDocument.class,range,LocalDate.class,ProductionEstimationService.DailySiteProduction.class,List.class);method.setAccessible(true);
  var d=doc("ARI_1",10,JAN.plusDays(4),JAN.plusDays(1).toString());var s=site(d,Map.of(JAN,999.0,JAN.plusDays(1),32.3));
  var r=(com.fusionsolar.monitoring.dto.PerformanceReportRowDto)method.invoke(service,d,ctor.newInstance(JAN,JAN.plusDays(5)),JAN.plusDays(3),s,List.of(s));
  eq(r.getProductionStartDate(),"2025-01-02");eq(r.getProductionEndDate(),"2025-01-04");eq(r.getProductionDaysCount(),3);
  eq(r.getCapturedDaysCount(),1);eq(r.getEstimatedDaysCount(),2);near(r.getActualKwh(),96.9);near(r.getExpectedKwh(),96.9);
  d.setSwapDate(null);var mesOnly=(com.fusionsolar.monitoring.dto.PerformanceReportRowDto)method.invoke(service,d,ctor.newInstance(JAN,JAN.plusDays(5)),JAN.plusDays(3),s,List.of(s));
  eq(mesOnly.getActualKwh(),r.getActualKwh());eq(mesOnly.getDataStatus(),"OK");
  d.setGridConnectionDate(null);var unavailable=(com.fusionsolar.monitoring.dto.PerformanceReportRowDto)method.invoke(service,d,ctor.newInstance(JAN,JAN.plusDays(5)),JAN.plusDays(3),s,List.of(s));
  eq(unavailable.getActualKwh(),null);eq(unavailable.getDataStatus(),"MISSING_ORIGIN_DATE");
 }
}
