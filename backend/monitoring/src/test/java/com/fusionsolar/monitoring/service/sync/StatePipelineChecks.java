package com.fusionsolar.monitoring.service.sync;
import com.fusionsolar.monitoring.client.FusionSolarStationClient;
import com.fusionsolar.monitoring.dto.StationRealKpiResponse;
import com.fusionsolar.monitoring.persistence.document.*;
import com.fusionsolar.monitoring.persistence.enums.*;
import com.fusionsolar.monitoring.persistence.repositories.*;
import com.fusionsolar.monitoring.service.huawei.HuaweiAccountDirectory;
import java.time.*;
import java.util.*;
import java.lang.reflect.Proxy;
public class StatePipelineChecks {
 public static void main(String[] args) {
  var account = new HuaweiAccountDirectory.HuaweiAccount("a","g","url","u","p");
  var directory = new HuaweiAccountDirectory(null) {
   @Override public List<HuaweiAccount> getEnabledAccounts() { return List.of(account); }
  };
  var documents = new ArrayList<SiteDocument>();
  for (int i=0;i<101;i++) documents.add(SiteDocument.builder().id("id"+i).brand(Brand.HUAWEI)
   .siteCode("s"+i).active(true).providerAccountKey("a").status(SiteStatus.FAULTY)
   .swapDate(LocalDate.of(2025,1,1)).build());
  var repository = (SiteRepository)Proxy.newProxyInstance(StatePipelineChecks.class.getClassLoader(),new Class[]{SiteRepository.class},
   (p,m,a) -> {if(m.getName().equals("findAllByBrandOrderBySiteNameAsc"))return documents;throw new UnsupportedOperationException(m.getName());});
  var saved = new ArrayList<SyncRunDocument>();
  var runs = (SyncRunRepository)Proxy.newProxyInstance(StatePipelineChecks.class.getClassLoader(),new Class[]{SyncRunRepository.class},
   (p,m,a) -> {if(m.getName().equals("save")){saved.add((SyncRunDocument)a[0]);return a[0];}throw new UnsupportedOperationException();});
  class Client extends FusionSolarStationClient {
   int calls; List<String> firstNext;
   Client(){super(null,null,null);}
   @Override public void prioritizeStateCollection(String account) {}
   @Override public StationRealKpiResponse getStationRealKpi(String a,List<String> codes) {
    calls++;if(calls==2)throw new IllegalStateException("guard deferred");
    if(calls==3)firstNext=new ArrayList<>(codes);
    var response = new StationRealKpiResponse();var rows=new ArrayList<StationRealKpiResponse.DataItem>();
    for(String code:codes){var item=new StationRealKpiResponse.DataItem();item.setStationCode(code);item.setDataItemMap(Map.of("real_health_state",3));rows.add(item);}
    response.setData(rows);return response;
   }
  }
  var client=new Client();
  var service=new HuaweiStateSyncService(repository,runs,client,directory,null) {
   @Override protected boolean updateObservedEnergy(String id, Map<String,Object> values, Instant at) { return false; }
   @Override protected void updateObservedState(String id,String status,Instant at) {
    var site=documents.stream().filter(s->s.getId().equals(id)).findFirst().orElseThrow();
    site.setStatus(SiteStatus.valueOf(status.toUpperCase(Locale.ROOT)));site.setStatusLastSyncAt(at);
   }
  };
  service.syncStates();
  if(documents.get(100).getStatus()!=SiteStatus.FAULTY || documents.get(100).getStatusLastSyncAt()!=null)throw new AssertionError("missing state overwritten");
  if(saved.get(saved.size()-1).getStatus()!=SyncStatus.PARTIAL_SUCCESS)throw new AssertionError("false success");
  if(!Integer.valueOf(1).equals(saved.get(saved.size()-1).getStats().get("statesNotRefreshed")))throw new AssertionError("coverage");
  service.syncStates();
  if(!client.firstNext.get(0).equals("s100"))throw new AssertionError("deferred site not prioritized");
  if(documents.stream().anyMatch(s->s.getStatus()!=SiteStatus.HEALTHY))throw new AssertionError("not all refreshed");
  if(documents.stream().anyMatch(s->!s.getSwapDate().equals(LocalDate.of(2025,1,1))))throw new AssertionError("manual data overwritten");
  System.out.println("PASS: 6 state pipeline checks");
 }
}
