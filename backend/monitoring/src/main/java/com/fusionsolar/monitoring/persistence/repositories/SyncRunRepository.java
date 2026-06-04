package com.fusionsolar.monitoring.persistence.repositories;

import com.fusionsolar.monitoring.persistence.document.SyncRunDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface SyncRunRepository extends MongoRepository<SyncRunDocument, String> {

    List<SyncRunDocument> findTop20ByBrandOrderByStartedAtDesc(Brand brand);

    Optional<SyncRunDocument> findTopByBrandAndTypeOrderByStartedAtDesc(Brand brand, String type);

    Optional<SyncRunDocument> findTopByTypeOrderByStartedAtDesc(String type);
}
