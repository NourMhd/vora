package com.fusionsolar.monitoring.service;
import org.junit.jupiter.api.Test;
class HuaweiLifetimeReportTests {
    @Test void reportUsesWeightedTotalsAndFallsBackToHistory() throws Exception {
        HuaweiLifetimeReportChecks.main(new String[0]);
    }
}
