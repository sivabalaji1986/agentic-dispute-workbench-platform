# agentic-dispute-workbench-platform — Platform Contract

**Version:** 1.0 — locked before Session 1. All module prompts cite this document.
**Companion:** `agentic-dispute-workbench-ui` spec at
`docs/superpowers/specs/2026-07-13-agentic-dispute-workbench-ui-design.md` (frozen).

---

## 1. Repository layout

```
agentic-dispute-workbench-platform/
├── pom.xml                    (parent — Boot 4 + Spring AI 2.0 BOMs, no code)
├── workbench-common/          (shared DTOs, no Spring dependencies)
├── agui-support/              (AG-UI event types + SSE emitter, WebFlux only)
├── case-system-mcp/           (Spring Boot app — MCP server over Postgres)
├── case-review-agent/         (Spring Boot app — A2A server, MCP client reads)
├── policy-agent/              (Spring Boot app — A2A server, RAG)
├── orchestrator-agent/        (Spring Boot app — AG-UI server, A2A client, MCP client writes)
└── infra/
    ├── docker-compose.yml
    ├── seed/
    │   ├── schema.sql
    │   ├── seed-data.sql
    │   └── policy-document.txt   (bank T&C — Goods Not Received section minimum)
    └── scripts/
        └── ingest-policy.sh      (embeds policy-document.txt into pgvector)
```

---

## 2. Tech stack — locked versions

| Concern | Artifact | Version |
|---|---|---|
| JDK | GraalVM / Eclipse Temurin 25 | 25 |
| Spring Boot | `spring-boot-starter-parent` | 4.0.x (latest GA) |
| Spring AI | `spring-ai-bom` | 2.0.0 |
| A2A Java SDK | `io.a2a:a2a-core`, `a2a-client`, `a2a-server` | latest GA from `a2aproject/a2a-java` |
| spring-ai-a2a | `org.springaicommunity:spring-ai-a2a-server-autoconfigure` | latest compatible with Spring AI 2.0 |
| AG-UI core types | `com.ag-ui:core` | 0.0.1 (types only — see §6) |
| Jackson | `tools.jackson.core` / `tools.jackson.databind` | 3.x (Boot 4 default — see package note below) |
| PostgreSQL driver | `postgresql` | Boot 4 managed |
| Spring Data JPA | `spring-boot-starter-data-jpa` | Boot 4 managed |
| Spring WebFlux | `spring-boot-starter-webflux` | Boot 4 managed (orchestrator only) |
| pgvector Java | `com.pgvector:pgvector` | latest |
| Testcontainers | `testcontainers-bom` | latest compatible with Boot 4 |
| JUnit | JUnit 5 only — JUnit 4 removed in Boot 4 | — |

**Jackson 3 package rename** (Boot 4 breaking change): `ObjectMapper` and all
core streaming/databind types use `tools.jackson.core.*` / `tools.jackson.databind.*`.
Never `com.fasterxml.jackson.core` or `com.fasterxml.jackson.databind` anywhere
in the platform — mixing Jackson 2 and Jackson 3 core/databind classes is
binary-incompatible and fails at runtime.

