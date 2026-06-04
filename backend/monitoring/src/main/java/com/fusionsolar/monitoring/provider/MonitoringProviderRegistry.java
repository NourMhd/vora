package com.fusionsolar.monitoring.provider;

import com.fusionsolar.monitoring.persistence.enums.Brand;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class MonitoringProviderRegistry {

    private final Map<Brand, MonitoringProvider> providers;

    public MonitoringProviderRegistry(List<MonitoringProvider> providerList) {
        this.providers = new EnumMap<>(Brand.class);

        for (MonitoringProvider provider : providerList) {
            this.providers.put(provider.getBrand(), provider);
        }
    }

    public MonitoringProvider getRequiredProvider(Brand brand) {
        MonitoringProvider provider = providers.get(brand);

        if (provider == null) {
            throw new RuntimeException("No monitoring provider configured for brand: " + brand);
        }

        return provider;
    }

    public List<Brand> getAvailableBrands() {
        return providers.keySet().stream().sorted().toList();
    }
}