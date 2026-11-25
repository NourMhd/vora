package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.dto.*;
import com.fusionsolar.monitoring.persistence.document.*;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.repositories.SiteDeviceRepository;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;

/** Legacy fields are preserved on fallback; no origin date now means unavailable. */
public class LegacyFallbackEquivalenceChecks {
    private static long assertions;
    private static PerformanceReportRowDto row(Object service,SiteDocument site,
            ProductionEstimationService.DailySiteProduction daily,LocalDate start,LocalDate end,LocalDate yesterday)throws Exception {
        var cls=service.getClass();var range=Class.forName(cls.getName()+"$ReportRange");
        var constructor=range.getDeclaredConstructors()[0];constructor.setAccessible(true);
        var method=cls.getDeclaredMethod("toRow",SiteDocument.class,range,LocalDate.class,
                ProductionEstimationService.DailySiteProduction.class,List.class);method.setAccessible(true);
        return (PerformanceReportRowDto)method.invoke(service,site,constructor.newInstance(start,end),yesterday,daily,List.of(daily));
    }
    private static void identical(PerformanceReportRowDto a,PerformanceReportRowDto b)throws Exception {
        for(var field:PerformanceReportRowDto.class.getDeclaredFields()) {
            if(Modifier.isStatic(field.getModifiers()))continue;
            field.setAccessible(true);assertions++;
            if(!Objects.equals(field.get(a),field.get(b)))throw new AssertionError(field.getName()+": "+field.get(a)+" != "+field.get(b));
        }
    }
    public static void main(String[]args)throws Exception {
        var engine=new ProductionEstimationService();
        // During a differential audit, use the separately compiled original source.
        // Normal Maven tests use the exact same report with no inverter repository.
        var baselineClass=Class.forName(System.getProperty("reference.class",PerformanceReportService.class.getName()));
        var ctor=baselineClass.getConstructors()[0];var params=new Object[ctor.getParameterCount()];params[3]=engine;
        var baseline=ctor.newInstance(params);
        var first=new DeviceItemDto();first.setId("1");first.setDevTypeId(1);
        var second=new DeviceItemDto();second.setId("2");second.setDevTypeId(38);
        var cache=SiteDeviceDocument.builder().providerAccountKey("primary").devices(List.of(first,second)).build();
        var repo=(SiteDeviceRepository)Proxy.newProxyInstance(SiteDeviceRepository.class.getClassLoader(),new Class<?>[]{SiteDeviceRepository.class},
                (p,m,a)->Optional.of(cache));
        var candidate=new PerformanceReportService(null,null,null,engine,null,repo);
        LocalDate start=LocalDate.of(2025,1,1),end=LocalDate.of(2025,12,31),yesterday=LocalDate.of(2026,11,23);
        int cases=0;
        for(var brand:new Brand[]{Brand.HUAWEI,Brand.SOLIS,Brand.GROWATT})
        for(var power:new Double[]{4.56,0.0,Double.NaN})
        for(var swap:new LocalDate[]{LocalDate.of(2025,12,12),LocalDate.of(2026,1,1),null})
        for(var mes:new String[]{"2026-01-07","2024-01-01",null})
        for(int state=0;state<3;state++) {
            var site=new SiteDocument();site.setBrand(brand);site.setSiteName("TUN_TEST");site.setSiteCode("TEST");
            site.setProviderAccountKey("primary");site.setProviderAccountGroup("primary");
            site.setInstalledPowerKw(power);site.setSwapDate(swap);site.setGridConnectionDate(mes);
            Instant now=Instant.now();
            cache.setInverterTotalKwh(state==0?null:state==1?Map.of("1",100.0):Map.of("1",100.0,"2",200.0));
            cache.setInverterTotalObservedAt(Map.of("1",now,"2",now.minusSeconds(1201)));
            Map<LocalDate,Double> values=Map.of(LocalDate.of(2025,12,15),12.0,LocalDate.of(2025,12,16),0.0);
            var daily=new ProductionEstimationService.DailySiteProduction(site,"TEST","TUN_TEST","TUN","Zone A",power,swap,values,values.keySet());
            if (swap == null && mes == null) {
                var missing = row(candidate,site,daily,start,end,yesterday);
                if (!"MISSING_ORIGIN_DATE".equals(missing.getDataStatus()) || missing.getActualKwh() != null
                        || missing.getExpectedKwh() != null) throw new AssertionError("No dates must be unavailable");
            } else {
                identical(row(baseline,site,daily,start,end,yesterday),row(candidate,site,daily,start,end,yesterday));
            }
            cases++;
        }
        // Even a fresh complete counter must not bypass the old power/date conditions.
        cache.setInverterTotalKwh(Map.of("1",100.0,"2",200.0));
        cache.setInverterTotalObservedAt(Map.of("1",Instant.now(),"2",Instant.now()));
        for(var power:new Double[]{0.0,Double.NaN}) {
            var site=new SiteDocument();site.setBrand(Brand.HUAWEI);site.setSiteName("TUN_TEST");site.setSiteCode("TEST");
            site.setProviderAccountKey("primary");site.setProviderAccountGroup("primary");site.setInstalledPowerKw(power);
            site.setSwapDate(LocalDate.of(2025,12,12));site.setGridConnectionDate("2024-01-01");
            var daily=new ProductionEstimationService.DailySiteProduction(site,"TEST","TUN_TEST","TUN","Zone A",power,site.getSwapDate(),Map.of(),Set.of());
            identical(row(baseline,site,daily,start,end,yesterday),row(candidate,site,daily,start,end,yesterday));cases++;
        }
        System.out.println("Legacy equivalence: "+cases+" cases, "+assertions+" field comparisons passed");
    }
}
