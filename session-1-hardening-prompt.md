# Session 1 — Architectural Hardening (pre-Session 2 gate)

This is a targeted fix pass only. Do not change method signatures, package
structure, field names, or anything not listed below. Do not add features.
PLATFORM_CONTRACT.md is still the source of truth.

---

## Fix 1 — Defend list immutability in all DTO compact constructors

**Problem:** Java records are immutable by value but their `List<>` fields
are not — if a caller passes a mutable `ArrayList`, they can mutate it after
construction, silently breaking the DTO's invariants across module boundaries.

**Fix:** In every record that holds a `List<>` field, add a compact constructor
that defensively copies the list:

```java
public record CaseReviewResult(..., List<String> availableDocuments, ...) {
    public CaseReviewResult {
        availableDocuments = availableDocuments == null
            ? List.of()
            : List.copyOf(availableDocuments);
        // same for any other List field in this record
    }
}
```

Apply to: `CaseReviewResult`, `PolicyResult`, `EvidenceReadiness`
(`missingItems`), `PendingApproval` (`missingItems`), `EvidenceChecklist`
items list inside `A2uiComponents`. Use `List.copyOf()` — not
`Collections.unmodifiableList()` — because `List.copyOf()` also makes a
defensive copy, not just a wrapper.

**Test:** For each affected record, add one test asserting that mutating
the original list after construction does not affect the record's field.

---

## Fix 2 — Enrich CaseReviewResult document representation

**Problem:** `availableDocuments` is `List<String>` (document type codes).
When the Case Review Agent is built in Session 3 it will query the database
for document metadata (type, presence). A flat `List<String>` of codes will
require a breaking DTO change at that point.

**Fix:** Replace `List<String> availableDocuments` in `CaseReviewResult`
with `List<EvidenceItem> availableDocuments` where `EvidenceItem` is the
existing record `{ String label, boolean present }` already in
`workbench-common`. This is the same type used in the A2UI checklist,
which is intentional — the Case Review Agent's document inventory directly
produces the checklist items.

Update `EvidenceReadiness.compute()` signature accordingly:
```java
public static EvidenceReadiness compute(
    List<EvidenceItem> available,   // from CaseReviewResult
    List<String> required)          // from PolicyResult.requiredEvidence
```

Logic update: `present` = count of `available` items where `present == true`
AND whose `label` matches (case-insensitive) a human-readable form of any
`required` code (use the existing `DocumentTypes` map for the translation).

Update all existing tests that construct `CaseReviewResult` or call
`EvidenceReadiness.compute()` to use the new signature. No new test logic
needed — just update the fixture data.

---

## Fix 3 — Verify agui-support does not pull spring-boot-autoconfigure
   as a compile-scoped transitive dependency

**Check:** Run `mvn dependency:tree -pl agui-support` and verify that
`spring-boot-autoconfigure` is present only as a transitive dependency
of `spring-boot-starter-webflux`, not declared directly, and that there
is no `@SpringBootApplication`, `@EnableAutoConfiguration`, or
`@ComponentScan` annotation anywhere in `agui-support/src`.

**If `spring-boot-autoconfigure` is on the compile classpath directly**
(not transitively): remove it. `agui-support` is a library module — it
must not trigger Boot auto-configuration when included in another module.

**Document the outcome** in a one-line comment at the top of
`AguiEmitter.java`: either "Verified: agui-support has no Boot
autoconfiguration dependency" or the fix applied.

No code change needed if the check passes.

---

## Fix 4 — Add contract-tests module with golden JSON fixtures

**Why now:** Sessions 2–4 will produce Java services that serialize DTOs
and AG-UI events. Without fixtures, serialization drift (a field renamed,
a case changed) goes undetected until Session 5 when the orchestrator
connects everything. Adding fixtures now means each session can assert its
output against the contract.

**What to create:**

New Maven module `contract-tests` (not a Spring Boot app — plain Java +
JUnit 5 + Jackson 3). Add to parent POM modules list.

```
contract-tests/
  src/test/resources/fixtures/
    case-review-result.json
    policy-result.json
    evidence-readiness.json
    agui-run-started.json
    agui-custom-progress.json
    agui-custom-a2ui-update-components.json
    agui-state-delta.json
    a2ui-create-surface.json
    a2ui-update-components-full.json   ← the complete §7.2 payload
    a2ui-approval-preview.json
    a2ui-task-created-card.json
  src/test/java/com/workbench/contract/
    CommonDtoContractTest.java
    AguiEventContractTest.java
    A2uiPayloadContractTest.java
```

**Fixture content:** each JSON file contains the exact wire-format JSON
that the platform must produce — taken directly from PLATFORM_CONTRACT.md
§6, §7, §8. Generate them from the existing `A2uiMessages` builders and
`AguiEmitter` serialization (i.e., run the builders, serialize, write the
output as the fixture). This makes the builders the single source of truth.

**Contract tests:** each test deserializes the fixture JSON into the
corresponding Java type AND serializes the Java type back to JSON, then
asserts field-by-field equality against the fixture. If a field name or
type changes in a DTO, the contract test fails immediately.

`a2ui-update-components-full.json` must contain the complete three-entry
payload from PLATFORM_CONTRACT §7.2 including `checklistId` and `actionsId`
on the DecisionCard — this is the most critical contract assertion.

**Acceptance:** `mvn test -pl contract-tests` passes. Show the output of
`a2ui-update-components-full.json` deserialized and re-serialized —
it must be byte-for-byte identical to the §7.2 fixture (field order aside).

---

## Acceptance for this pass

- `mvn test` from repo root: all modules green, zero failures.
- No `com.fasterxml.jackson.core` or `com.fasterxml.jackson.databind`
  import anywhere (annotation imports are fine):
  ```bash
  grep -r "com\.fasterxml\.jackson\.core\|com\.fasterxml\.jackson\.databind" \
    workbench-common/src agui-support/src contract-tests/src 2>/dev/null
  ```
  Must return nothing.
- Show: dependency:tree excerpt for agui-support, the Fix 2 updated
  EvidenceReadiness test output, and the contract-tests pass summary.
- After this pass, reply with "Session 1 is architecturally ready.
  Proceed with Session 2" if all four fixes are clean.
