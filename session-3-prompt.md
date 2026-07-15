# Session 3 — case-review-agent

**Source of truth:** `PLATFORM_CONTRACT.md` in the repo root.
If anything below conflicts with the contract, the contract wins.

## Context

You are adding the `case-review-agent` module to
`agentic-dispute-workbench-platform`. This is a standalone Spring Boot 4
application that:
- Exposes itself as an **A2A server** (specialist agent)
- Acts as an **MCP client** (reads from `case-system-mcp`)
- Uses Spring AI `ChatClient` to reason about case facts
- Returns a structured `CaseReviewResult` to the orchestrator

It does NOT write to the database. It does NOT decide what evidence is
missing — it only reports what is present. The missing-evidence determination
happens in the orchestrator's merge step (PLATFORM_CONTRACT §6.3).

Before writing any code:
1. Re-read PLATFORM_CONTRACT §8 (A2A contract), §9 (MCP tools), §10
   (shared DTOs), §14 (test requirements).
2. Verify the currently published version and artifact ID of:
   - Official A2A Java SDK (`io.a2a:a2a-server` or current artifact — check
     `a2aproject/a2a-java` on GitHub/Maven Central)
   - `spring-ai-a2a-server-autoconfigure` (Spring AI community — check
     `spring-ai-community` org)
   - Spring AI MCP client starter (for connecting to `case-system-mcp`)
   Do NOT assume artifact IDs or APIs from memory — verify each.
3. Note that `workbench-common` types are the shared contract — import them,
   never redefine them.
4. Note that Session 2 added `-parameters` compiler flag for `@ToolParam`.
   Verify it is in the parent POM's `maven-compiler-plugin` compilerArgs,
   not just in `case-system-mcp`. If missing from parent, add it now so
   all modules inherit it.

---

## A. Module setup

`groupId`: `com.workbench`, `artifactId`: `case-review-agent`.
This IS a Spring Boot application (`@SpringBootApplication`).
Port: **8081** (PLATFORM_CONTRACT §5).

Dependencies:
- Parent POM (inherits BOMs)
- `workbench-common` (sibling)
- A2A server dependency (verified artifact from step 2)
- `spring-ai-a2a-server-autoconfigure` (verified artifact from step 2)
- Spring AI MCP client starter (verified artifact from step 2)
- Spring AI ChatClient starter for Ollama
  (`spring-ai-starter-model-ollama` or current name — verify)
- `spring-boot-starter-web` (servlet — A2A server is REST-based)
- Testcontainers: `postgresql` + `junit-jupiter` (test scope, for
  integration test against real `case-system-mcp` via Testcontainers)

**No WebFlux, no JPA** — this agent reads via MCP, not direct DB access.

---

## B. A2A server configuration

The agent must expose:
- Agent Card at `GET /.well-known/agent-card.json`
- A2A endpoint at `POST /a2a`

Per PLATFORM_CONTRACT §8.1, the agent card must be:
```json
{
  "name": "case-review-agent",
  "description": "Reviews operational case facts: transaction, merchant response, and available evidence documents.",
  "url": "http://localhost:8081/a2a",
  "version": "1.0.0",
  "capabilities": {"streaming": false}
}
```

Configure via `application.properties` using whatever properties
`spring-ai-a2a-server-autoconfigure` exposes (verify from its source/docs —
do not guess property names). If autoconfiguration is insufficient, wire
manually using the A2A Java SDK's server components directly.

---

## C. Agent executor implementation

Package: `com.workbench.caseagent`.

The A2A server calls an `AgentExecutor` (or equivalent interface in the
current SDK version — verify) with the incoming message text. Implement:

```java
@Service
public class CaseReviewAgentExecutor implements AgentExecutor {
    // dependencies: ChatClient, CaseMcpClient (§D)
}
```

### C1. Input parsing

The orchestrator sends (PLATFORM_CONTRACT §8.2):
```
Check transaction, merchant response, case status and available evidence
for dispute case {caseId}, dispute type {disputeType}.
```

Extract `caseId` and `disputeType` from the message text. Use simple
string parsing or a one-shot `ChatClient` call — do not over-engineer.
If parsing fails, return a structured error response (not a Java exception).

### C2. MCP tool calls (via §D CaseMcpClient)

Call in this order:
1. `get_case(caseId)` — validate transaction exists, get amount, currency,
   merchant position.
2. `list_case_documents(caseId)` — get available documents.

### C3. EvidenceItem construction — CRITICAL

