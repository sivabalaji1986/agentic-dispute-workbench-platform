# Session 1 — workbench-common + agui-support

**Source of truth:** `PLATFORM_CONTRACT.md` in the repo root.
If anything below conflicts with the contract, the contract wins.

## Context

You are building the foundation of `agentic-dispute-workbench-platform`,
a Maven multi-module Spring Boot 4 / Java 25 / Spring AI 2.0 monorepo.
This session creates two modules that have no Spring Boot runtime dependency:
`workbench-common` (shared DTOs) and `agui-support` (AG-UI event types and
SSE emitter). All downstream modules depend on these. Get them right first.

Before writing any code:
1. Read `PLATFORM_CONTRACT.md` in full.
2. Verify the latest published versions of `com.ag-ui:core` on Maven Central
   and confirm whether its Jackson annotations use `tools.jackson` (Jackson 3)
   or `com.fasterxml.jackson` (Jackson 2). If they use Jackson 2 annotations,
   do NOT use the SDK's classes — use our own Java records as specified in
   PLATFORM_CONTRACT §11. Document the finding in a comment in `AguiEvents.java`.

---

## A. Parent POM (`pom.xml` at repo root)

Create the parent POM. Requirements:

- `groupId`: `com.workbench`, `artifactId`: `agentic-dispute-workbench-platform`,
  `version`: `1.0.0-SNAPSHOT`, `packaging`: `pom`.
- Import Spring Boot 4 BOM (`spring-boot-dependencies`) and
  Spring AI 2.0.0 BOM (`spring-ai-bom`) in `dependencyManagement`.
- Declare all module artifactIds under `<modules>` (all six, even though
  only two are created this session — stubs for the others are fine).
- Properties: `java.version=25`, `maven.compiler.source=25`,
  `maven.compiler.target=25`, `project.build.sourceEncoding=UTF-8`.
- Jackson 3 enforcement: add an enforcer rule or a comment block prominently
  warning that ALL Jackson imports must use `tools.jackson.*` not
  `com.fasterxml.jackson.*`. Boot 4 ships Jackson 3; mixing will cause
  runtime failures.
- Plugin management: `maven-compiler-plugin` 3.13+, `maven-surefire-plugin`
  3.x (JUnit 5), `spring-boot-maven-plugin` (only in modules that are
  Spring Boot apps — not in common/support modules).

---

## B. workbench-common

`groupId`: `com.workbench`, `artifactId`: `workbench-common`.
No Spring dependencies. Java 25 records. No `@Component`, no `@Service`.

### B1. Package structure
```
com.workbench.common/
  a2a/
    CaseReviewRequest.java
    CaseReviewResult.java
    PolicyRequest.java
    PolicyResult.java
  agui/
    EvidenceItem.java
    ActionItem.java
  merge/
    EvidenceReadiness.java
  session/
    PendingApproval.java
```

### B2. Types — implement exactly as specified in PLATFORM_CONTRACT §10

**`CaseReviewRequest`** — record: `caseId`, `disputeType`.

**`CaseReviewResult`** — record: `caseId`, `transactionFound`, `transactionAmount`,
`merchantResponse`, `merchantPosition`, `availableDocuments` (List<String>),
`caseStatus`.

**`PolicyRequest`** — record: `disputeType`.

**`PolicyResult`** — record: `disputeType`, `policySection`,
`policyInterpretation`, `requiredEvidence` (List<String>), `policyOutcome`.

**`EvidenceItem`** — record: `label` (String), `present` (boolean).

**`ActionItem`** — record: `id` (String), `label` (String).

**`EvidenceReadiness`** — record: `present` (int), `required` (int),
`missingItems` (List<String>), `label` (String).
Implement a static factory method:
```java
public static EvidenceReadiness compute(
    List<String> available, List<String> required)
```
Logic: `present` = count of items in `required` that also appear in `available`
(case-insensitive match); `required` = required.size();
`missingItems` = items in `required` not in `available` (preserve order, human-readable
names from the map in §B3); `label` = `"{present} of {required} required items present"`.

