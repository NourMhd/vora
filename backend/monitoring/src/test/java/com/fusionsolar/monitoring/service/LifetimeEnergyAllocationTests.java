package com.fusionsolar.monitoring.service;
import org.junit.jupiter.api.Test;
class LifetimeEnergyAllocationTests {
    @Test void allocatesByTheoreticalWeightsAndConservesTheTotal() {
        LifetimeEnergyAllocationChecks.main(new String[0]);
    }
}
