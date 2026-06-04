package com.fusionsolar.monitoring.provider.huawei;

import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.dto.SiteDetailDto;
import com.fusionsolar.monitoring.dto.SiteDevicesResponseDto;
import com.fusionsolar.monitoring.dto.SiteSummaryDto;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.provider.MonitoringProvider;
import com.fusionsolar.monitoring.service.HuaweiLiveStationService;
import com.fusionsolar.monitoring.service.HuaweiLiveStationService.AccountSitesSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class HuaweiMonitoringProvider implements MonitoringProvider {

    private final HuaweiLiveStationService huaweiLiveStationService;

    @Override
    public Brand getBrand() {
        return Brand.HUAWEI;
    }

    @Override
    public List<SiteSummaryDto> getSitesSummary() {
        return huaweiLiveStationService.getSitesSummary();
    }

    public List<AccountSitesSummary> getSitesSummaryByAccountGroup() {
        return huaweiLiveStationService.getSitesSummaryByAccountGroup();
    }

    @Override
    public SiteDetailDto getSiteDetail(String siteCode) {
        return huaweiLiveStationService.getSiteDetail(siteCode);
    }

    @Override
    public List<HistoryPointDto> getHistory(String siteCode, String type, String value) {
        return huaweiLiveStationService.getHistory(siteCode, type, value);
    }

    @Override
    public List<HistoryPointDto> getYearlyHistory(String siteCode) {
        return huaweiLiveStationService.getYearlyHistory(siteCode);
    }

    @Override
    public SiteDevicesResponseDto getSiteDevices(String siteCode) {
        return huaweiLiveStationService.getSiteDevices(siteCode);
    }

}