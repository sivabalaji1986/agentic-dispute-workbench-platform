# Session 2 — case-system-mcp

**Source of truth:** `PLATFORM_CONTRACT.md` in the repo root.
If anything below conflicts with the contract, the contract wins.

## Context

You are adding the `case-system-mcp` module to
`agentic-dispute-workbench-platform`. This is a standalone Spring Boot 4
application that exposes the case database as MCP tools over Streamable HTTP
transport. It is the only module that owns write access to the database.

The Case Review Agent (Session 3) will use its read tools.
The Orchestrator (Session 5) will use its write tools — but ONLY after
human approval; never speculatively.

Before writing any code:
1. Re-read `PLATFORM_CONTRACT.md` §4 (schema), §9 (MCP tools), §14 (test
   requirements), §15 (session order).
2. Check the currently published version of the Spring AI MCP server starter
   (`spring-ai-mcp-server-spring-boot-starter` or its current artifact name
   in Spring AI 2.0) — do NOT assume the artifact ID from memory; verify on
   Maven Central or the Spring AI 2.0 release notes.
3. Note that `workbench-common` and `contract-tests` are sibling modules —
   import `workbench-common` as a dependency, never copy its types.

---

## A. Module setup

`groupId`: `com.workbench`, `artifactId`: `case-system-mcp`.
This IS a Spring Boot application (`@SpringBootApplication`).
Port: **8083** (PLATFORM_CONTRACT §5).

Dependencies:
- Parent POM (inherits Spring Boot 4 + Spring AI 2.0 BOMs)
- `workbench-common` (sibling module)
- Spring AI MCP server starter (verified artifact ID from step 2 above)
- `spring-boot-starter-data-jpa`
- `postgresql` driver
- `spring-boot-starter-actuator` (health endpoint for docker-compose
  healthcheck)
- Testcontainers: `postgresql` + `junit-jupiter` (test scope)

**No WebFlux.** This module is servlet-based (MCP Streamable HTTP uses
servlet). Do not add `spring-boot-starter-webflux`.

---

## B. Database entities + repositories

Package: `com.workbench.mcp.entity` and `com.workbench.mcp.repository`.

Create JPA entities exactly matching the schema in PLATFORM_CONTRACT §4.
All entities in schema `workbench` — set
`spring.jpa.properties.hibernate.default_schema=workbench` in
`application.properties`.

Entities required:
- `CaseEntity` — maps `workbench.cases`
- `TransactionEntity` — maps `workbench.transactions`
- `EvidenceDocumentEntity` — maps `workbench.evidence_documents`
- `TaskEntity` — maps `workbench.tasks`, includes `missing_items` as
  `String[]` (PostgreSQL text array — use
  `@Column(columnDefinition="text[]")`)
- `AuditEntryEntity` — maps `workbench.audit_entries`

Spring Data repositories:
- `CaseRepository extends JpaRepository<CaseEntity, String>`
- `EvidenceDocumentRepository` — needs `findByCaseId(String caseId)`
- `TaskRepository` — needs
  `findByCaseIdAndTaskType(String caseId, String taskType)` for idempotency
- `AuditEntryRepository`

---

## C. MCP tool implementations

Package: `com.workbench.mcp.tools`.

Implement exactly the five tools in PLATFORM_CONTRACT §9 as Spring AI MCP
`@Tool`-annotated methods on a `@Service` class. Tool method names and
parameter names must match the contract exactly — the frontend's contract
fixtures assert on these.

### Read tools

**`get_case`**
```java
@Tool(name = "get_case",
      description = "Retrieve full case details by case ID")
public Map<String, Object> getCase(
    @ToolParam(name = "caseId", description = "The case identifier") String caseId)
```
Returns: all columns of the case row as a Map. Throws a descriptive
`IllegalArgumentException` (not a 500) if caseId not found.

