CREATE SCHEMA IF NOT EXISTS workbench;

-- cases
CREATE TABLE workbench.cases (
    case_id        VARCHAR(20) PRIMARY KEY,         -- e.g. 'D-10291'
    dispute_text   TEXT NOT NULL,
    dispute_type   VARCHAR(50),                     -- set after classification
    case_status    VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    amount         NUMERIC(10,2),
    currency       VARCHAR(3) DEFAULT 'SGD',
    created_at     TIMESTAMPTZ DEFAULT NOW(),
    updated_at     TIMESTAMPTZ DEFAULT NOW()
);

-- transactions
CREATE TABLE workbench.transactions (
    txn_id         VARCHAR(20) PRIMARY KEY,
    case_id        VARCHAR(20) REFERENCES workbench.cases(case_id),
    amount         NUMERIC(10,2),
    currency       VARCHAR(3),
    merchant_name  VARCHAR(100),
    txn_date       DATE,
    merchant_position VARCHAR(200)                  -- e.g. 'Item was delivered'
);

-- evidence_documents
CREATE TABLE workbench.evidence_documents (
    doc_id         SERIAL PRIMARY KEY,
    case_id        VARCHAR(20) REFERENCES workbench.cases(case_id),
    doc_type       VARCHAR(50) NOT NULL,            -- TRANSACTION_RECORD, MERCHANT_RESPONSE, etc.
    present        BOOLEAN NOT NULL DEFAULT TRUE,
    uploaded_at    TIMESTAMPTZ DEFAULT NOW()
);

-- tasks
CREATE TABLE workbench.tasks (
    task_id        VARCHAR(20) PRIMARY KEY,         -- e.g. 'EVID-88421'
    case_id        VARCHAR(20) REFERENCES workbench.cases(case_id),
    task_type      VARCHAR(50) NOT NULL,
    missing_items  TEXT[],
    assigned_queue VARCHAR(50),
    created_at     TIMESTAMPTZ DEFAULT NOW(),
    -- Backstops create_task's application-level idempotency check (find-then-insert)
    -- against true concurrent calls for the same case + task type. ddl-auto=validate
    -- does not check unique constraints, so this is additive and safe.
    CONSTRAINT uq_tasks_case_id_task_type UNIQUE (case_id, task_type)
);

-- audit_entries
CREATE TABLE workbench.audit_entries (
    entry_id       SERIAL PRIMARY KEY,
    case_id        VARCHAR(20) REFERENCES workbench.cases(case_id),
    action         VARCHAR(100) NOT NULL,
    performed_by   VARCHAR(50) DEFAULT 'ORCHESTRATOR',
    performed_at   TIMESTAMPTZ DEFAULT NOW()
);
