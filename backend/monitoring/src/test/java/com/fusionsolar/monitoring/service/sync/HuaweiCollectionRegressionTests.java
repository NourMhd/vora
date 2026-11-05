package com.fusionsolar.monitoring.service.sync;
import org.junit.jupiter.api.Test;
class HuaweiCollectionRegressionTests {
    @Test void historyProgressesWhileStatesAreStale() throws Exception {
        HuaweiCollectionRegressionChecks.main(new String[0]);
    }
}
