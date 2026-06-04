package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.persistence.enums.Brand;

public interface MonitoringSitesSyncService {

    Brand getBrand();

    void syncSitesSummary();

    void bootstrapIfEmpty();
}