**§B3 — Document type to human-readable name map** (used in EvidenceReadiness
and EvidenceItem label rendering):
```
TRANSACTION_RECORD       → "Transaction record"
MERCHANT_RESPONSE        → "Merchant response"
CUSTOMER_DECLARATION     → "Customer declaration"
DELIVERY_DISPUTE_PROOF   → "Delivery / non-delivery proof"
```
Define this as a package-private constant map in a `DocumentTypes` utility class.
Do not scatter magic strings.

**`PendingApproval`** — record: `threadId`, `surfaceId`, `caseId`, `taskType`,
`missingItems` (List<String>), `executed` (boolean).
Add a `withExecuted(boolean)` convenience method returning a new record with
`executed` set (records are immutable).

### B3. Jackson annotations
All records that will be serialized/deserialized over A2A or stored must have
`@JsonProperty` on fields where the JSON name differs from the Java name, and
`@JsonInclude(NON_NULL)` at the class level. Use `tools.jackson.annotation.*`.
Do not use `com.fasterxml.jackson.*` anywhere.

### B4. Tests (`workbench-common` — JUnit 5, no Spring context)
- `EvidenceReadinessTest`: compute with all present → 0 missing; compute with
  2 of 4 present (exactly the demo case) → missingItems = [CUSTOMER_DECLARATION,
  DELIVERY_DISPUTE_PROOF], label = "2 of 4 required items present"; compute with
  empty available → all required are missing; case-insensitive match works.
- `PendingApprovalTest`: `withExecuted(true)` returns new record with correct value,
  original unchanged.
- Serialization round-trip for `CaseReviewResult` and `PolicyResult` using
  Jackson 3 `ObjectMapper` — assert field names are camelCase in JSON.

---

## C. agui-support

`groupId`: `com.workbench`, `artifactId`: `agui-support`.
Depends on: `workbench-common`, `spring-boot-starter-webflux` (for
`Flux`, `Sinks`, `ServerSentEvent` — NO `@SpringBootApplication`),
`com.ag-ui:core` (0.0.1 — types only, conditional on Jackson 3 compatibility
per the check in the preamble), Jackson 3 (`tools.jackson.databind`).

### C1. Package structure
```
com.workbench.agui/
  events/
    AguiEvents.java          (event record definitions)
    JsonPatchOp.java
  emitter/
    AguiEmitter.java
  a2ui/
    A2uiMessages.java
    A2uiComponents.java      (inner component builder records)
```

### C2. AguiEvents.java

Define Java records for every event type the orchestrator emits.
Field names must be EXACT camelCase matches to the AG-UI spec
(see PLATFORM_CONTRACT §6.1) — the UI validates these field names.

```java
// Serializes as: {"type":"RUN_STARTED","threadId":"...","runId":"..."}
public record RunStartedEvent(
    @JsonProperty("type")     String type,
    @JsonProperty("threadId") String threadId,
    @JsonProperty("runId")    String runId) {
    public RunStartedEvent(String threadId, String runId) {
        this("RUN_STARTED", threadId, runId);
    }
}
// Same pattern for: RunFinishedEvent, RunErrorEvent (adds: message, code?)
// CustomEvent: type="CUSTOM", name, value (Object — serializes as nested JSON)
// StateDeltaEvent: type="STATE_DELTA", delta (List<JsonPatchOp>)
// StateSnapshotEvent: type="STATE_SNAPSHOT", snapshot (Object)
```

**JsonPatchOp** — record: `op` (String), `path` (String), `value` (Object).

Important: if `com.ag-ui:core` types ARE Jackson-3-compatible, use them
instead of these records — but the factory constructors and the canonical
field names still apply. Add a comment at the top of `AguiEvents.java`
documenting which path was chosen and why.

### C3. AguiEmitter.java

