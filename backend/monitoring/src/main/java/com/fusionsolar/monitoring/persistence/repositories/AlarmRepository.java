package com.fusionsolar.monitoring.persistence.repositories;

import com.fusionsolar.monitoring.persistence.document.AlarmDocument;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface AlarmRepository extends MongoRepository<AlarmDocument, String> {

    List<AlarmDocument> findAllByActiveTrue();

    Optional<AlarmDocument> findByAlarmKeyAndActiveTrue(String alarmKey);

    List<AlarmDocument> findByActiveFalseOrderByClearedAtDesc(Pageable pageable);
}
