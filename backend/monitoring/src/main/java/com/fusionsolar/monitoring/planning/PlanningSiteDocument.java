package com.fusionsolar.monitoring.planning;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

/** Planning-only inventory. Never persisted as a monitoring SiteDocument. */
@Document("field_sites")
public class PlanningSiteDocument {
    @Id public String id;
    public String owner;
    public String normalizedName;
    public String name;
    public Instant createdAt;
    public Instant deletedAt;
}
