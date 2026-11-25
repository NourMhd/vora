package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.dto.*;
import com.fusionsolar.monitoring.persistence.document.*;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.repositories.SiteDeviceRepository;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;

/** Regression: live counter availability must never change the theoretical forecast. */
public class HuaweiForecastStabilityChecks {
    private static int checks;
    private static void eq(Object actual,Object expected) {
        checks++; if(!Objects.equals(actual,expected))throw new AssertionError(actual+" != "+expected);
    }
    public static void main(String[] args) throws Exception {
        var site=new SiteDocument(); site.setBrand(Brand.HUAWEI); site.setProviderAccountKey("primary");
        site.setProviderAccountGroup("primary"); site.setSiteCode("NE=TEST");site.setSiteName("TUN_0071");
        site.setInstalledPowerKw(4.56);site.setSwapDate(LocalDate.of(2025,12,12));site.setGridConnectionDate("2026-01-07");
        var d=new DeviceItemDto();d.setId("1");d.setDevTypeId(1);
        var cache=SiteDeviceDocument.builder().devices(List.of(d)).providerAccountKey("primary")
                .inverterTotalKwh(Map.of("1",10000.0)).inverterTotalObservedAt(Map.of("1",Instant.now())).build();
        var repo=(SiteDeviceRepository)Proxy.newProxyInstance(SiteDeviceRepository.class.getClassLoader(),
                new Class<?>[]{SiteDeviceRepository.class},(p,m,a)->Optional.of(cache));
        var engine=new ProductionEstimationService();
        var withCache=new PerformanceReportService(null,null,null,engine,null,repo);
        var withoutCache=new PerformanceReportService(null,null,null,engine,null,null);
        var range=Class.forName(PerformanceReportService.class.getName()+"$ReportRange");
        var ctor=range.getDeclaredConstructors()[0];ctor.setAccessible(true);
        var method=PerformanceReportService.class.getDeclaredMethod("toRow",SiteDocument.class,range,LocalDate.class,
                ProductionEstimationService.DailySiteProduction.class,List.class);method.setAccessible(true);
        LocalDate start=LocalDate.of(2025,1,1),end=LocalDate.of(2025,12,31),yesterday=LocalDate.of(2026,11,23);
        // Matches the screenshot: production in 2025 but provider commissioning in 2026.
        var daily=new ProductionEstimationService.DailySiteProduction(site,site.getSiteCode(),site.getSiteName(),"TUN","Zone A",4.56,
                site.getSwapDate(),Map.of(),Set.of());
        var baseline=(PerformanceReportRowDto)method.invoke(withoutCache,site,ctor.newInstance(start,end),yesterday,daily,List.of(daily));
        var current=(PerformanceReportRowDto)method.invoke(withCache,site,ctor.newInstance(start,end),yesterday,daily,List.of(daily));
        eq(current.getExpectedKwh(),194.71);eq(current.getExpectedKwh(),baseline.getExpectedKwh());
        eq(current.getActualKwh(),baseline.getActualKwh());eq(current.getCapturedKwh(),baseline.getCapturedKwh());
        eq(current.getEstimatedDaysCount(),20);eq(current.getProductionStartDate(),"2025-12-12");
        // Coherent earlier commissioning enables the allocation without changing forecast/window.
        site.setGridConnectionDate("2024-01-01");
        current=(PerformanceReportRowDto)method.invoke(withCache,site,ctor.newInstance(start,end),yesterday,daily,List.of(daily));
        eq(current.getExpectedKwh(),baseline.getExpectedKwh());eq(current.getProductionDaysCount(),baseline.getProductionDaysCount());
        eq(current.getProductionStartDate(),baseline.getProductionStartDate());eq(current.getProductionEndDate(),baseline.getProductionEndDate());
        eq(current.getEstimatedMissingKwh(),0.0);
        checks++;if(current.getCapturedKwh()==null||current.getCapturedKwh()<=0)throw new AssertionError("No allocation");
        // Invalidating the cache changes energy source, never the forecast.
        cache.setInverterTotalObservedAt(Map.of("1",Instant.now().minusSeconds(1201)));
        var stale=(PerformanceReportRowDto)method.invoke(withCache,site,ctor.newInstance(start,end),yesterday,daily,List.of(daily));
        eq(stale.getExpectedKwh(),current.getExpectedKwh());eq(stale.getActualKwh(),baseline.getActualKwh());
        // Current-year filters keep the original cutoff at yesterday even with a fresh counter.
        cache.setInverterTotalObservedAt(Map.of("1",Instant.now()));
        var yearStart=LocalDate.of(2026,1,1);var yearEnd=LocalDate.of(2026,12,31);
        baseline=(PerformanceReportRowDto)method.invoke(withoutCache,site,ctor.newInstance(yearStart,yearEnd),yesterday,daily,List.of(daily));
        current=(PerformanceReportRowDto)method.invoke(withCache,site,ctor.newInstance(yearStart,yearEnd),yesterday,daily,List.of(daily));
        eq(current.getExpectedKwh(),baseline.getExpectedKwh());eq(current.getProductionEndDate(),"2026-11-23");
        // A missing swap must not introduce a new theoretical forecast.
        site.setSwapDate(null);
        current=(PerformanceReportRowDto)method.invoke(withCache,site,ctor.newInstance(start,end),yesterday,null,List.of());
        eq(current.getExpectedKwh(),null);eq(current.getActualKwh(),null);eq(current.getDataStatus(),"MISSING_SWAP_DATE");
        System.out.println("Forecast stability: "+checks+" checks passed");
    }
}
