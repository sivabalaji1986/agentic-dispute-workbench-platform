package com.workbench.mcp.repository;

import com.workbench.mcp.entity.CaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseRepository extends JpaRepository<CaseEntity, String> {
}
