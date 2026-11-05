package com.fusionsolar.monitoring.config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
@Configuration
public class StateSyncSchedulingConfig {
    @Bean(name = "taskScheduler") public ThreadPoolTaskScheduler taskScheduler() { return scheduler("provider-background-", 3); }
    @Bean public ThreadPoolTaskScheduler stateScheduler() { return scheduler("provider-state-", 3); }
    @Bean public ThreadPoolTaskScheduler inventoryScheduler() { return scheduler("provider-inventory-", 1); }
    @Bean public ThreadPoolTaskScheduler solisHistoryScheduler() { return scheduler("solis-history-", 1); }
    @Bean public ThreadPoolTaskScheduler growattHistoryScheduler() { return scheduler("growatt-history-", 1); }
    @Bean public ThreadPoolTaskScheduler demandHistoryScheduler() { return scheduler("demand-history-", 3); }
    private ThreadPoolTaskScheduler scheduler(String name, int size) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(size); scheduler.setThreadNamePrefix(name); return scheduler;
    }
}
