package com.workbench.mcp.repository;

import com.workbench.mcp.entity.TaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TaskRepository extends JpaRepository<TaskEntity, String> {

    Optional<TaskEntity> findByCaseIdAndTaskType(String caseId, String taskType);
}
