package com.fusionsolar.monitoring.persistence.repositories;

import com.fusionsolar.monitoring.persistence.document.TechnicianDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface TechnicianRepository extends MongoRepository<TechnicianDocument, String> {

    Optional<TechnicianDocument> findByUsername(String username);
}