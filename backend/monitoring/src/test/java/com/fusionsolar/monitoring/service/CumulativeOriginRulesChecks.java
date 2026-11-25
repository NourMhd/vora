package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.dto.*;
import com.fusionsolar.monitoring.persistence.document.*;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.repositories.SiteDeviceRepository;
import com.fusionsolar.monitoring.util.LifetimeEnergyAllocation;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;

public class CumulativeOriginRulesChecks {
    private static int checks;
    private static void eq(Object a,Object b) {checks++;if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
    private static double rounded(double value){return Math.round(value*100)/100.0;}
    private static PerformanceReportRowDto row(Object report,SiteDocument site,LocalDate start,LocalDate end,LocalDate yesterday)throws Exception {
        var type=report.getClass();var range=Class.forName(type.getName()+"$ReportRange");
        var constructor=range.getDeclaredConstructors()[0];constructor.setAccessible(true);
        var method=type.getDeclaredMethod("toRow",SiteDocument.class,range,LocalDate.class,
                ProductionEstimationService.DailySiteProduction.class,List.class);method.setAccessible(true);
        var daily=new ProductionEstimationService.DailySiteProduction(site,site.getSiteCode(),site.getSiteName(),"ARI","Zone A",4.56,
                site.getSwapDate(),Map.of(),Set.of());
        return (PerformanceReportRowDto)method.invoke(report,site,constructor.newInstance(start,end),yesterday,daily,List.of(daily));
    }
    public static void main(String[]args)throws Exception {
        var engine=new ProductionEstimationService();
        var d=new DeviceItemDto();d.setId("1");d.setDevTypeId(1);
        var cache=SiteDeviceDocument.builder().providerAccountKey("primary").devices(List.of(d))
                .inverterTotalKwh(Map.of("1",10000.0)).inverterTotalObservedAt(Map.of("1",Instant.now())).build();
        var repository=(SiteDeviceRepository)Proxy.newProxyInstance(SiteDeviceRepository.class.getClassLoader(),
                new Class<?>[]{SiteDeviceRepository.class},(p,m,a)->Optional.of(cache));
        var report=new PerformanceReportService(null,null,null,engine,null,repository);
        var fallback=new PerformanceReportService(null,null,null,engine,null,null);
        var site=new SiteDocument();site.setBrand(Brand.HUAWEI);site.setSiteCode("NE=TEST");site.setSiteName("ARI_TEST");
        site.setProviderAccountKey("primary");site.setProviderAccountGroup("primary");site.setInstalledPowerKw(4.56);
        LocalDate start=LocalDate.of(2025,1,1),end=LocalDate.of(2025,12,31),today=LocalDate.of(2026,11,25),yesterday=today.minusDays(1);
        String[][] examples={
          {"2024-12-12","2025-01-15","2025-01-01"},
          {"2025-01-15","2024-12-12","2025-01-01"},
          {"2025-01-05","2025-01-10","2025-01-05"},
          {"2025-01-10","2025-01-05","2025-01-05"},
          {"2024-12-12","2024-12-15","2025-01-01"},
          {"2024-12-15","2024-12-12","2025-01-01"},
          {"2025-01-05","2025-01-05","2025-01-05"},
          {null,"2025-01-05","2025-01-05"},
          {"2025-01-05",null,"2025-01-05"},
          {"2025-03-05","2025-03-10","2025-03-05"}};
        for(var example:examples){
            site.setSwapDate(example[0]==null?null:LocalDate.parse(example[0]));site.setGridConnectionDate(example[1]);
            var origin=LifetimeEnergyAllocation.origin(site.getSwapDate(),example[1]==null?null:LocalDate.parse(example[1]));
            var actual=row(report,site,start,end,yesterday);var legacy=row(fallback,site,start,end,yesterday);
            eq(actual.getProductionStartDate(),example[2]);eq(actual.getProductionEndDate(),"2025-12-31");
            eq(actual.getExpectedKwh(),legacy.getExpectedKwh());
            eq(actual.getEstimatedMissingKwh(),0.0);eq(actual.getEstimatedDaysCount(),0);
            double numerator=engine.theoreticalYieldPerKwc("Zone A",LocalDate.parse(example[2]),end);
            double denominator=engine.theoreticalYieldPerKwc("Zone A",origin,today);
            eq(actual.getCapturedKwh(),rounded(10000*numerator/denominator));
            eq(actual.getActualKwh(),actual.getCapturedKwh());
            if(example[0]!=null&&example[0].equals("2024-12-12"))eq(actual.getCapturedKwh(),rounded(10000*1650.3/3261.8));
        }
        // No dates: no energy, no day counts, even when the inverter is available.
        site.setSwapDate(null);site.setGridConnectionDate(null);
        var absent=row(report,site,start,end,yesterday);
        eq(absent.getDataStatus(),"MISSING_ORIGIN_DATE");eq(absent.getExpectedKwh(),null);eq(absent.getActualKwh(),null);
        eq(absent.getCapturedKwh(),null);eq(absent.getEstimatedMissingKwh(),null);eq(absent.getDifferenceKwh(),null);
        eq(absent.getDaysCount(),null);eq(absent.getProductionDaysCount(),null);eq(absent.getCapturedDaysCount(),null);
        eq(absent.getBonusMalus(),"N/A");
        site.setSiteName("SFA_0138");eq(row(report,site,start,end,yesterday).getActualKwh(),null);site.setSiteName("ARI_TEST");
        // All dates after the selected period: allocation is zero, not unavailable.
        site.setSwapDate(LocalDate.of(2026,1,5));site.setGridConnectionDate("2026-01-10");
        var future=row(report,site,start,end,yesterday);
        eq(future.getActualKwh(),0.0);eq(future.getCapturedKwh(),0.0);eq(future.getProductionDaysCount(),0);
        eq(future.getDataStatus(),"NO_PRODUCTION_IN_FILTER");
        // Current filter cutoff remains yesterday; neither expected nor actual includes a future day.
        site.setSwapDate(start);site.setGridConnectionDate("2024-01-01");
        var current=row(report,site,LocalDate.of(2026,1,1),LocalDate.of(2026,12,31),yesterday);
        eq(current.getProductionEndDate(),"2026-11-24");
        // Every type of unusable counter restores the legacy energy computation.
        site.setSwapDate(start);site.setGridConnectionDate("2025-02-01");
        for(int state=0;state<4;state++){
            cache.setInverterTotalKwh(state==0?null:state==1?Map.of("1",Double.NaN):Map.of("1",10000.0));
            cache.setInverterTotalObservedAt(Map.of("1",state==2?Instant.now().minusSeconds(1201):Instant.now()));
            cache.setDevices(state==3?List.of(d,newDevice("2")):List.of(d));
            var actual=row(report,site,start,end,yesterday);var legacy=row(fallback,site,start,end,yesterday);
            eq(actual.getExpectedKwh(),legacy.getExpectedKwh());eq(actual.getActualKwh(),legacy.getActualKwh());
            eq(actual.getCapturedKwh(),legacy.getCapturedKwh());eq(actual.getEstimatedMissingKwh(),legacy.getEstimatedMissingKwh());
            eq(actual.getProductionStartDate(),legacy.getProductionStartDate());
        }
        System.out.println("Cumulative origin rules: "+checks+" checks passed");
    }
    private static DeviceItemDto newDevice(String id){var d=new DeviceItemDto();d.setId(id);d.setDevTypeId(38);return d;}
}
