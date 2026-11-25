package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.dto.DeviceItemDto;
import com.fusionsolar.monitoring.dto.PerformanceReportRowDto;
import com.fusionsolar.monitoring.persistence.document.SiteDeviceDocument;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.repositories.SiteDeviceRepository;
import com.fusionsolar.monitoring.service.sync.HuaweiInverterEnergySyncService;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;

public class HuaweiLifetimeReportChecks {
    private static int checks;
    private static void eq(Object actual, Object expected) {
        checks++; if (!Objects.equals(actual,expected)) throw new AssertionError(actual+" != "+expected);
    }
    private static DeviceItemDto inverter(String id,int type) {
        var d=new DeviceItemDto(); d.setId(id); d.setDevTypeId(type); return d;
    }
    public static void main(String[] args) throws Exception {
        var site=new SiteDocument(); site.setBrand(Brand.HUAWEI); site.setProviderAccountKey("primary");
        site.setProviderAccountGroup("primary"); site.setSiteCode("NE=TEST"); site.setSiteName("ARI_TEST");
        site.setGridConnectionDate("2025-01-01"); site.setSwapDate(LocalDate.of(2025,1,1)); site.setInstalledPowerKw(10.0);
        var now=Instant.now();
        var cache=SiteDeviceDocument.builder().brand(Brand.HUAWEI).providerAccountKey("primary")
                .siteCode(site.getSiteCode()).devices(List.of(inverter("1",1),inverter("2",38),inverter("3",17),inverter("1",1)))
                .inverterTotalKwh(Map.of("1",1000.0,"2",625.0,"3",9999.0))
                .inverterTotalObservedAt(Map.of("1",now,"2",now)).build();
        eq(HuaweiInverterEnergySyncService.completeTotal(cache,now),1625.0);
        var repo=(SiteDeviceRepository) Proxy.newProxyInstance(SiteDeviceRepository.class.getClassLoader(),
                new Class<?>[]{SiteDeviceRepository.class},(p,m,a)-> {
                    if (m.getName().equals("findByBrandAndProviderAccountGroupAndSiteCode")) return Optional.of(cache);
                    throw new UnsupportedOperationException(m.getName());
                });
        var service=new PerformanceReportService(null,null,null,new ProductionEstimationService(),null,repo);
        var rangeClass=Class.forName(PerformanceReportService.class.getName()+"$ReportRange");
        var ctor=rangeClass.getDeclaredConstructors()[0]; ctor.setAccessible(true);
        var method=PerformanceReportService.class.getDeclaredMethod("toRow",SiteDocument.class,rangeClass,LocalDate.class,
                ProductionEstimationService.DailySiteProduction.class,List.class);method.setAccessible(true);
        LocalDate jan=LocalDate.of(2025,1,1), janEnd=LocalDate.of(2025,1,31), febEnd=LocalDate.of(2025,2,28);
        var row=(PerformanceReportRowDto)method.invoke(service,site,ctor.newInstance(jan,janEnd),febEnd.minusDays(1),null,List.of());
        eq(row.getCapturedKwh(),735.0);eq(row.getActualKwh(),735.0);eq(row.getEstimatedMissingKwh(),0.0);
        eq(row.getCapturedDaysCount(),31);eq(row.getEstimatedDaysCount(),0);eq(row.getDataStatus(),"OK");
        eq(row.getProductionStartDate(),"2025-01-01");eq(row.getExpectedKwh(),735.0);
        row=(PerformanceReportRowDto)method.invoke(service,site,ctor.newInstance(janEnd.plusDays(1),febEnd),febEnd.minusDays(1),null,List.of());
        eq(row.getCapturedKwh(),855.0);eq(row.getProductionEndDate(),"2025-02-27");
        row=(PerformanceReportRowDto)method.invoke(service,site,ctor.newInstance(jan,febEnd),febEnd.minusDays(1),null,List.of());
        eq(row.getCapturedKwh(),1590.0);eq(row.getActualKwh(),1590.0);
        // Missing a second counter never yields a partial sum; normal history takes over.
        cache.setInverterTotalKwh(Map.of("1",1000.0));
        eq(HuaweiInverterEnergySyncService.completeTotal(cache,now),null);
        site.setSwapDate(jan);
        var daily=new ProductionEstimationService.DailySiteProduction(site,site.getSiteCode(),site.getSiteName(),"ARI","Zone A",10,jan,
                Map.of(jan,20.0),Set.of(jan));
        row=(PerformanceReportRowDto)method.invoke(service,site,ctor.newInstance(jan,janEnd),febEnd.minusDays(1),daily,List.of(daily));
        eq(row.getCapturedKwh(),20.0);eq(row.getEstimatedDaysCount(),30);eq(row.getActualKwh(),620.0);
        cache.setInverterTotalKwh(Map.of("1",1000.0,"2",625.0));
        cache.setInverterTotalObservedAt(Map.of("1",now,"2",now.minusSeconds(1201)));
        eq(HuaweiInverterEnergySyncService.completeTotal(cache,now),null);
        System.out.println("Huawei lifetime report: "+checks+" checks passed");
    }
}