When building `List<EvidenceItem>` from `list_case_documents` response:
- Use `DocumentTypes` map from `workbench-common` to convert `docType`
  codes to human-readable labels.
- Set `present` from the `present` field in the MCP response.
- **Never use raw `docType` strings as `EvidenceItem.label`** — e.g., do
  not produce `label: "TRANSACTION_RECORD"`. Produce `label: "Transaction
  record"` (from `DocumentTypes`).
- This is load-bearing: `EvidenceReadiness.compute()` in the orchestrator
  matches on these labels. Divergence causes the readiness score to silently
  return 0. (Minor 2 from Session 1 hardening — now being enforced.)

### C4. ChatClient reasoning

Use `ChatClient` to produce the `merchantResponse` and `merchantPosition`
summary fields from the `get_case` result. Keep the prompt minimal:

```
Given this case data: {caseJson}
Summarise: 1) Has the merchant responded? (yes/no) 2) What is the merchant's
position in one sentence?
Respond only with JSON: {"merchantResponse": "...", "merchantPosition": "..."}
```

Parse the JSON response into the two fields. Handle malformed LLM output
gracefully — if parsing fails, default to `merchantResponse: "unknown"`,
`merchantPosition: "Unable to determine"`.

### C5. Progress line emission

The A2A response must include progress lines that the orchestrator will
forward to the AG-UI stream. These are the exact lines from
PLATFORM_CONTRACT §8.2:

```
Checking transaction status...
Transaction found for SGD {amount}
Merchant response available
Case file contains transaction record
Case file contains merchant response
No additional customer documents found in case file
```

The last line is conditional: emit it only when no customer-submitted
documents are found (i.e., no CUSTOMER_DECLARATION or DELIVERY_DISPUTE_PROOF
in the document list).

**How to return progress lines:** include them in the A2A response as
metadata or as part of the structured text response — check the A2A Java SDK
for the correct mechanism to return both structured data and progress
annotations. If the SDK has no progress mechanism, return a wrapper object:
```java
record AgentResponse(CaseReviewResult result, List<String> progressLines) {}
```
and document this choice in a comment — the orchestrator (Session 5) must
extract them in the same way.

### C6. Response serialization

Serialize `CaseReviewResult` (from `workbench-common`) to JSON as the A2A
response text. Use Jackson 3 `ObjectMapper` with the same camelCase
conventions. The orchestrator will deserialize using the same type.

---

## D. MCP client (CaseMcpClient)

Package: `com.workbench.caseagent.mcp`.

```java
@Service
public class CaseMcpClient {
    // Wraps Spring AI MCP client to call case-system-mcp tools
    Map<String, Object> getCase(String caseId);
    Map<String, Object> listCaseDocuments(String caseId);
}
```

Configure the MCP client to connect to `case-system-mcp` at
`http://localhost:8083` (configurable via property
`workbench.mcp.case-system.url`).

Use whatever Spring AI 2.0 MCP client API is current — verify from docs,
do not assume class names. If using `McpClient` or `McpAsyncClient`, prefer
the synchronous variant here (A2A execution is already on a thread pool).

For tests, the MCP client URL will be overridden to point at a
Testcontainers-managed `case-system-mcp` instance OR mocked via WireMock.
See §E for the test strategy choice.

---

## E. Tests (PLATFORM_CONTRACT §14)

### Strategy decision (make this choice explicitly and state it in a comment):

**Option A — Full Testcontainers stack:** start both a PostgreSQL container
AND the `case-system-mcp` Spring Boot app (via `@SpringBootTest` with
`spring-boot-test-containers` or by starting it as a separate process).
More realistic, more complex setup.

**Option B — WireMock for MCP responses:** mock the HTTP calls to
`case-system-mcp` using WireMock. Faster, isolated, but tests the
serialization contract rather than the real tool behaviour.

**Recommended:** Option B for unit/integration tests in this module (the
MCP tools were already tested with real Postgres in Session 2). Add a
comment: "Full stack integration tested in Session 6 infra smoke test."

### Test classes:

**`CaseReviewAgentExecutorTest`** (WireMock or stubbed MCP client):

1. `execute_validCase_returnsStructuredResult` — stub `get_case` and
   `list_case_documents` responses matching demo case D-10291 seed data;
   assert `CaseReviewResult` fields: `transactionFound=true`,
   `transactionAmount="SGD 250"`, `availableDocuments` contains two
   `EvidenceItem` entries with human-readable labels (not raw codes).

2. `execute_validCase_progressLinesPresent` — assert progress lines list
   contains all expected strings from §C5 (exact text match on the
   conditional "No additional customer documents" line).

