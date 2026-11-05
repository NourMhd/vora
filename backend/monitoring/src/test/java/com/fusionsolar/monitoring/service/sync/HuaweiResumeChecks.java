package com.fusionsolar.monitoring.service.sync;
import com.fusionsolar.monitoring.client.FusionSolarAuthClient;
import com.fusionsolar.monitoring.service.HuaweiSessionManager;
import com.fusionsolar.monitoring.service.huawei.*;
import java.util.*;
public class HuaweiResumeChecks {
 public static void main(String[] args) throws Exception {
  var a=new HuaweiAccountDirectory.HuaweiAccount("a","g","url","u","p");
  var directory=new HuaweiAccountDirectory(null){@Override public List<HuaweiAccount> getEnabledAccounts(){return List.of(a);}};
  class Auth extends FusionSolarAuthClient {
   int calls;
   Auth(){super(null,null);}
   @Override public LoginResult login(String key) {
    calls++; if(calls==1)throw new HuaweiApiDeferredException("no HTTP request", 300000);
    return new LoginResult(null,"token",List.of("cookie=value"));
   }
  }
  var auth=new Auth();var manager=new HuaweiSessionManager(auth,directory);
  try {manager.getSession("a");throw new AssertionError();} catch(HuaweiApiDeferredException expected){}
  if(manager.getSession("a")==null || auth.calls!=2)throw new AssertionError("deferred login consumed retry interval");
  manager.getSession("a");if(auth.calls!=2)throw new AssertionError("session not reused");
  manager.invalidateCurrentSession("a");
  try {manager.getSession("a");throw new AssertionError("real login cadence lost");}catch(HuaweiApiDeferredException expected){}
  var guard=new HuaweiApiRateLimiter(directory);
  guard.prioritizeStates("a");
  if(guard.tryReserveSlot("a","/thirdData/stations")<=0)throw new AssertionError("inventory consumes stale state priority");
  if(guard.tryReserveSlot("a","/thirdData/getStationRealKpi")!=0)throw new AssertionError("state blocked by own priority");
  if(guard.tryReserveSlot("a","/thirdData/getStationRealKpi")<=0)throw new AssertionError("cadence bypassed");
  System.out.println("PASS: 6 Huawei reconnect/priority checks");
 }
}
