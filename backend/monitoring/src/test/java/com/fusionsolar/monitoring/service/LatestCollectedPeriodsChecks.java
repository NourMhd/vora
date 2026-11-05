package com.fusionsolar.monitoring.service;
import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.persistence.document.SiteHistoryDocument;
import java.time.*;
import java.util.*;

public class LatestCollectedPeriodsChecks {
    private static int checks;
    private static void check(boolean value) {checks++; if(!value)throw new AssertionError("check "+checks);}
    private static SiteHistoryDocument history(String group, String date, Double value) {
        var doc=new SiteHistoryDocument();doc.setProviderAccountGroup(group);
        doc.setPoints(List.of(new HistoryPointDto(Instant.parse(date).toEpochMilli(),value)));return doc;
    }
    public static void main(String[] args) {
        checks=0;Instant now=Instant.parse("2026-11-02T12:00:00Z");
        var old=history("a","2026-09-12T11:00:00Z",12.0);
        var zero=history("a","2026-10-30T11:00:00Z",0.0);
        var other=history("b","2026-11-01T11:00:00Z",200.0);
        var future=history("a","2027-01-01T11:00:00Z",200.0);
        var invalid=history("a","2026-11-01T11:00:00Z",Double.NaN);
        check(LatestCollectedPeriods.latest(List.of(old,zero,other,future,invalid),"a","month",now).orElseThrow().equals("2026-10"));
        check(LatestCollectedPeriods.latest(List.of(old,zero),"a","day",now).orElseThrow().equals("2026-10-30"));
        check(LatestCollectedPeriods.latest(List.of(old),"a","year",now).orElseThrow().equals("2026"));
        check(LatestCollectedPeriods.latest(List.of(other),"a","day",now).isEmpty());
        check(LatestCollectedPeriods.latest(List.of(future,invalid),"a","day",now).isEmpty());
        check(LatestCollectedPeriods.latest(List.of(),"a","month",now).isEmpty());
        System.out.println("PASS: "+checks+" latest collected period checks");
    }
}
