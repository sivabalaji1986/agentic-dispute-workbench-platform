package com.workbench.mcp.repository;

import com.workbench.mcp.entity.AuditEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEntryRepository extends JpaRepository<AuditEntryEntity, Long> {
}
