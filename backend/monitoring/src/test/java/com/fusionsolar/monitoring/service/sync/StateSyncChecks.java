package com.fusionsolar.monitoring.service.sync;
public class StateSyncChecks {
 public static void main(String[] args) {
   if (!"disconnected".equals(HuaweiStateSyncService.decodeState(1))) throw new AssertionError();
   if (!"faulty".equals(HuaweiStateSyncService.decodeState("2"))) throw new AssertionError();
   if (!"healthy".equals(HuaweiStateSyncService.decodeState(3.0))) throw new AssertionError();
   for (Object bad : new Object[]{null, 0, 4, "bad", 2.5})
     if (HuaweiStateSyncService.decodeState(bad) != null) throw new AssertionError("invalid state: " + bad);
   try {
     var status = HuaweiStateSyncService.class.getMethod("syncStates").getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
     var inventory = SitesSummarySyncSchedulerService.class.getMethod("scheduledSyncAllBrandsSitesSummary").getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
     var solis = SitesSummarySyncSchedulerService.class.getMethod("scheduledSolisSitesSummary").getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
     if (status.scheduler().equals(inventory.scheduler())) throw new AssertionError("shared inventory/state scheduler");
     if (!solis.scheduler().equals(status.scheduler())) throw new AssertionError();
   } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
   System.out.println("PASS: 10 state mapping/scheduler checks");
 }
}
