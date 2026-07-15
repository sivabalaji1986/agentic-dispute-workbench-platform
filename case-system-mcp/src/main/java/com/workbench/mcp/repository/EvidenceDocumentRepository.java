package com.workbench.mcp.repository;

import com.workbench.mcp.entity.EvidenceDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvidenceDocumentRepository extends JpaRepository<EvidenceDocumentEntity, Long> {

    List<EvidenceDocumentEntity> findByCaseId(String caseId);
}
