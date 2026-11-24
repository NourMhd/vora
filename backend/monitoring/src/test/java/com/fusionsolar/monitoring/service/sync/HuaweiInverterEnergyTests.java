package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.dto.DeviceItemDto;
import com.fusionsolar.monitoring.persistence.document.SiteDeviceDocument;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HuaweiInverterEnergyTests {
    private final Instant now = Instant.parse("2026-11-24T10:00:00Z");
    private DeviceItemDto device(String id, int type) {
        var device = new DeviceItemDto(); device.setId(id); device.setDevTypeId(type); return device;
    }
    private SiteDeviceDocument site() {
        return SiteDeviceDocument.builder()
                .devices(List.of(device("1", 1), device("2", 38), device("3", 17), device("1", 1)))
                .inverterTotalKwh(Map.of("1", 1200.5, "2", 800.25, "3", 9999.0))
                .inverterTotalObservedAt(Map.of("1", now, "2", now)).build();
    }
    @Test void sumsDistinctInvertersWithoutAddingGridImportExportMeters() {
        assertEquals(2000.75, HuaweiInverterEnergySyncService.completeTotal(site(), now));
    }
    @Test void missingOrStaleSecondValueRequiresExistingLogic() {
        var site = site(); site.setInverterTotalKwh(Map.of("1", 1200.5));
        assertNull(HuaweiInverterEnergySyncService.completeTotal(site, now));
        site = site(); site.setInverterTotalObservedAt(Map.of("1", now, "2", now.minusSeconds(1201)));
        assertNull(HuaweiInverterEnergySyncService.completeTotal(site, now));
    }
    @Test void zeroIsValidButInvalidNumbersAndEmptyInventoriesAreNot() {
        var site = site(); site.setInverterTotalKwh(Map.of("1", 0.0, "2", 0.0));
        assertEquals(0.0, HuaweiInverterEnergySyncService.completeTotal(site, now));
        site.setInverterTotalKwh(Map.of("1", Double.NaN, "2", 1.0));
        assertNull(HuaweiInverterEnergySyncService.completeTotal(site, now));
        site.setDevices(List.of());
        assertNull(HuaweiInverterEnergySyncService.completeTotal(site, now));
    }
}