**Correction (verified against Maven Central 2026-07-15):** the
`jackson-annotations` module was **not** renamed in Jackson 3. Jackson 3's own
`jackson-base` POM depends on it verbatim ("depends on Jackson 2.x
annotations"), and `tools.jackson.core:jackson-annotations` does not exist as
a published artifact (404 on Maven Central). Annotation types —
`@JsonProperty`, `@JsonInclude`, `@JsonIgnoreProperties`, etc. — are imported
from `com.fasterxml.jackson.annotation.*` even in a pure Jackson-3/Boot-4
codebase. This is the one permitted exception to the "never
`com.fasterxml.jackson.*`" rule; it applies only to the `annotation`
subpackage, never to `.core` or `.databind`.

**Build:** Maven multi-module. Parent POM manages all versions via BOMs.
Child modules inherit; no version declared in child POMs except for
libraries not in a BOM.

---

## 3. Spring profiles

| Profile | LLM | Notes |
|---|---|---|
| `default` / `ollama` | Ollama local | `spring.ai.ollama.base-url=http://localhost:11434`. Recommend `qwen2.5:14b` or equivalent tool-capable model. |
| `anthropic` | Anthropic API | `ANTHROPIC_API_KEY` env var. Model: `claude-sonnet-4-6`. |
| `openai` | OpenAI API | `OPENAI_API_KEY` env var. |

Profile is set via `SPRING_PROFILES_ACTIVE`. Embedding model for RAG follows the same profile (Ollama: `nomic-embed-text`; Anthropic/OpenAI: their respective embedding models). One property change, zero code change.

---

## 4. Database schema

Single PostgreSQL 16 + pgvector instance. All relational tables in schema `workbench`.

```sql
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
    created_at     TIMESTAMPTZ DEFAULT NOW()
);

-- audit_entries
CREATE TABLE workbench.audit_entries (
    entry_id       SERIAL PRIMARY KEY,
    case_id        VARCHAR(20) REFERENCES workbench.cases(case_id),
    action         VARCHAR(100) NOT NULL,
    performed_by   VARCHAR(50) DEFAULT 'ORCHESTRATOR',
    performed_at   TIMESTAMPTZ DEFAULT NOW()
);

-- pgvector table (Spring AI managed — do not create manually)
-- Spring AI auto-creates vector_store table on startup when ddl-auto=update
```

**Seed data (demo case):**

```sql
INSERT INTO workbench.cases VALUES
  ('D-10291', 'I paid SGD 250 for an item, but I never received it. The merchant says the item was delivered, but I disagree.',
   'GOODS_NOT_RECEIVED', 'OPEN', 250.00, 'SGD', NOW(), NOW());

INSERT INTO workbench.transactions VALUES
  ('TXN-55820', 'D-10291', 250.00, 'SGD', 'ShopFast Pte Ltd', '2026-07-01', 'Item was delivered');

INSERT INTO workbench.evidence_documents VALUES
  (DEFAULT, 'D-10291', 'TRANSACTION_RECORD', TRUE, NOW()),
  (DEFAULT, 'D-10291', 'MERCHANT_RESPONSE',  TRUE, NOW());
-- customer_declaration and delivery_proof are intentionally absent
```

---

## 5. Port assignments

| Service | Port |
|---|---|
| orchestrator-agent | 8080 |
| case-review-agent | 8081 |
| policy-agent | 8082 |
| case-system-mcp | 8083 |
| PostgreSQL | 5432 |
| Ollama (external) | 11434 |

---

## 6. AG-UI wire contract

The orchestrator is the **AG-UI server**; the UI is the **AG-UI client**.
Endpoint: `GET /agui` (SSE, `text/event-stream`). Request carries
`threadId` and `input` (first run) or `forwardedProps.a2uiAction` (action runs).

### 6.1 Event types emitted (in order per run kind)

All events are JSON objects, one per SSE `data:` line.
Use `com.ag-ui:core` typed classes where they exist; fall back to plain Java
records with matching field names if the 0.0.1 types are Jackson-3-incompatible.

```
RUN_STARTED        {type, threadId, runId}
CUSTOM/progress    {type:"CUSTOM", name:"progress", value:{source, text}}
CUSTOM/a2ui        {type:"CUSTOM", name:"a2ui",     value:<A2uiMessage>}
STATE_SNAPSHOT     {type:"STATE_SNAPSHOT", snapshot:{evidenceReadiness:null}}
STATE_DELTA        {type:"STATE_DELTA",    delta:[{op, path, value}]}   -- RFC 6902
RUN_FINISHED       {type, threadId, runId}
RUN_ERROR          {type, threadId, runId, message, code?}
```

### 6.2 Progress source values (frozen)

`source` ∈ `{ "orchestrator", "case-review", "policy" }` — exact strings,
lowercase with hyphen. The UI validates against this enum.

### 6.3 Run sequence for the demo scenario

**Run 1 — review run** (triggered by initial dispute submission):
```
RUN_STARTED
progress: orchestrator "Understanding dispute..."
progress: orchestrator "Dispute type detected: Goods Not Received"
progress: orchestrator "Preparing specialist review..."
STATE_SNAPSHOT  {evidenceReadiness: null}
progress: orchestrator "Calling Case Review Agent..."
progress: orchestrator "Calling Policy Agent..."
[interleaved — exact order varies by timing:]
progress: case-review "Checking transaction status..."
progress: policy     "Searching policy document..."
progress: case-review "Transaction found for SGD 250"
progress: policy     "Goods Not Received policy section found"
progress: case-review "Merchant response available"
progress: policy     "Interpreting policy requirements"
progress: case-review "Case file contains transaction record"
progress: policy     "Required evidence list identified"
progress: case-review "Case file contains merchant response"
progress: case-review "No additional customer documents found in case file"
progress: orchestrator "Merging case facts with policy requirements..."
progress: orchestrator "Comparing available documents against required evidence..."
progress: orchestrator "Missing customer declaration"
progress: orchestrator "Missing delivery / non-delivery proof"
progress: orchestrator "Calculating evidence readiness..."
STATE_DELTA     [{op:"replace", path:"/evidenceReadiness", value:"2 of 4 required items present"}]
progress: orchestrator "Preparing decision view..."
CUSTOM/a2ui     createSurface
CUSTOM/a2ui     updateComponents  [DecisionCard, EvidenceChecklist, NextActions]
RUN_FINISHED
```

**Run 2 — preview run** (triggered by `create_evidence_request_task` action):
```
RUN_STARTED
progress: orchestrator "Preparing evidence request task preview..."
CUSTOM/a2ui     updateComponents  [ApprovalPreview]   (same surfaceId)
RUN_FINISHED
```

**Run 3 — approval run** (triggered by `approve_task_creation` action):
```
RUN_STARTED
progress: orchestrator "Creating evidence request task..."
progress: orchestrator "Updating case status to Pending Evidence..."
progress: orchestrator "Creating audit entry..."
progress: orchestrator "Task created successfully."
CUSTOM/a2ui     updateComponents  [TaskCreatedCard]   (same surfaceId)
RUN_FINISHED
```

**Run 4 — cancel run** (triggered by `cancel_task_creation` action):
```
RUN_STARTED
progress: orchestrator "Cancelling evidence request..."
CUSTOM/a2ui     updateComponents  [DecisionCard, EvidenceChecklist, NextActions]
RUN_FINISHED
```

### 6.4 Approval idempotency (mandatory)

If `approve_task_creation` arrives for a `(threadId, surfaceId)` pair that has
already executed, the orchestrator does NOT write again. It re-emits
the terminal state (TaskCreatedCard) and RUN_FINISHED. Keyed on the
`pending_approval` state held in-memory (or a small DB flag) per session.

---

## 7. A2UI payload shapes (v0.9)

All A2UI messages ride inside `CUSTOM` events: `{type:"CUSTOM", name:"a2ui", value:<msg>}`.
Each message: `{version:"v0.9", <key>: {...}}`.

### 7.1 createSurface (sent once per session, idempotent)
```json
{
  "version": "v0.9",
  "createSurface": {
    "surfaceId": "case-D-10291",
    "catalogId": "https://dispute-workbench.internal/catalogs/v1.json"
  }
}
```

### 7.2 updateComponents (flat entries, id-referenced composition)
```json
{
  "version": "v0.9",
  "updateComponents": {
    "surfaceId": "case-D-10291",
    "components": [
      {
        "id": "decision-1",
        "component": "DecisionCard",
        "status": "Needs More Evidence",
        "disputeType": "Goods Not Received",
        "evidenceReadiness": "2 of 4 required items present",
        "recommendedAction": "Create evidence request task",
        "checklistId": "checklist-1",
        "actionsId": "actions-1"
      },
      {
        "id": "checklist-1",
        "component": "EvidenceChecklist",
        "items": [
          {"label": "Transaction record",       "present": true},
          {"label": "Merchant response",        "present": true},
          {"label": "Customer declaration",     "present": false},
          {"label": "Delivery / non-delivery proof", "present": false}
        ]
      },
      {
        "id": "actions-1",
        "component": "NextActions",
        "actions": [
          {"id": "create_evidence_request_task", "label": "Create Evidence Request Task"},
          {"id": "escalate_to_reviewer",         "label": "Escalate to Reviewer"},
          {"id": "save_case_note",               "label": "Save Case Note"}
        ]
      }
    ]
  }
}
```

**ApprovalPreview** (run 2):
```json
{
  "id": "approval-1",
  "component": "ApprovalPreview",
  "caseId": "D-10291",
  "newCaseStatus": "Pending Evidence",
  "missingItems": ["Customer declaration", "Delivery / non-delivery proof"],
  "actionAfterApproval": "Create task in case system and update case status.",
  "onApprove": {"id": "approve_task_creation"},
  "onEdit":    {"id": "edit_task_creation"},
  "onCancel":  {"id": "cancel_task_creation"}
}
```

**TaskCreatedCard** (run 3):
```json
{
  "id": "task-created-1",
  "component": "TaskCreatedCard",
  "taskId": "EVID-88421",
  "caseStatus": "Pending Evidence",
  "auditEntry": "Created",
  "nextOwner": "Dispute Operations Queue"
}
```

---

## 8. A2A contract

### 8.1 Agent Cards

**Case Review Agent** — `http://localhost:8081/.well-known/agent-card.json`:
```json
{
  "name": "case-review-agent",
  "description": "Reviews operational case facts: transaction, merchant response, and available evidence documents.",
  "url": "http://localhost:8081/a2a",
  "version": "1.0.0",
  "capabilities": {"streaming": false}
}
```

**Policy Agent** — `http://localhost:8082/.well-known/agent-card.json`:
```json
{
  "name": "policy-agent",
  "description": "Interprets dispute policy: retrieves the relevant clause via RAG, identifies required evidence, returns reasoning and policy outcome.",
  "url": "http://localhost:8082/a2a",
  "version": "1.0.0",
  "capabilities": {"streaming": false}
}
```

### 8.2 Case Review Agent — request / response

**Input message (from orchestrator):**
```
Check transaction, merchant response, case status and available evidence for dispute case {caseId}, dispute type {disputeType}.
```

**Response payload (structured text the orchestrator parses):**
```json
{
  "caseId": "D-10291",
  "transactionFound": true,
  "transactionAmount": "SGD 250",
  "merchantResponse": "available",
  "merchantPosition": "Item was delivered",
  "availableDocuments": ["TRANSACTION_RECORD", "MERCHANT_RESPONSE"],
  "caseStatus": "OPEN"
}
```

Progress lines emitted during execution (streamed back to orchestrator for forwarding):
```
Checking transaction status...
Transaction found for SGD 250
Merchant response available
Case file contains transaction record
Case file contains merchant response
No additional customer documents found in case file
```

### 8.3 Policy Agent — request / response

**Input message (from orchestrator):**
```
Interpret the dispute policy for dispute type {disputeType} and identify the required evidence.
```

**Response payload:**
```json
{
  "disputeType": "GOODS_NOT_RECEIVED",
  "policySection": "Section 4.2 — Goods Not Received",
  "policyInterpretation": "This case qualifies as Goods Not Received because the customer claims non-delivery while the merchant asserts delivery. Under the disputed-delivery sub-clause, customer declaration and delivery dispute proof are required in addition to the transaction record and merchant response.",
  "requiredEvidence": [
    "TRANSACTION_RECORD",
    "MERCHANT_RESPONSE",
    "CUSTOMER_DECLARATION",
    "DELIVERY_DISPUTE_PROOF"
  ],
  "policyOutcome": "Potentially eligible, but evidence is incomplete."
}
```

Progress lines:
```
Searching policy document...
Goods Not Received policy section found
Interpreting policy requirements
Required evidence list identified
```

---

## 9. MCP tool contract

**Case-System MCP Server** — `http://localhost:8083` (Streamable HTTP transport,
Spring AI MCP default).

### Read tools (Case Review Agent uses these)

**`get_case`**
- Input: `{ "caseId": "D-10291" }`
- Output: case row as JSON (all columns)

**`list_case_documents`**
- Input: `{ "caseId": "D-10291" }`
- Output: `{ "documents": [{ "docType": "TRANSACTION_RECORD", "present": true }, ...] }`

### Write tools (Orchestrator uses after human approval ONLY)

**`create_task`**
- Input: `{ "caseId": "D-10291", "taskType": "MISSING_EVIDENCE_REQUEST", "missingItems": ["CUSTOMER_DECLARATION", "DELIVERY_DISPUTE_PROOF"], "assignedQueue": "Dispute Operations" }`
- Output: `{ "taskId": "EVID-88421", "createdAt": "<ISO>" }`
- **Idempotent:** if a task of the same type already exists for the case, returns existing task; does not insert twice.

**`update_case_status`**
- Input: `{ "caseId": "D-10291", "newStatus": "PENDING_EVIDENCE" }`
- Output: `{ "caseId": "D-10291", "status": "PENDING_EVIDENCE", "updatedAt": "<ISO>" }`

**`create_audit_entry`**
- Input: `{ "caseId": "D-10291", "action": "EVIDENCE_REQUEST_TASK_CREATED", "performedBy": "ORCHESTRATOR" }`
- Output: `{ "entryId": 1, "createdAt": "<ISO>" }`

---

## 10. workbench-common — shared DTOs

Package: `com.workbench.common`. No Spring annotations. Records preferred.

```java
// A2A request/response
record CaseReviewRequest(String caseId, String disputeType) {}
record CaseReviewResult(
    String caseId, boolean transactionFound, String transactionAmount,
    String merchantResponse, String merchantPosition,
    List<String> availableDocuments, String caseStatus) {}

record PolicyRequest(String disputeType) {}
record PolicyResult(
    String disputeType, String policySection, String policyInterpretation,
    List<String> requiredEvidence, String policyOutcome) {}

// Orchestrator merge output
record EvidenceReadiness(
    int present, int required,
    List<String> missingItems,
    String label) {                              // "2 of 4 required items present"
    static EvidenceReadiness compute(
        List<String> available, List<String> required) { ... }
}

// Pending approval state (held in orchestrator memory, keyed by threadId+surfaceId)
record PendingApproval(
    String threadId, String surfaceId,
    String caseId, String taskType,
    List<String> missingItems, boolean executed) {}
```

---

## 11. agui-support — AG-UI emitter

Package: `com.workbench.agui`. Depends on `workbench-common` and WebFlux only.

Key types:

```java
// Event records mirroring AG-UI spec field names exactly
// Use com.ag-ui:core classes if Jackson-3 compatible; otherwise use these records:
record RunStartedEvent(String type, String threadId, String runId) {
    RunStartedEvent(String threadId, String runId) {
        this("RUN_STARTED", threadId, runId);
    }
}
record RunFinishedEvent(String type, String threadId, String runId) { ... }
record RunErrorEvent(String type, String threadId, String runId, String message) { ... }
record CustomEvent(String type, String name, Object value) {
    // factory methods:
    static CustomEvent progress(String source, String text) { ... }
    static CustomEvent a2ui(Object a2uiMessage) { ... }
}
record StateDeltaEvent(String type, List<JsonPatchOp> delta) { ... }
record StateSnapshotEvent(String type, Object snapshot) { ... }
record JsonPatchOp(String op, String path, Object value) { ... }

// Emitter — wraps a Sinks.Many<ServerSentEvent<String>>
public class AguiEmitter {
    public void emit(Object event);         // serializes to JSON, pushes to sink
    public void complete();
    public void error(String msg);
    public Flux<ServerSentEvent<String>> flux();
}

// A2UI message builders
public class A2uiMessages {
    public static Object createSurface(String surfaceId, String catalogId);
    public static Object updateComponents(String surfaceId, List<Object> components);
    // component builders:
    public static Object decisionCard(String id, String checklistId, String actionsId,
        String status, String disputeType, String evidenceReadiness, String recommendedAction);
    public static Object evidenceChecklist(String id, List<EvidenceItem> items);
    public static Object nextActions(String id, List<ActionItem> actions);
    public static Object approvalPreview(String id, String caseId,
        String newCaseStatus, List<String> missingItems, String actionAfterApproval);
    public static Object taskCreatedCard(String id, String taskId,
        String caseStatus, String auditEntry, String nextOwner);
}
```

Jackson serialization: use `@JsonProperty` to ensure camelCase field names
match the UI's frozen contract exactly (`threadId` not `thread_id`, etc.).
Verify against §7 payload shapes.

---

## 12. Infra — docker-compose

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_DB: workbench
      POSTGRES_USER: workbench
      POSTGRES_PASSWORD: workbench
    ports: ["5432:5432"]
    volumes:
      - ./seed/schema.sql:/docker-entrypoint-initdb.d/01-schema.sql
      - ./seed/seed-data.sql:/docker-entrypoint-initdb.d/02-seed-data.sql
      - pgdata:/var/lib/postgresql/data

  ollama:
    image: ollama/ollama:latest
    ports: ["11434:11434"]
    volumes: ["ollama_data:/root/.ollama"]
    # Pull model manually: docker exec <container> ollama pull qwen2.5:14b

volumes:
  pgdata:
  ollama_data:
```

`ingest-policy.sh` calls the orchestrator's `/admin/ingest` endpoint (or a
standalone Spring Boot runner) which reads `policy-document.txt`, chunks it,
embeds it via Spring AI's `VectorStore`, and stores into pgvector. Run once
after `docker compose up`.

---

## 13. Contract fixtures (cross-language validation)

The UI repo contains `src/test/fixtures/*.ndjson` — each line is one
AG-UI event exactly as the frontend expects on the wire. These are
the acceptance criteria for the platform.

The orchestrator integration tests MUST replay the equivalent event sequence
and assert it matches the fixture shapes. Specifically:

- `review-success.ndjson` — all events from Run 1 in order (progress interleaving
  is timing-dependent; assert all lines present regardless of order within
  the parallel phase).
- `preview-success.ndjson` — Run 2.
- `approval-success.ndjson` — Run 3 including MCP write events.
- `cancel-success.ndjson` — Run 4.

**Key serialization assertions:**
- `type` values are `SCREAMING_SNAKE_CASE` strings matching `@ag-ui/core` EventType enum exactly.
- `source` values are `"orchestrator"`, `"case-review"`, `"policy"` (lowercase, hyphenated).
- A2UI `version` field is the string `"v0.9"` exactly.
- All A2UI field names are camelCase (`surfaceId`, `checklistId`, not `surface_id`).
- `evidenceReadiness` in STATE_DELTA value is `"2 of 4 required items present"` exactly.

---

## 14. Build + test requirements per module

Each module must pass independently before the next session begins.

| Module | Test requirement |
|---|---|
| `workbench-common` | Unit tests for `EvidenceReadiness.compute()` |
| `agui-support` | Unit tests: emitter emits correct JSON per event type; A2uiMessages builders produce §7-conformant JSON |
| `case-system-mcp` | Testcontainers: all 5 MCP tools against real Postgres; idempotency test for `create_task` |
| `case-review-agent` | Testcontainers: full A2A request → real Postgres → structured response; progress lines present |
| `policy-agent` | Integration test with embedded vector store OR WireMock for Ollama; response includes all §8.3 fields |
| `orchestrator-agent` | Full run-1 through run-4 against mock A2A agents + mock MCP; assert emitted events match fixture shapes |

---

## 15. Session build order

| Session | Modules | Dependency |
|---|---|---|
| 1 | `workbench-common`, `agui-support` | none |
| 2 | `case-system-mcp` | Postgres (docker-compose up) |
| 3 | `case-review-agent` | case-system-mcp running |
| 4 | `policy-agent` | Ollama + policy doc ingested |
| 5 | `orchestrator-agent` | all of above |
| 6 | `infra` (compose + smoke test) | all services |

Each session prompt will say: "PLATFORM_CONTRACT.md is the source of truth.
If anything in this prompt conflicts with PLATFORM_CONTRACT.md, the contract wins."
