package com.fusionsolar.monitoring.persistence.repositories;

import com.fusionsolar.monitoring.persistence.document.ProviderConfigDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ProviderConfigRepository extends MongoRepository<ProviderConfigDocument, String> {

    Optional<ProviderConfigDocument> findByBrand(Brand brand);
}