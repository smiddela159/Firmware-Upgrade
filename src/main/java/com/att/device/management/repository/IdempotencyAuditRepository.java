package com.att.device.management.repository;

import com.att.device.management.model.IdempotencyAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface IdempotencyAuditRepository extends JpaRepository<IdempotencyAudit, Long> {
    Optional<IdempotencyAudit> findByKey(String key);
}