**`list_case_documents`**
```java
@Tool(name = "list_case_documents",
      description = "List all evidence documents for a case")
public Map<String, Object> listCaseDocuments(
    @ToolParam(name = "caseId", description = "The case identifier") String caseId)
```
Returns: `{ "documents": [ { "docType": "TRANSACTION_RECORD", "present": true }, ... ] }`
Field names must be camelCase — `docType` not `doc_type`.

### Write tools

**`create_task`** — IDEMPOTENT
```java
@Tool(name = "create_task",
      description = "Create a missing-evidence request task for a case")
public Map<String, Object> createTask(
    @ToolParam(name = "caseId") String caseId,
    @ToolParam(name = "taskType") String taskType,
    @ToolParam(name = "missingItems") List<String> missingItems,
    @ToolParam(name = "assignedQueue") String assignedQueue)
```
Idempotency: query `TaskRepository.findByCaseIdAndTaskType(caseId, taskType)`.
If found → return existing task (do NOT insert again).
If not found → insert and return `{ "taskId": ..., "createdAt": ... }`.
This is the server-side idempotency required by PLATFORM_CONTRACT §6.4 and
the UI spec §3.3/B4. It protects against duplicate writes on approval retry.

**`update_case_status`**
```java
@Tool(name = "update_case_status",
      description = "Update the status of a case")
public Map<String, Object> updateCaseStatus(
    @ToolParam(name = "caseId") String caseId,
    @ToolParam(name = "newStatus") String newStatus)
```
Returns: `{ "caseId": ..., "status": ..., "updatedAt": ... }`.
Updates `updated_at` timestamp.

**`create_audit_entry`**
```java
@Tool(name = "create_audit_entry",
      description = "Create an audit log entry for a case action")
public Map<String, Object> createAuditEntry(
    @ToolParam(name = "caseId") String caseId,
    @ToolParam(name = "action") String action,
    @ToolParam(name = "performedBy") String performedBy)
```
Returns: `{ "entryId": ..., "createdAt": ... }`.

### Tool registration

Register all tools with Spring AI's MCP server auto-configuration.
In `application.properties`:
```properties
spring.ai.mcp.server.name=case-system-mcp
spring.ai.mcp.server.version=1.0.0
spring.ai.mcp.server.transport=STREAMABLE_HTTP
```
Expose `McpSyncServerExchange` or the current Spring AI 2.0 equivalent —
verify the correct bean/registration pattern from Spring AI 2.0 MCP docs,
do not assume the API from memory.

---

## D. Configuration

`src/main/resources/application.properties`:
```properties
server.port=8083
spring.application.name=case-system-mcp
spring.datasource.url=jdbc:postgresql://localhost:5432/workbench
spring.datasource.username=workbench
spring.datasource.password=workbench
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.properties.hibernate.default_schema=workbench
spring.jpa.open-in-view=false
management.endpoints.web.exposure.include=health
```

`ddl-auto=validate` — the schema is created by `infra/seed/schema.sql`,
not by Hibernate. If Hibernate cannot validate against the running DB,
it fails fast on startup — correct behaviour.

For tests, use a separate `application-test.properties` with
`ddl-auto=create-drop` so Testcontainers manages schema lifecycle.

---

## E. Tests (Testcontainers — PLATFORM_CONTRACT §14)

Package: `com.workbench.mcp.test`.
Use `@SpringBootTest` + `@Testcontainers` + `@Container PostgreSQLContainer`.

**`CaseSystemMcpToolsTest`** — one test class, covers all five tools:

1. `getCase_existingCase_returnsAllFields` — seed case D-10291, call
   `get_case`, assert all fields present including `caseId`, `disputeType`,
   `amount`, `currency`.

2. `getCase_unknownCase_throwsDescriptiveException` — call with
   `"D-UNKNOWN"`, assert `IllegalArgumentException` with message containing
   the caseId.

3. `listCaseDocuments_returnsDocumentsWithCamelCaseFields` — assert
   response contains `docType` (not `doc_type`) and `present` fields;
   demo case has 2 documents (TRANSACTION_RECORD + MERCHANT_RESPONSE).

