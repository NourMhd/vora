package com.fusionsolar.monitoring.service.sync;

import org.junit.jupiter.api.Test;

class SyncRegressionTests {
    @Test
    void inventoriesStatesAndArchiving() throws Exception {
        SyncRegressionChecks.main(new String[0]);
    }
}
