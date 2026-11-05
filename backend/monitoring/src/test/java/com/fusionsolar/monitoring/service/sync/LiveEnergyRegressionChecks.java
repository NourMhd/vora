package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.enums.SiteStatus;
import com.fusionsolar.monitoring.provider.mapper.SiteMongoMapper;
import com.fusionsolar.monitoring.dto.SiteSummaryDto;
import java.time.*;
import java.util.*;

/** Offline checks of the actual Mongo update builders and API mapper. */
public class LiveEnergyRegressionChecks {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Instant now = Instant.parse("2026-10-09T09:00:00Z");
        var update = HuaweiStateSyncService.energyUpdate(Map.of("day_power", 0,
                "month_power", "123.45", "total_power", 9000), now);
        var fields = (org.bson.Document) update.getUpdateObject().get("$set");
        check(Double.valueOf(0).equals(fields.get("dayEnergyKwh")), "valid zero day must reset previous day");
        check(Double.valueOf(123.45).equals(fields.get("monthEnergyKwh")), "month parsed without unit conversion");
        check(Double.valueOf(9000).equals(fields.get("totalEnergyKwh")), "lifetime retained as lifetime");
        check(LocalDate.of(2026,10,9).equals(fields.get("energyDate")), "day attributed in Tunis timezone");
        check(!fields.containsKey("status") && !fields.containsKey("swapDate") && !fields.containsKey("active"), "energy write leaves other fields untouched");
        check(HuaweiStateSyncService.energyUpdate(Map.of("day_power", -1,"month_power","NaN","total_power","bad"),now)==null,"invalid energies produce no update");
        var totalOnly=(org.bson.Document)HuaweiStateSyncService.energyUpdate(Map.of("total_power",42),now).getUpdateObject().get("$set");
        check(!totalOnly.containsKey("dayEnergyKwh") && !totalOnly.containsKey("energyDate"),"missing day preserves previous value and date");
        check(HuaweiStateSyncService.nonNegativeEnergy("Infinity")==null,"infinity rejected");
        var midnight=(org.bson.Document)HuaweiStateSyncService.energyUpdate(Map.of("day_power",0),Instant.parse("2026-10-09T23:05:00Z")).getUpdateObject().get("$set");
        check(LocalDate.of(2026,10,10).equals(midnight.get("energyDate")),"Tunis day boundary");
        var site=SiteDocument.builder().id("s").brand(Brand.HUAWEI).siteCode("NE=1")
                .siteName("TUN_1").status(SiteStatus.HEALTHY).dayEnergyKwh(77.0).swapDate(LocalDate.of(2026,1,1)).build();
        var metadata=(org.bson.Document)HuaweiSyncService.inventoryUpdate(site).getUpdateObject().get("$set");
        check(!metadata.containsKey("status") && !metadata.containsKey("statusLastSyncAt"),"inventory cannot revert a newer state");
        check(!metadata.containsKey("dayEnergyKwh") && !metadata.containsKey("energyLastSyncAt") && !metadata.containsKey("swapDate"),"inventory cannot revert live energy or manual swap");
        check(metadata.containsKey("siteName") && metadata.containsKey("active"),"inventory still maintains metadata");
        var mapper=new SiteMongoMapper();
        var missing=new SiteSummaryDto("SOLIS","s","site","healthy",1.0,null,null,null,null,10000.0,null);
        var solis=mapper.toSiteDocument(Brand.SOLIS,missing,null,now);
        check(Double.valueOf(0).equals(solis.getYearEnergyKwh()),"missing year must not become lifetime total");
        var old=SiteDocument.builder().brand(Brand.SOLIS).yearEnergyKwh(10000.0).totalEnergyKwh(10000.0).build();
        check(Double.valueOf(0).equals(mapper.toSiteSummaryDto(old).getYearEnergyKwh()),"legacy unverified lifetime-as-year ignored immediately");
        var zero=new SiteSummaryDto("SOLIS","s","site","healthy",1.0,null,0.0,0.0,0.0,10000.0,null);
        var observed=mapper.toSiteDocument(Brand.SOLIS,zero,null,now);
        check(Double.valueOf(0).equals(observed.getYearEnergyKwh()),"official zero year remains zero");
        check(now.equals(observed.getYearEnergyLastSyncAt()),"valid year observation receives timestamp");
        var real=new SiteSummaryDto("SOLIS","s","site","healthy",1.0,null,1.0,20.0,300.0,10000.0,null);
        check(Double.valueOf(300).equals(mapper.toSiteDocument(Brand.SOLIS,real,null,now).getYearEnergyKwh()),"official year retained");
        System.out.println("PASS: "+checks+" live energy regression checks");
    }
}
