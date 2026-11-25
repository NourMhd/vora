package com.fusionsolar.monitoring.service;
import org.junit.jupiter.api.Test;
class HuaweiForecastStabilityTests {
    @Test void counterAvailabilityNeverChangesForecastOrReportWindow() throws Exception {
        HuaweiForecastStabilityChecks.main(new String[0]);
    }
}
