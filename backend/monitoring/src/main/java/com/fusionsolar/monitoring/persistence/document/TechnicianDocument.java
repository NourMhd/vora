package com.fusionsolar.monitoring.persistence.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "technicians")
public class TechnicianDocument {

    @Id
    private String id;

    private String username;

    private String passwordHash;

    private String displayName;

    private String role;

    private Boolean active;

    private Instant createdAt;

    private Instant lastLoginAt;
}