```java
public class AguiEmitter {
    private final Sinks.Many<ServerSentEvent<String>> sink;
    private final ObjectMapper objectMapper;  // tools.jackson.databind

    public AguiEmitter(ObjectMapper objectMapper) {
        this.sink = Sinks.many().unicast().onBackpressureBuffer();
        this.objectMapper = objectMapper;
    }

    // Serializes event to JSON, wraps in ServerSentEvent, emits to sink.
    // Never throws — on serialization error, emits a RUN_ERROR event instead.
    public void emit(Object event);

    // Emits RUN_FINISHED then completes the sink.
    public void complete(String threadId, String runId);

    // Emits RUN_ERROR then completes the sink.
    public void error(String threadId, String runId, String message);

    // Returns the Flux for the SSE endpoint to return.
    public Flux<ServerSentEvent<String>> flux();
}
```

Thread safety: `Sinks.Many` is thread-safe for concurrent `emit()` calls —
this matters because the orchestrator will call `emit()` from two parallel
A2A response handlers simultaneously during the fan-out phase.

### C4. A2uiMessages.java + A2uiComponents.java

Implement static builder methods producing plain Java Maps/records that
serialize to the exact JSON shapes in PLATFORM_CONTRACT §7.
Each builder method's Javadoc references the §7 section it satisfies.

Required builders:
```java
// §7.1
public static Object createSurface(String surfaceId, String catalogId)

// §7.2 — takes pre-built component objects
public static Object updateComponents(String surfaceId, List<Object> components)

// Component builders — produce objects matching §7.2 flat entry shapes:
public static Object decisionCard(String id, String checklistId, String actionsId,
    String status, String disputeType, String evidenceReadiness, String recommendedAction)

public static Object evidenceChecklist(String id, List<EvidenceItem> items)

public static Object nextActions(String id, List<ActionItem> actions)

public static Object approvalPreview(String id, String caseId, String newCaseStatus,
    List<String> missingItems, String actionAfterApproval)

public static Object taskCreatedCard(String id, String taskId, String caseStatus,
    String auditEntry, String nextOwner)
```

All output field names must be camelCase matching §7 exactly:
`surfaceId`, `catalogId`, `checklistId`, `actionsId`, `disputeType`,
`evidenceReadiness`, `recommendedAction`, `newCaseStatus`, `missingItems`,
`actionAfterApproval`, `taskId`, `caseStatus`, `auditEntry`, `nextOwner`.
Never snake_case.

### C5. Tests (`agui-support` — JUnit 5, no Spring context)

- `AguiEmitterTest`:
  - emit RunStartedEvent → flux emits one SSE item whose data parses to
    `{type:"RUN_STARTED", threadId:..., runId:...}`.
  - emit CustomEvent (progress) → data parses to `{type:"CUSTOM",
    name:"progress", value:{source:"orchestrator", text:"..."}}`.
  - emit two events concurrently from two threads → both appear in flux,
    no `MissedEmissionException`.
  - complete() → flux terminates after RUN_FINISHED event.
  - serialization error handling → RUN_ERROR emitted, flux continues.

- `A2uiMessagesTest`:
  - `createSurface` output serializes to exactly `{"version":"v0.9",
    "createSurface":{"surfaceId":"...","catalogId":"..."}}`.
  - `updateComponents` with DecisionCard + EvidenceChecklist + NextActions
    produces the exact §7.2 JSON structure — assert `checklistId` and
    `actionsId` are present on the DecisionCard entry.
  - `approvalPreview` serializes with all fields per §7.2.
  - `taskCreatedCard` serializes with all fields per §7.2.

- `AguiEventsSerializationTest`:
  - Every event type round-trips through Jackson 3 with correct field names.
  - `StateDeltaEvent` with a replace op serializes to RFC 6902 shape.
  - `StateSnapshotEvent` with a plain map snapshot serializes correctly.

---

## D. Acceptance before Session 2

- `mvn test` from repo root passes for both modules, zero failures.
- `mvn package` produces JARs for both modules (not Spring Boot fat JARs —
  these are library modules).
- Jackson 3 finding documented in `AguiEvents.java` comment.
- No `com.fasterxml.jackson.*` import anywhere — verify with:
  `grep -r "com.fasterxml" workbench-common/src policy-agent/src 2>/dev/null`
  (should return nothing).
- Show test output summary and the serialized JSON from
  `A2uiMessagesTest.updateComponents` before finishing — this is the shape
  the entire frontend contract depends on.
