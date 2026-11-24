package com.fusionsolar.monitoring.service;
import org.junit.jupiter.api.Test;
class LegacyFallbackEquivalenceTests {
    @Test void unavailableCountersPreserveEveryLegacyResultField() throws Exception {
        LegacyFallbackEquivalenceChecks.main(new String[0]);
    }
}
