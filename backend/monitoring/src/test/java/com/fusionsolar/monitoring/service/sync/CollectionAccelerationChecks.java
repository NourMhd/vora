package com.fusionsolar.monitoring.service.sync;
import com.fusionsolar.monitoring.service.huawei.*;
import com.fusionsolar.monitoring.config.ProvidersProperties;
import com.fusionsolar.monitoring.client.growatt.GrowattCloudClient;
import com.fusionsolar.monitoring.provider.growatt.GrowattMonitoringProvider;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.LocalDate;

/** Offline regression checks, run with compiled application dependencies. */
public class CollectionAccelerationChecks {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static void field(Object o, String name, Object value) throws Exception {
        var f=o.getClass().getDeclaredField(name); f.setAccessible(true); f.set(o,value);
    }
    public static void main(String[] args) throws Exception {
        var accounts=List.of(new HuaweiAccountDirectory.HuaweiAccount("a","g","url","u","p"),
                new HuaweiAccountDirectory.HuaweiAccount("b","g","url","v","p"));
        var directory=new HuaweiAccountDirectory(null) {
            @Override public List<HuaweiAccount> getEnabledAccounts(){ return accounts; }
        };
        var shared=new HuaweiApiRateLimiter(directory);
        field(shared,"groupMinRequestIntervalMs",300000L);
        check(shared.tryReserveSlot("a","/thirdData/getStationRealKpi")==0,"first shared slot");
        check(shared.tryReserveSlot("b","/thirdData/getStationRealKpi")>0,"shared budget retained");
        var individual=new HuaweiApiRateLimiter(directory);
        field(individual,"quotaScope","account"); field(individual,"groupMinRequestIntervalMs",300000L);
        check(individual.tryReserveSlot("a","/thirdData/getStationRealKpi")==0,"first account slot");
        check(individual.tryReserveSlot("b","/thirdData/getStationRealKpi")==0,"independent account slot");
        check(individual.tryReserveSlot("a","/thirdData/getStationRealKpi")>0,"same account protected");
        var executor=Executors.newFixedThreadPool(2);
        try {
            var started=new CountDownLatch(1); var release=new CountDownLatch(1); var calls=new AtomicInteger();
            var first=executor.submit(()->HistorySingleFlight.execute("same",()->{
                calls.incrementAndGet(); started.countDown();
                try { release.await(2,TimeUnit.SECONDS); } catch(InterruptedException e){throw new RuntimeException(e);}
                return List.of();
            }));
            started.await(2,TimeUnit.SECONDS);
            var secondStarted=new CountDownLatch(1);
            var second=executor.submit(()->{secondStarted.countDown(); return HistorySingleFlight.execute("same",()->{
                calls.incrementAndGet();return List.of();
            });});
            secondStarted.await(2,TimeUnit.SECONDS); Thread.sleep(50); release.countDown();
            first.get(3,TimeUnit.SECONDS); second.get(3,TimeUnit.SECONDS);
            check(calls.get()==1,"concurrent demand coalesced");
        } finally {executor.shutdownNow();}
        try { HistorySingleFlight.execute("error",()->{throw new IllegalStateException();}); } catch(IllegalStateException expected){}
        check(HistorySingleFlight.execute("error",List::of).isEmpty(),"failed flight released");
        var calls=new AtomicInteger();
        var client=new GrowattCloudClient(null,new ObjectMapper()) {
            @Override public JsonNode get(ProvidersProperties.HuaweiAccountProperties account,String path,Map<String,?> params) {
                calls.incrementAndGet(); int page=((Number)params.get("page")).intValue();
                return new ObjectMapper().valueToTree(Map.of("count",3,"energys", page==1 ?
                        List.of(Map.of("date","2026-10-01","energy",1),Map.of("date","2026-10-02","energy",2)) :
                        List.of(Map.of("date","2026-10-03","energy",3))));
            }
        };
        var provider=new GrowattMonitoringProvider(null,client);
        var rangeClass=Class.forName(GrowattMonitoringProvider.class.getName()+"$Range");
        var ctor=rangeClass.getDeclaredConstructors()[0];ctor.setAccessible(true);
        Object range=ctor.newInstance(LocalDate.of(2026,10,1),LocalDate.of(2026,10,3),"day",2,"month");
        var method=GrowattMonitoringProvider.class.getDeclaredMethod("getEnergyHistory",GrowattMonitoringProvider.AccountAndPlant.class,rangeClass);
        method.setAccessible(true);
        List<?> points=(List<?>)method.invoke(provider,new GrowattMonitoringProvider.AccountAndPlant(new ProvidersProperties.HuaweiAccountProperties(),"1"),range);
        check(points.size()==3 && calls.get()==2,"Growatt paginates to total count");
        System.out.println("PASS: 8 offline quota, concurrency, recovery and pagination checks");
    }
}
