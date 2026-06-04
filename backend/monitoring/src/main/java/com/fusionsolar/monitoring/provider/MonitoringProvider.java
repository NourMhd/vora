package com.fusionsolar.monitoring.provider;

import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.dto.SiteDetailDto;
import com.fusionsolar.monitoring.dto.SiteDevicesResponseDto;
import com.fusionsolar.monitoring.dto.SiteSummaryDto;
import com.fusionsolar.monitoring.persistence.enums.Brand;

import java.util.List;

public interface MonitoringProvider {

    Brand getBrand();

    List<SiteSummaryDto> getSitesSummary();

    SiteDetailDto getSiteDetail(String siteCode);

    List<HistoryPointDto> getHistory(String siteCode, String type, String value);

    List<HistoryPointDto> getYearlyHistory(String siteCode);

    SiteDevicesResponseDto getSiteDevices(String siteCode);
}