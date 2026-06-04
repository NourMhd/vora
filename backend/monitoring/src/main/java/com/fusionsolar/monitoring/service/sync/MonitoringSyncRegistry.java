package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.persistence.enums.Brand;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class MonitoringSyncRegistry {

    private final Map<Brand, MonitoringSyncService> syncServices;

    public MonitoringSyncRegistry(List<MonitoringSyncService> serviceList) {
        this.syncServices = new EnumMap<>(Brand.class);

        for (MonitoringSyncService service : serviceList) {
            this.syncServices.put(service.getBrand(), service);
        }
    }

    public MonitoringSyncService getRequiredSyncService(Brand brand) {
        MonitoringSyncService service = syncServices.get(brand);

        if (service == null) {
            throw new RuntimeException("No sync service configured for brand: " + brand);
        }

        return service;
    }

    public Set<Brand> getAvailableBrands() {
        return syncServices.keySet();
    }
}