3. `execute_documentsUseHumanReadableLabels` — assert specifically that
   no `EvidenceItem.label` equals "TRANSACTION_RECORD" or
   "MERCHANT_RESPONSE" (raw codes); must equal "Transaction record" and
   "Merchant response". This directly tests the Minor 2 fix.

4. `execute_unknownCase_returnsErrorGracefully` — stub `get_case` to
   return 404/error; assert response does not throw, returns a result
   with `transactionFound=false` and descriptive message.

5. `execute_malformedLlmOutput_defaultsGracefully` — stub ChatClient
   to return non-JSON; assert `merchantResponse="unknown"`,
   `merchantPosition="Unable to determine"`.

**`CaseMcpClientTest`** (WireMock):

6. `getCase_mapsResponseCorrectly` — assert `caseId`, `amount` fields
   present in returned Map.

7. `listCaseDocuments_mapsDocTypeAndPresentFields` — assert `docType`
   (camelCase) and `present` fields in each document entry.

---

## F. Contract-tests addition

Add to `contract-tests` module:

**`CaseReviewResultContractTest`** — constructs a `CaseReviewResult`
matching the demo scenario (transaction found, SGD 250, two documents with
human-readable labels, no customer documents), serializes to JSON, and
asserts:
- `availableDocuments[0].label` = "Transaction record" (not "TRANSACTION_RECORD")
- `availableDocuments[1].label` = "Merchant response" (not "MERCHANT_RESPONSE")
- `transactionAmount` = "SGD 250"
- All field names are camelCase

This fixture becomes the contract the orchestrator (Session 5) will
deserialize against.

---

## G. application.properties

```properties
server.port=8081
spring.application.name=case-review-agent
workbench.mcp.case-system.url=http://localhost:8083
# LLM — Ollama default; override via SPRING_PROFILES_ACTIVE=anthropic|openai
spring.ai.ollama.base-url=http://localhost:11434
spring.ai.ollama.chat.model=qwen2.5:14b
```

Profile-based LLM config follows PLATFORM_CONTRACT §3. Provide
`application-anthropic.properties` and `application-openai.properties`
stubs with the correct Spring AI 2.0 property names (verify from docs).

---

## H. Future-session notes (do NOT implement now)

- **Session 4 (policy-agent):** Same A2A server pattern as this session.
  Reuse the same A2A autoconfiguration approach verified here. The
  `AgentResponse` wrapper pattern (if chosen in §C5) must be used
  identically in the policy agent so the orchestrator can handle both
  uniformly.

- **Session 5 (orchestrator-agent):** The orchestrator calls this agent
  in parallel with the policy agent via A2A client. It must:
  (a) parse `CaseReviewResult` from the A2A response using the same
  Jackson 3 `ObjectMapper` + `workbench-common` type,
  (b) extract progress lines using whatever wrapper pattern §C5 established,
  (c) forward progress lines to the AG-UI stream with `source: "case-review"`.

- **Session 5 (orchestrator-agent):** `EvidenceReadiness.compute()` takes
  `availableDocuments` from `CaseReviewResult` directly. Verify at
  integration time that labels match `DocumentTypes` map entries exactly
  (case-insensitive). If any mismatch surfaces, fix in `DocumentTypes`
  — not by changing `EvidenceReadiness.compute()` logic.

- **Session 6 (infra):** Full stack smoke test should start `case-system-mcp`
  and `case-review-agent` together and call the A2A endpoint with the demo
  scenario. The Testcontainers Postgres from Session 2 is the shared DB.

---

## I. Acceptance before Session 4

- `mvn test` from repo root: all modules green (target: 53 + new tests).
- `mvn test -pl case-review-agent` shows all tests passing including the
  human-readable-label assertion (test E3).
- `mvn test -pl contract-tests` still passes including new
  `CaseReviewResultContractTest`.
- A2A artifact IDs and versions chosen (from live check) recorded in a
  POM comment.
- `-parameters` compiler flag confirmed in parent POM — show the relevant
  POM snippet.
- Show serialized JSON output of `CaseReviewResult` for the demo scenario
  (from the contract test) — field names must be camelCase, labels must be
  human-readable.
- No `com.fasterxml.jackson.core` or `com.fasterxml.jackson.databind`
  imports:
  ```bash
  grep -r "com\.fasterxml\.jackson\.core\|com\.fasterxml\.jackson\.databind" \
    case-review-agent/src 2>/dev/null
  ```
  Must return nothing.
