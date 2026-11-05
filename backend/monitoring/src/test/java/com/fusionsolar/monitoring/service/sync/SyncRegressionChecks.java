package com.fusionsolar.monitoring.service.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fusionsolar.monitoring.provider.ProviderInventoryPage;
import com.fusionsolar.monitoring.provider.mapper.SiteMongoMapper;
import com.fusionsolar.monitoring.provider.solis.SolisMonitoringProvider;
import com.fusionsolar.monitoring.client.solis.SolisCloudClient;
import com.fusionsolar.monitoring.client.FusionSolarStationClient;
import com.fusionsolar.monitoring.service.HuaweiLiveStationService;
import com.fusionsolar.monitoring.service.huawei.HuaweiAccountDirectory;
import com.fusionsolar.monitoring.config.ProvidersProperties;
import com.fusionsolar.monitoring.dto.*;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.enums.*;
import java.time.*;
import java.util.*;

/** Can run as a standalone check without contacting providers or MongoDB. */
public final class SyncRegressionChecks {
    private static int checks;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
    private static void rejects(Runnable action, String label) {
        try { action.run(); } catch (IllegalStateException expected) { checks++; return; }
        throw new AssertionError(label);
    }
    public static void main(String[] args) throws Exception {
        checks = 0;
        ArrayNode hundred = JSON.createArrayNode();
        for (int i=0; i<100; i++) hundred.addObject().put("id", "s"+i);
        ObjectNode first = JSON.createObjectNode().set("records", hundred);
        check(ProviderInventoryPage.parse(first, "records").hasNext(100,100), "full page without total continues");
        ObjectNode shortPage = JSON.createObjectNode().set("records", JSON.createArrayNode().addObject().put("id","end"));
        // Construct a genuine array, not the ObjectNode returned by addObject().
        ArrayNode one = JSON.createArrayNode(); one.addObject().put("id","end"); shortPage.set("records",one);
        check(!ProviderInventoryPage.parse(shortPage,"records").hasNext(101,100),"short final page ends without total");
        shortPage.put("total",150);
        check(ProviderInventoryPage.parse(shortPage,"records").hasNext(101,100),"advertised total overrides short page");
        ObjectNode empty = JSON.createObjectNode().put("total",150).set("records",JSON.createArrayNode());
        rejects(() -> ProviderInventoryPage.parse(empty,"records").hasNext(100,100),"empty page before total rejected");
        rejects(() -> ProviderInventoryPage.parse(JSON.createObjectNode(),"records"),"malformed inventory rejected");
        rejects(() -> ProviderInventoryPage.parse(shortPage,"records").hasNext(151,100),"excess rows rejected");
        empty.put("total",0);
        check(!ProviderInventoryPage.parse(empty,"records").hasNext(0,100),"valid empty inventory accepted");

        Instant now=Instant.parse("2026-10-05T09:00:00Z");
        SiteDocument site=SiteDocument.builder().brand(Brand.HUAWEI).siteCode("a").active(true)
                .status(SiteStatus.HEALTHY).swapDate(LocalDate.of(2025,10,1)).build();
        check(!SiteMissingPolicy.confirmMissing(site,now) && Boolean.TRUE.equals(site.getActive()),"first absence keeps site active");
        check(SiteMissingPolicy.confirmMissing(site,now.plusSeconds(600)) && Boolean.FALSE.equals(site.getActive()),"second absence archives");
        check(site.getSwapDate().equals(LocalDate.of(2025,10,1)),"archive preserves swap date");
        SiteSummaryDto unknown=new SiteSummaryDto();unknown.setSiteCode("a");unknown.setStatus("unknown");
        SiteMongoMapper mapper=new SiteMongoMapper();
        for (Brand legacyBrand : new Brand[]{Brand.HUAWEI, Brand.SOLIS, Brand.GROWATT}) {
            SiteDocument legacy=SiteDocument.builder().brand(legacyBrand).siteCode("legacy").active(true)
                    .status(SiteStatus.HEALTHY).swapDate(LocalDate.of(2025,1,1)).build();
            SiteDocument migrated=mapper.toSiteDocument(legacyBrand,unknown,legacy,now);
            check(Integer.valueOf(0).equals(migrated.getMissingConfirmations()) && migrated.getStatus()==SiteStatus.HEALTHY
                    && migrated.getSwapDate().equals(legacy.getSwapDate()), "legacy missingConfirmations null accepted " + legacyBrand);
        }

        SiteDocument retained=mapper.toSiteDocument(Brand.HUAWEI,unknown,site,now);
        check(retained.getStatus()==SiteStatus.HEALTHY && retained.getStatusLastSyncAt()==null,"unknown state preserves state without fresh timestamp");
        check(retained.getMissingConfirmations()==2 && Boolean.FALSE.equals(retained.getActive()),"mapper preserves lifecycle metadata");
        unknown.setStatus("disconnected");
        SiteDocument live=mapper.toSiteDocument(Brand.HUAWEI,unknown,site,now);
        check(live.getStatus()==SiteStatus.DISCONNECTED && now.equals(live.getStatusLastSyncAt()),"fresh disconnected state timestamped");

        // Actual Solis provider: missing total must not truncate after page 1.
        class SolisFake extends SolisCloudClient {
            int calls; boolean repeat;
            SolisFake(){super(null,JSON);}
            @Override public JsonNode post(String path, Map<String,Object> body) {
                calls++;int page=((Number)body.get("pageNo")).intValue();
                ArrayNode records=JSON.createArrayNode();
                int count=page==1 || repeat ? 100 : 1;
                int start=page==1 || repeat ? 0 : 100;
                for(int i=0;i<count;i++) records.addObject().put("id", "id"+(start+i)).put("stationName","ARI_"+(start+i));
                return JSON.createObjectNode().set("records",records);
            }
        }
        SolisFake solis=new SolisFake();
        check(new SolisMonitoringProvider(solis).getSitesSummary().size()==101 && solis.calls==2,"Solis loads second page without total");
        SolisFake repeated=new SolisFake();repeated.repeat=true;
        rejects(() -> new SolisMonitoringProvider(repeated).getSitesSummary(),"Solis repeated page rejected");

        // Actual Huawei inventory: all pages and all source scopes are unioned.
        var a=new HuaweiAccountDirectory.HuaweiAccount("a","g","url","u","p");
        var b=new HuaweiAccountDirectory.HuaweiAccount("b","g","url","u","p");
        class Directory extends HuaweiAccountDirectory {
            Directory(){super(null);}
            @Override public List<HuaweiAccount> getEnabledAccounts(){return List.of(a,b);}
            @Override public Map<String,List<HuaweiAccount>> getEnabledAccountsByGroup(){return Map.of("g",List.of(a,b));}
        }
        class HuaweiFake extends FusionSolarStationClient {
            int calls; boolean failB; boolean truncate; boolean failLastKpi;
            HuaweiFake(){super(null,null,null);}
            @Override public StationListResponse getStationsPageForSync(String key,int page){
                calls++;
                if(failB && key.equals("b")) throw new IllegalStateException("unavailable");
                StationListResponse response=new StationListResponse();response.setSuccess(true);
                StationListResponse.DataContainer data=new StationListResponse.DataContainer();
                List<StationItemDto> rows=new ArrayList<>();
                int count=key.equals("a")?(page==1?100:1):1;
                if(truncate && page==2) count=0;
                for(int i=0;i<count;i++){
                    StationItemDto row=new StationItemDto();
                    row.setPlantCode(key.equals("a")?"s"+((page-1)*100+i):"unique-b");row.setPlantName("ARI_"+i);rows.add(row);
                }
                data.setList(rows);if(truncate) data.setTotal(101);response.setData(data);return response;
            }
            @Override public StationRealKpiResponse getStationRealKpiForSync(String key,List<String> codes){
                if(failLastKpi && codes.contains("s100"))throw new IllegalStateException("second batch failed");
                StationRealKpiResponse response=new StationRealKpiResponse();List<StationRealKpiResponse.DataItem> rows=new ArrayList<>();
                for(String code:codes){var row=new StationRealKpiResponse.DataItem();row.setStationCode(code);row.setDataItemMap(Map.of("real_health_state",3));rows.add(row);}
                response.setData(rows);return response;
            }
        }
        HuaweiFake h=new HuaweiFake();var service=new HuaweiLiveStationService(h,new Directory());
        var groups=service.getSitesSummaryByAccountGroup();
        check(groups.size()==1 && groups.get(0).complete() && groups.get(0).sites().size()==102,"Huawei all pages and disjoint source access unioned");
        check(service.inventoryAccountForSite("g","unique-b","a").equals("b"),"Huawei unique source routing retained");
        check(service.getFreshSitesSummaryForSync("a").size()==101,"Huawei KPI covers multiple batches");
        h.failLastKpi=true;
        check(service.getFreshSitesSummaryForSync("a").size()==100,"Huawei successful KPI batch survives a later batch failure");
        h.failLastKpi=false;
        service.getSitesSummaryByAccountGroup();
        check(h.calls==6,"successive Huawei sync bypasses inventory cache");
        HuaweiFake failed=new HuaweiFake();failed.failB=true;
        var partial=new HuaweiLiveStationService(failed,new Directory()).getSitesSummaryByAccountGroup().get(0);
        check(!partial.complete() && partial.sites().size()==101,"failed credential makes Huawei group incomplete");
        HuaweiFake truncated=new HuaweiFake();truncated.truncate=true;
        check(!new HuaweiLiveStationService(truncated,new Directory()).getSitesSummaryByAccountGroup().get(0).complete(),"Huawei empty premature page cannot authorize withdrawal");
        // Actual Growatt provider pagination and live-failure reporting.
        var properties=new ProvidersProperties();properties.getGrowatt().setEnabled(true);
        var ga=new ProvidersProperties.HuaweiAccountProperties();ga.setKey("ga");ga.setGroup("G");ga.setToken("fake");
        properties.getGrowatt().setAccounts(List.of(ga));
        class GrowattFake extends com.fusionsolar.monitoring.client.growatt.GrowattCloudClient {
            int pages;boolean failLive;
            GrowattFake(){super(properties,JSON);}
            @Override public JsonNode get(ProvidersProperties.HuaweiAccountProperties account,String path,Map<String,?> params){
                if(path.endsWith("/data")){
                    if(failLive)throw new IllegalStateException("live unavailable");
                    return JSON.createObjectNode().put("status","1");
                }
                pages++;int page=((Number)params.get("page")).intValue();
                ArrayNode rows=JSON.createArrayNode();int count=page==1?100:1;
                for(int i=0;i<count;i++) rows.addObject().put("plant_id","p"+((page-1)*100+i)).put("name","ARI_"+i);
                return JSON.createObjectNode().set("plants",rows);
            }
        }
        GrowattFake growatt=new GrowattFake();
        check(new com.fusionsolar.monitoring.provider.growatt.GrowattMonitoringProvider(properties,growatt)
                .getSitesSummary().size()==101 && growatt.pages==2,"Growatt continues without total");
        growatt.failLive=true;
        check(new com.fusionsolar.monitoring.provider.growatt.GrowattMonitoringProvider(properties,growatt)
                .getSitesSummary().stream().allMatch(v -> "unknown".equals(v.getStatus())),"Growatt failed live collection exposes unknown incoming states");

        // End-to-end Huawei sync using repositories in memory.
        class ScriptedLive extends HuaweiLiveStationService {
            boolean complete=true;boolean omitted;boolean reappear;
            ScriptedLive(){super(h,new Directory());}
            @Override public List<AccountSitesSummary> getSitesSummaryByAccountGroup(){
                if(omitted)return List.of();
                SiteSummaryDto summary=new SiteSummaryDto();summary.setSiteCode(reappear?"gone":"present");summary.setStatus("unknown");
                return List.of(new AccountSitesSummary("a","g",List.of(summary),complete));
            }
            @Override public List<SiteSummaryDto> getFreshSitesSummaryForSync(String key){return List.of();}
        }
        var store=new HashMap<String,SiteDocument>();
        SiteDocument gone=SiteDocument.builder().brand(Brand.HUAWEI).siteCode("gone").providerAccountGroup("g")
                .active(true).swapDate(LocalDate.of(2025,10,1)).build();store.put("gone",gone);
        var repository=(com.fusionsolar.monitoring.persistence.repositories.SiteRepository)java.lang.reflect.Proxy.newProxyInstance(
                SyncRegressionChecks.class.getClassLoader(),new Class[]{com.fusionsolar.monitoring.persistence.repositories.SiteRepository.class},
                (proxy,method,arguments) -> {
                    if(method.getName().equals("save")){SiteDocument doc=(SiteDocument)arguments[0];store.put(doc.getSiteCode(),doc);return doc;}
                    if(method.getName().startsWith("findByBrandAnd"))return Optional.ofNullable(store.get((String)arguments[arguments.length-1]));
                    if(method.getName().startsWith("findAllByBrand"))return new ArrayList<>(store.values());
                    throw new UnsupportedOperationException(method.getName());
                });
        List<com.fusionsolar.monitoring.persistence.document.SyncRunDocument> runs=new ArrayList<>();
        var runRepo=(com.fusionsolar.monitoring.persistence.repositories.SyncRunRepository)java.lang.reflect.Proxy.newProxyInstance(
                SyncRegressionChecks.class.getClassLoader(),new Class[]{com.fusionsolar.monitoring.persistence.repositories.SyncRunRepository.class},
                (proxy,method,arguments) -> {if(method.getName().equals("save")){var run=(com.fusionsolar.monitoring.persistence.document.SyncRunDocument)arguments[0];runs.add(run);return run;}throw new UnsupportedOperationException();});
        ScriptedLive scripted=new ScriptedLive();
        var sync=new HuaweiSyncService(new com.fusionsolar.monitoring.provider.huawei.HuaweiMonitoringProvider(scripted),repository,runRepo,mapper,scripted,new Directory()) {
            @Override protected void persistInventory(SiteDocument doc, SiteDocument old) { repository.save(doc); }
            @Override protected void persistMissing(SiteDocument doc) { repository.save(doc); }
        };
        var runMethod=HuaweiSyncService.class.getDeclaredMethod("runSitesSummarySync",Instant.class);runMethod.setAccessible(true);
        runMethod.invoke(sync,now);
        check(Boolean.TRUE.equals(store.get("gone").getActive()) && store.get("gone").getMissingConfirmations()==1,"sync first missing inventory keeps site");
        check(runs.get(runs.size()-1).getStatus()==SyncStatus.SUCCESS,"inventory success independent of states");
        scripted.complete=false;runMethod.invoke(sync,now.plusSeconds(600));
        check(store.get("gone").getMissingConfirmations()==1,"incomplete group cannot confirm disappearance");
        scripted.omitted=true;runMethod.invoke(sync,now.plusSeconds(1200));
        check(store.get("gone").getMissingConfirmations()==1 && runs.get(runs.size()-1).getStatus()==SyncStatus.PARTIAL_SUCCESS,"omitted group prevents false success and withdrawal");
        scripted.omitted=false;scripted.complete=true;runMethod.invoke(sync,now.plusSeconds(1800));
        check(Boolean.FALSE.equals(store.get("gone").getActive()) && store.get("gone").getSwapDate()!=null,"two successful inventory absences archive without losing swap");
        scripted.reappear=true;runMethod.invoke(sync,now.plusSeconds(2400));
        check(Boolean.TRUE.equals(store.get("gone").getActive()) && store.get("gone").getMissingConfirmations()==0 && store.get("gone").getSwapDate()!=null,"reappearance restores existing site and swap");
        System.out.println("PASS: "+checks+" synchronization regression checks");
    }
}
