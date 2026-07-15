package com.workbench.mcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "tasks")
public class TaskEntity {

    @Id
    @Column(name = "task_id")
    private String taskId;

    @Column(name = "case_id")
    private String caseId;

    @Column(name = "task_type", nullable = false)
    private String taskType;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "missing_items", columnDefinition = "text[]")
    private String[] missingItems;

    @Column(name = "assigned_queue")
    private String assignedQueue;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public String getTaskType() {
        return taskType;
    }

    public void setTaskType(String taskType) {
        this.taskType = taskType;
    }

    public String[] getMissingItems() {
        return missingItems;
    }

    public void setMissingItems(String[] missingItems) {
        this.missingItems = missingItems;
    }

    public String getAssignedQueue() {
        return assignedQueue;
    }

    public void setAssignedQueue(String assignedQueue) {
        this.assignedQueue = assignedQueue;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
