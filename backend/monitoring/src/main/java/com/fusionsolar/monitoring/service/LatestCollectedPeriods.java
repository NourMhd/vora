package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.persistence.document.SiteHistoryDocument;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class LatestCollectedPeriods {
    private LatestCollectedPeriods() { }
    public static Optional<String> latest(List<SiteHistoryDocument> histories, String group, String type, Instant now) {
        ZoneId zone = ZoneId.of("Africa/Tunis");
        return histories.stream().filter(Objects::nonNull)
                .filter(d -> d.getProviderAccountGroup() == null || d.getProviderAccountGroup().isBlank()
                        || Objects.equals(group, d.getProviderAccountGroup()))
                .filter(d -> d.getPoints() != null)
                .flatMap(d -> d.getPoints().stream())
                .filter(p -> p != null && p.getCollectTime() != null && p.getValue() != null
                        && Double.isFinite(p.getValue()) && p.getValue() >= 0
                        && p.getCollectTime() > 0 && p.getCollectTime() <= now.toEpochMilli())
                .map(p -> Instant.ofEpochMilli(p.getCollectTime()).atZone(zone).toLocalDate())
                .max(Comparator.naturalOrder()).map(date -> switch(type) {
                    case "day" -> date.toString();
                    case "month" -> date.format(DateTimeFormatter.ofPattern("yyyy-MM"));
                    case "year" -> Integer.toString(date.getYear());
                    default -> throw new IllegalArgumentException("Unsupported history type");
                });
    }
}