4. `createTask_newTask_insertsAndReturns` — call `create_task` with demo
   data, assert `taskId` starts with "EVID-" (or whatever format the
   implementation generates), `createdAt` is present.

5. `createTask_duplicate_returnsExistingWithoutInsert` — call `create_task`
   twice with same `caseId` + `taskType`, assert: second call returns same
   `taskId` as first, and only one row in `tasks` table (query DB to verify).
   This is the critical idempotency test from PLATFORM_CONTRACT §9.

6. `updateCaseStatus_updatesAndReturnsTimestamp` — call, assert response
   `status` matches input, `updatedAt` is non-null, DB row is updated.

7. `createAuditEntry_insertsAndReturnsEntryId` — call, assert `entryId`
   is a positive integer, `createdAt` is non-null.

Schema bootstrap for tests: apply `infra/seed/schema.sql` and
`infra/seed/seed-data.sql` via Testcontainers `withInitScript()` or
Flyway/Liquibase (choose the simpler approach — plain init scripts are fine).
Do NOT use `ddl-auto=create-drop` with raw `@Entity` scanning as the source
of truth for schema — the schema SQL file is the single source of truth.

---

## F. Contract-tests integration

After implementing, add one test to `contract-tests` module:

**`McpToolOutputContractTest`** — calls the tool service methods directly
(not over HTTP — instantiate the service with an in-memory H2 or via
Testcontainers) and asserts their Map output field names match the
PLATFORM_CONTRACT §9 field names exactly:
- `list_case_documents` response contains key `"documents"` (not
  `"documentList"` or `"docs"`).
- Each document entry contains `"docType"` and `"present"`.
- `create_task` response contains `"taskId"` and `"createdAt"`.
- `update_case_status` response contains `"caseId"`, `"status"`,
  `"updatedAt"`.
- `create_audit_entry` response contains `"entryId"` and `"createdAt"`.

This test will catch field-name drift before Session 3 (Case Review Agent)
tries to parse these responses.

---

## G. Future-session notes (act on these in their respective sessions)

These are recorded here so the information is not lost — do NOT implement
them now:

- **Session 3 (case-review-agent):** When constructing `EvidenceItem`
  objects from database document records, use `DocumentTypes` map from
  `workbench-common` to produce human-readable labels. Do NOT use raw DB
  `doc_type` strings as labels — `EvidenceReadiness.compute()` matches on
  these labels case-insensitively, and divergence causes the readiness
  calculation to report incorrect results silently. This is Minor 2 from
  the Session 1 hardening pass.

- **Session 5 (orchestrator-agent):** Replace the self-referential
  contract-tests fixtures (currently generated from the builders themselves)
  with independently transcribed JSON from PLATFORM_CONTRACT.md §6 and §7,
  verified against captured real orchestrator output. This closes Minor 3
  from the Session 1 hardening pass.

- **Session 6 (infra):** The `infra/seed/schema.sql` used for Testcontainers
  init scripts in this session must be the single schema source of truth.
  Verify in Session 6 that `docker-compose.yml` mounts the same file.

---

## H. Acceptance before Session 3

- `mvn test` from repo root: all modules green including `contract-tests`.
- `mvn test -pl case-system-mcp` shows 7 tests passing.
- `mvn test -pl contract-tests` shows `McpToolOutputContractTest` passing.
- Idempotency test (E5) explicitly verified — show the DB row count assertion
  output.
- No `com.fasterxml.jackson.core` or `com.fasterxml.jackson.databind`
  imports:
  ```bash
  grep -r "com\.fasterxml\.jackson\.core\|com\.fasterxml\.jackson\.databind" \
    case-system-mcp/src 2>/dev/null
  ```
  Must return nothing.
- Show the Spring AI MCP artifact ID and version chosen (from live Maven
  Central check) — record it in a comment in the POM.
