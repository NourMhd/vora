package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.service.huawei.*;
import com.fusionsolar.monitoring.client.FusionSolarAuthClient;
import com.fusionsolar.monitoring.service.HuaweiSessionManager;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline reproduction of the live-priority/login starvation seen in the production log. */
public class HuaweiCollectionRegressionChecks {
    private static final String LIVE="/thirdData/getStationRealKpi", HOUR="/thirdData/getKpiStationHour";
    static int checks;
    static void check(boolean ok,String message) { checks++; if(!ok) throw new AssertionError(message); }
    static void set(Object target,String name,Object value) throws Exception {
        var field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    static Object get(Object target,String name) throws Exception {
        var field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    static HuaweiApiRateLimiter limiter(HuaweiAccountDirectory directory) throws Exception {
        var limiter=new HuaweiApiRateLimiter(directory);
        set(limiter,"groupMinRequestIntervalMs",300000L);
        set(limiter,"historyGroupMinRequestIntervalMs",60000L);
        set(limiter,"loginMinRequestIntervalMs",300000L);
        return limiter;
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        checks=0;
        var accounts=List.of(new HuaweiAccountDirectory.HuaweiAccount("a","g","url","u","p"),
                new HuaweiAccountDirectory.HuaweiAccount("b","g","url","v","p"));
        var directory=new HuaweiAccountDirectory(null) {
            @Override public List<HuaweiAccount> getEnabledAccounts(){return accounts;}
        };
        var limiter=limiter(directory);
        check(limiter.tryReserveSlot("a","/thirdData/login")==0,"first login allowed");
        check(limiter.tryReserveSlot("a",LIVE)==0,"login must not postpone live data five minutes");
        for(int n=0;n<500;n++)limiter.prioritizeStates("a");
        check(limiter.tryReserveSlot("a",HOUR)==0,"repeated stale-state priority must not block history");
        check(limiter.tryReserveSlot("b",HOUR)>0,"shared history cadence still protected");
        check(limiter.tryReserveSlot("a",LIVE)>0,"same live endpoint still throttled");
        check(limiter.waitMsBeforeNextSlot("a",HOUR)>0,"availability includes cadence");
        check(limiter.tryReserveSlot("b","/thirdData/login")==0,"other user's login independent of report scope");
        check(limiter.tryReserveSlot("a","/thirdData/login")>0,"same user's login protected");
        Object state=((Map<String,?>)get(limiter,"statesByGroup")).get("g");
        set(state,"lastHistoryRequestAt",System.currentTimeMillis()-61000L);
        ((Map<String,Long>)get(state,"endpointLastRequestAt")).put(HOUR,System.currentTimeMillis()-61000L);
        check(limiter.tryReserveSlot("a",HOUR)==0,"history advances after its minute even while live is throttled");
        var calls=new AtomicInteger();
        var auth=new FusionSolarAuthClient(null,null) {
            @Override public LoginResult login(String key) {
                calls.incrementAndGet();return new LoginResult(null,"token",List.of("cookie=value"));
            }
        };
        var sessions=new HuaweiSessionManager(auth,directory);
        check(!sessions.hasUsableSession("a"),"no cached session initially");
        var first=sessions.getSession("a");
        check(sessions.hasUsableSession("a"),"cached session advertised");
        check(sessions.getSession("a")==first && calls.get()==1,"reuse session without another login");
        sessions.invalidateSession("a",first);
        check(!sessions.hasUsableSession("a"),"invalid session no longer advertised");
        System.out.println("PASS: "+checks+" Huawei login, history progress and session regression checks");
    }
}
