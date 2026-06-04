package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.persistence.enums.Brand;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class MonitoringSitesSyncRegistry {

    private final Map<Brand, MonitoringSitesSyncService> syncServices;

    public MonitoringSitesSyncRegistry(List<MonitoringSitesSyncService> serviceList) {
        this.syncServices = new EnumMap<>(Brand.class);

        for (MonitoringSitesSyncService service : serviceList) {
            this.syncServices.put(service.getBrand(), service);
        }
    }

    public MonitoringSitesSyncService getRequiredSyncService(Brand brand) {
        MonitoringSitesSyncService service = syncServices.get(brand);

        if (service == null) {
            throw new RuntimeException("No sites sync service configured for brand: " + brand);
        }

        return service;
    }

    public Set<Brand> getAvailableBrands() {
        return syncServices.keySet();
    }
}