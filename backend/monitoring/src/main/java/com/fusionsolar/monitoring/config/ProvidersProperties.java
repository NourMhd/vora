package com.fusionsolar.monitoring.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "providers")
public class ProvidersProperties {

    private ProviderProperties huawei = new ProviderProperties();
    private ProviderProperties solis = new ProviderProperties();
    private ProviderProperties growatt = new ProviderProperties();

    @Getter
    @Setter
    public static class ProviderProperties {
        private Boolean enabled;
        private String baseUrl;
        private String username;
        private String password;
        private String keyId;
        private String keySecret;
        private String token;

        /**
         * Multi-comptes Huawei Northbound.
         * Chaque compte a son propre token, son propre throttle et son propre budget.
         */
        private List<HuaweiAccountProperties> accounts = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class HuaweiAccountProperties {
        private Boolean enabled = true;
        private String key;
        /**
         * Groupe réel FusionSolar / entreprise.
         * Plusieurs identifiants Northbound du même compte FusionSolar doivent partager le même group.
         */
        private String group;
        private String baseUrl;
        private String username;
        private String password;
        private String token;
    }
}
