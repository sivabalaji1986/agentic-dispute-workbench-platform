# Session 1 — workbench-common + agui-support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create the Maven parent POM and the two foundation library modules (`workbench-common`, `agui-support`) of `agentic-dispute-workbench-platform`, with zero-Spring-dependency shared DTOs, AG-UI event records, an SSE emitter, and A2UI message builders — all matching `PLATFORM_CONTRACT.md` exactly, with passing JUnit 5 test suites.

**Architecture:** Two independent library JARs under a Maven multi-module reactor. `workbench-common` has no Spring dependency at all (plain Java 25 records + Jackson 3 annotations). `agui-support` depends on `workbench-common` and `spring-boot-starter-webflux` only (for `Flux`/`Sinks`/`ServerSentEvent`), with no `@SpringBootApplication`. Four more modules are declared as empty stub POMs so the reactor builds cleanly; they are implemented in later sessions per `PLATFORM_CONTRACT.md` §15.

**Tech Stack:** Java 25, Maven multi-module, Spring Boot 4.0.7 BOM, Spring AI 2.0.0 BOM, Jackson 3.1.4 (`tools.jackson.*` for core/databind), Jackson annotations (`com.fasterxml.jackson.annotation.*` — see Global Constraints), JUnit 5 (`junit-jupiter`, version 6.0.3 via Boot's `junit-bom` import), Reactor (`reactor-test` for `AguiEmitter` tests), Spring WebFlux's `ServerSentEvent`.

## Global Constraints

- Parent POM: `groupId=com.workbench`, `artifactId=agentic-dispute-workbench-platform`, `version=1.0.0-SNAPSHOT`, `packaging=pom`.
- Java 25 everywhere: `java.version=25`, `maven.compiler.source=25`, `maven.compiler.target=25`, `project.build.sourceEncoding=UTF-8`.
- Import BOMs in `dependencyManagement`: `org.springframework.boot:spring-boot-dependencies:4.0.7` and `org.springframework.ai:spring-ai-bom:2.0.0`. Both versions verified present on Maven Central as of 2026-07-15.
- **Jackson package rule (corrected in `PLATFORM_CONTRACT.md` this session — verified against the real Jackson 3.1.4 artifacts on Maven Central):**
  - `ObjectMapper`, `JsonNode`, `JsonMapper`, and all core streaming/databind types → `tools.jackson.core.*` / `tools.jackson.databind.*`. Never `com.fasterxml.jackson.core` or `com.fasterxml.jackson.databind`.
  - Annotations (`@JsonProperty`, `@JsonInclude`, etc.) → `com.fasterxml.jackson.annotation.*`. This is the **one** permitted `com.fasterxml.jackson` import: Jackson 3's own `jackson-databind:3.1.4` POM depends on `com.fasterxml.jackson.core:jackson-annotations` directly (confirmed by inspecting the published POM — its `jackson-base` parent literally comments "depends on Jackson 2.x annotations"), and `tools.jackson.core:jackson-annotations` does not exist (404 on Maven Central). There is no other way to annotate a POJO for Jackson 3.
  - The acceptance grep in this plan therefore checks for `com.fasterxml.jackson.core` / `com.fasterxml.jackson.databind` only, never a blanket `com.fasterxml` — that blanket form would false-positive on every legitimate annotation import.
- `com.ag-ui:core` is **not used**. Verified 2026-07-15: `com/ag-ui/core/maven-metadata.xml` 404s on Maven Central; the upstream `ag-ui-protocol/ag-ui` repo's `sdks/community/java` directory contains only a `.gitkeep` (no published code), and `jitpack.yml` shows the Java SDK is JitPack-only when it exists, never Maven Central. `agui-support` defines its own event records per `PLATFORM_CONTRACT.md` §11.
- No `@Component`, `@Service`, or any Spring stereotype annotation in `workbench-common`. No `@SpringBootApplication` in `agui-support`.
- Records only, immutable, Java 25 canonical/compact constructors where logic is needed.
- Plugin versions (verified GA on Maven Central 2026-07-15): `maven-compiler-plugin:3.15.0`, `maven-surefire-plugin:3.5.6`, `maven-enforcer-plugin:3.6.3`, `spring-boot-maven-plugin:4.0.7` (declared in `pluginManagement` only — not used by these two library modules, reserved for the Spring Boot app modules in later sessions).
- All A2UI JSON field names are camelCase, exactly matching `PLATFORM_CONTRACT.md` §7. Never snake_case.

---

### Task 1: Parent POM + module stubs

**Files:**
- Create: `pom.xml` (repo root)
- Create: `case-system-mcp/pom.xml`
- Create: `case-review-agent/pom.xml`
- Create: `policy-agent/pom.xml`
- Create: `orchestrator-agent/pom.xml`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: parent POM coordinates `com.workbench:agentic-dispute-workbench-platform:1.0.0-SNAPSHOT` that Tasks 2–8's child POMs declare as `<parent>`. Six `<module>` entries so the reactor resolves `workbench-common` and `agui-support` (built in later tasks) alongside four stub modules.

- [ ] **Step 1: Create the parent POM**

`pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.workbench</groupId>
  <artifactId>agentic-dispute-workbench-platform</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <!--
    ============================================================
    JACKSON 3 IMPORT RULE — READ BEFORE ADDING ANY JACKSON IMPORT
    ============================================================
    Spring Boot 4 ships Jackson 3. ObjectMapper and all core
    streaming/databind types MUST be imported from `tools.jackson.core.*`
    or `tools.jackson.databind.*`. NEVER `com.fasterxml.jackson.core`
    or `com.fasterxml.jackson.databind` — those are the Jackson 2
    classes and are binary-incompatible with Jackson 3's databind.

    EXCEPTION — annotations only: `@JsonProperty`, `@JsonInclude`, etc.
    are imported from `com.fasterxml.jackson.annotation.*`. Jackson 3
    never renamed the annotations module (verified against the
    published jackson-databind:3.1.4 POM on Maven Central, which
    depends on com.fasterxml.jackson.core:jackson-annotations
    directly); `tools.jackson.core:jackson-annotations` does not
    exist. This is the ONLY permitted com.fasterxml.jackson import.

    The maven-enforcer-plugin execution below fails the build if
    com.fasterxml.jackson.core:jackson-databind or
    com.fasterxml.jackson.core:jackson-core ever appear on the
    classpath (real Jackson 2, not the shared annotations module).
    ============================================================
  -->

  <properties>
    <java.version>25</java.version>
    <maven.compiler.source>25</maven.compiler.source>
    <maven.compiler.target>25</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <spring-boot.version>4.0.7</spring-boot.version>
    <spring-ai.version>2.0.0</spring-ai.version>
  </properties>

  <modules>
    <module>workbench-common</module>
    <module>agui-support</module>
    <module>case-system-mcp</module>
    <module>case-review-agent</module>
    <module>policy-agent</module>
    <module>orchestrator-agent</module>
  </modules>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>${spring-boot.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-bom</artifactId>
        <version>${spring-ai.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-compiler-plugin</artifactId>
          <version>3.15.0</version>
          <configuration>
            <release>25</release>
          </configuration>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-surefire-plugin</artifactId>
          <version>3.5.6</version>
        </plugin>
        <plugin>
          <groupId>org.springframework.boot</groupId>
          <artifactId>spring-boot-maven-plugin</artifactId>
          <version>${spring-boot.version}</version>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-enforcer-plugin</artifactId>
          <version>3.6.3</version>
        </plugin>
      </plugins>
    </pluginManagement>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-enforcer-plugin</artifactId>
        <executions>
          <execution>
            <id>enforce-no-jackson2-core-databind</id>
            <phase>validate</phase>
            <goals>
              <goal>enforce</goal>
            </goals>
            <configuration>
              <rules>
                <bannedDependencies>
                  <excludes>
                    <exclude>com.fasterxml.jackson.core:jackson-databind</exclude>
                    <exclude>com.fasterxml.jackson.core:jackson-core</exclude>
                  </excludes>
                  <message>
                    Jackson 2 core/databind (com.fasterxml.jackson.core:jackson-databind or
                    jackson-core) detected on the classpath. Spring Boot 4 uses Jackson 3
                    (tools.jackson.core / tools.jackson.databind) for all core/databind
                    usage. Exclude the transitive Jackson 2 dependency. (Note:
                    com.fasterxml.jackson.core:jackson-annotations is exempt — Jackson 3
                    never renamed the annotations module.)
                  </message>
                </bannedDependencies>
              </rules>
              <fail>true</fail>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Create the four stub module POMs**

`case-system-mcp/pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.workbench</groupId>
    <artifactId>agentic-dispute-workbench-platform</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>
  <artifactId>case-system-mcp</artifactId>
  <packaging>pom</packaging>
  <!-- Stub for Session 2 (PLATFORM_CONTRACT.md §15): Spring Boot app, MCP server over Postgres. -->
</project>
```

`case-review-agent/pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.workbench</groupId>
    <artifactId>agentic-dispute-workbench-platform</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>
  <artifactId>case-review-agent</artifactId>
  <packaging>pom</packaging>
  <!-- Stub for Session 3 (PLATFORM_CONTRACT.md §15): Spring Boot app, A2A server, MCP client reads. -->
</project>
```

`policy-agent/pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.workbench</groupId>
    <artifactId>agentic-dispute-workbench-platform</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>
  <artifactId>policy-agent</artifactId>
  <packaging>pom</packaging>
  <!-- Stub for Session 4 (PLATFORM_CONTRACT.md §15): Spring Boot app, A2A server, RAG. -->
</project>
```

`orchestrator-agent/pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.workbench</groupId>
    <artifactId>agentic-dispute-workbench-platform</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>
  <artifactId>orchestrator-agent</artifactId>
  <packaging>pom</packaging>
  <!-- Stub for Session 5 (PLATFORM_CONTRACT.md §15): Spring Boot app, AG-UI server, A2A client, MCP client writes. -->
</project>
```

- [ ] **Step 3: Verify the reactor resolves (will fail until Tasks 2 and 5 add real module dirs)**

Run: `mvn -q validate`
Expected at this point: FAIL with "Child module .../workbench-common/pom.xml does not exist" (workbench-common and agui-support directories don't exist yet — this is expected; it's resolved once Tasks 2 and 5 create those POMs). This step exists to confirm the parent POM itself is syntactically valid; a `Non-resolvable parent POM` or XML error would indicate a mistake in Step 1, whereas a "Child module does not exist" error confirms the parent parsed correctly and is just waiting on the two real modules.

- [ ] **Step 4: Commit**

```bash
git add pom.xml case-system-mcp/pom.xml case-review-agent/pom.xml policy-agent/pom.xml orchestrator-agent/pom.xml
git commit -m "Add parent POM with Boot 4 / Spring AI 2.0 BOMs and module stubs"
```

---

### Task 2: workbench-common — a2a package (A2A request/response DTOs)

**Files:**
- Create: `workbench-common/pom.xml`
- Create: `workbench-common/src/main/java/com/workbench/common/a2a/CaseReviewRequest.java`
- Create: `workbench-common/src/main/java/com/workbench/common/a2a/CaseReviewResult.java`
- Create: `workbench-common/src/main/java/com/workbench/common/a2a/PolicyRequest.java`
- Create: `workbench-common/src/main/java/com/workbench/common/a2a/PolicyResult.java`
- Test: `workbench-common/src/test/java/com/workbench/common/a2a/A2aSerializationTest.java`

**Interfaces:**
- Consumes: parent POM from Task 1 (`com.workbench:agentic-dispute-workbench-platform:1.0.0-SNAPSHOT`).
- Produces: `com.workbench.common.a2a.CaseReviewRequest(String caseId, String disputeType)`, `com.workbench.common.a2a.CaseReviewResult(String caseId, boolean transactionFound, String transactionAmount, String merchantResponse, String merchantPosition, List<String> availableDocuments, String caseStatus)`, `com.workbench.common.a2a.PolicyRequest(String disputeType)`, `com.workbench.common.a2a.PolicyResult(String disputeType, String policySection, String policyInterpretation, List<String> requiredEvidence, String policyOutcome)` — consumed by `case-review-agent` and `policy-agent` in later sessions.

- [ ] **Step 1: Create the module POM**

`workbench-common/pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.workbench</groupId>
    <artifactId>agentic-dispute-workbench-platform</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>workbench-common</artifactId>
  <packaging>jar</packaging>

  <!-- No Spring dependencies — plain Java 25 records + Jackson 3 annotations only. -->

  <dependencies>
    <dependency>
      <groupId>tools.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-annotations</artifactId>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Write the failing serialization round-trip test**

`workbench-common/src/test/java/com/workbench/common/a2a/A2aSerializationTest.java`:
```java
package com.workbench.common.a2a;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2aSerializationTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void caseReviewResultRoundTripsWithCamelCaseFieldNames() {
        CaseReviewResult original = new CaseReviewResult(
                "D-10291", true, "SGD 250", "available", "Item was delivered",
                List.of("TRANSACTION_RECORD", "MERCHANT_RESPONSE"), "OPEN");

        String json = objectMapper.writeValueAsString(original);

        assertTrue(json.contains("\"caseId\":\"D-10291\""));
        assertTrue(json.contains("\"transactionFound\":true"));
        assertTrue(json.contains("\"transactionAmount\":\"SGD 250\""));
        assertTrue(json.contains("\"merchantResponse\":\"available\""));
        assertTrue(json.contains("\"merchantPosition\":\"Item was delivered\""));
        assertTrue(json.contains("\"availableDocuments\":[\"TRANSACTION_RECORD\",\"MERCHANT_RESPONSE\"]"));
        assertTrue(json.contains("\"caseStatus\":\"OPEN\""));

        CaseReviewResult roundTripped = objectMapper.readValue(json, CaseReviewResult.class);
        assertEquals(original, roundTripped);
    }

    @Test
    void policyResultRoundTripsWithCamelCaseFieldNames() {
        PolicyResult original = new PolicyResult(
                "GOODS_NOT_RECEIVED",
                "Section 4.2 — Goods Not Received",
                "This case qualifies as Goods Not Received because the customer claims "
                        + "non-delivery while the merchant asserts delivery.",
                List.of("TRANSACTION_RECORD", "MERCHANT_RESPONSE", "CUSTOMER_DECLARATION", "DELIVERY_DISPUTE_PROOF"),
                "Potentially eligible, but evidence is incomplete.");

        String json = objectMapper.writeValueAsString(original);

        assertTrue(json.contains("\"disputeType\":\"GOODS_NOT_RECEIVED\""));
        assertTrue(json.contains("\"policySection\":\"Section 4.2"));
        assertTrue(json.contains("\"policyInterpretation\":\""));
        assertTrue(json.contains("\"requiredEvidence\":[\"TRANSACTION_RECORD\""));
        assertTrue(json.contains("\"policyOutcome\":\"Potentially eligible, but evidence is incomplete.\""));

        PolicyResult roundTripped = objectMapper.readValue(json, PolicyResult.class);
        assertEquals(original, roundTripped);
    }

    @Test
    void caseReviewRequestAndPolicyRequestSerializeWithCamelCaseFieldNames() {
        String caseReviewJson = objectMapper.writeValueAsString(new CaseReviewRequest("D-10291", "GOODS_NOT_RECEIVED"));
        assertTrue(caseReviewJson.contains("\"caseId\":\"D-10291\""));
        assertTrue(caseReviewJson.contains("\"disputeType\":\"GOODS_NOT_RECEIVED\""));

        String policyJson = objectMapper.writeValueAsString(new PolicyRequest("GOODS_NOT_RECEIVED"));
        assertTrue(policyJson.contains("\"disputeType\":\"GOODS_NOT_RECEIVED\""));
    }
}
```

- [ ] **Step 3: Run the test to verify it fails to compile**

Run: `mvn -q -pl workbench-common test`
Expected: FAIL — compilation error, `cannot find symbol: class CaseReviewResult` (and siblings) because the records don't exist yet.

- [ ] **Step 4: Implement the four records**

`workbench-common/src/main/java/com/workbench/common/a2a/CaseReviewRequest.java`:
```java
package com.workbench.common.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaseReviewRequest(String caseId, String disputeType) {
}
```

`workbench-common/src/main/java/com/workbench/common/a2a/CaseReviewResult.java`:
```java
package com.workbench.common.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaseReviewResult(
        String caseId,
        boolean transactionFound,
        String transactionAmount,
        String merchantResponse,
        String merchantPosition,
        List<String> availableDocuments,
        String caseStatus) {
}
```

`workbench-common/src/main/java/com/workbench/common/a2a/PolicyRequest.java`:
```java
package com.workbench.common.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PolicyRequest(String disputeType) {
}
```

`workbench-common/src/main/java/com/workbench/common/a2a/PolicyResult.java`:
```java
package com.workbench.common.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PolicyResult(
        String disputeType,
        String policySection,
        String policyInterpretation,
        List<String> requiredEvidence,
        String policyOutcome) {
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -q -pl workbench-common test`
Expected: PASS, 3 tests green.

- [ ] **Step 6: Commit**

```bash
git add workbench-common/pom.xml workbench-common/src/main/java/com/workbench/common/a2a workbench-common/src/test/java/com/workbench/common/a2a
git commit -m "Add workbench-common a2a DTOs (CaseReviewRequest/Result, PolicyRequest/Result)"
```

---

### Task 3: workbench-common — agui + merge packages (EvidenceItem, ActionItem, DocumentTypes, EvidenceReadiness)

**Files:**
- Create: `workbench-common/src/main/java/com/workbench/common/agui/EvidenceItem.java`
- Create: `workbench-common/src/main/java/com/workbench/common/agui/ActionItem.java`
- Create: `workbench-common/src/main/java/com/workbench/common/merge/DocumentTypes.java`
- Create: `workbench-common/src/main/java/com/workbench/common/merge/EvidenceReadiness.java`
- Test: `workbench-common/src/test/java/com/workbench/common/merge/EvidenceReadinessTest.java`

**Interfaces:**
- Consumes: nothing new (same module, no cross-task type dependency).
- Produces: `com.workbench.common.agui.EvidenceItem(String label, boolean present)`, `com.workbench.common.agui.ActionItem(String id, String label)` — consumed by `agui-support`'s `A2uiComponents` in Task 7. `com.workbench.common.merge.EvidenceReadiness(int present, int required, List<String> missingItems, String label)` with `static EvidenceReadiness compute(List<String> available, List<String> required)` — consumed by `orchestrator-agent` in Session 5.

- [ ] **Step 1: Write the failing test for `EvidenceReadiness.compute`**

`workbench-common/src/test/java/com/workbench/common/merge/EvidenceReadinessTest.java`:
```java
package com.workbench.common.merge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceReadinessTest {

    private static final List<String> ALL_REQUIRED = List.of(
            "TRANSACTION_RECORD", "MERCHANT_RESPONSE", "CUSTOMER_DECLARATION", "DELIVERY_DISPUTE_PROOF");

    @Test
    void allPresentYieldsNoMissingItems() {
        EvidenceReadiness result = EvidenceReadiness.compute(ALL_REQUIRED, ALL_REQUIRED);

        assertEquals(4, result.present());
        assertEquals(4, result.required());
        assertTrue(result.missingItems().isEmpty());
        assertEquals("4 of 4 required items present", result.label());
    }

    @Test
    void demoCaseTwoOfFourPresent() {
        List<String> available = List.of("TRANSACTION_RECORD", "MERCHANT_RESPONSE");

        EvidenceReadiness result = EvidenceReadiness.compute(available, ALL_REQUIRED);

        assertEquals(2, result.present());
        assertEquals(4, result.required());
        assertEquals(List.of("Customer declaration", "Delivery / non-delivery proof"), result.missingItems());
        assertEquals("2 of 4 required items present", result.label());
    }

    @Test
    void emptyAvailableMeansAllRequiredAreMissing() {
        EvidenceReadiness result = EvidenceReadiness.compute(List.of(), ALL_REQUIRED);

        assertEquals(0, result.present());
        assertEquals(4, result.required());
        assertEquals(
                List.of("Transaction record", "Merchant response", "Customer declaration",
                        "Delivery / non-delivery proof"),
                result.missingItems());
        assertEquals("0 of 4 required items present", result.label());
    }

    @Test
    void matchIsCaseInsensitive() {
        List<String> available = List.of("transaction_record", "Merchant_Response");

        EvidenceReadiness result = EvidenceReadiness.compute(available, ALL_REQUIRED);

        assertEquals(2, result.present());
        assertEquals(List.of("Customer declaration", "Delivery / non-delivery proof"), result.missingItems());
        assertEquals("2 of 4 required items present", result.label());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `mvn -q -pl workbench-common test`
Expected: FAIL — `cannot find symbol: class EvidenceReadiness`.

- [ ] **Step 3: Implement `EvidenceItem`, `ActionItem`, `DocumentTypes`, `EvidenceReadiness`**

`workbench-common/src/main/java/com/workbench/common/agui/EvidenceItem.java`:
```java
package com.workbench.common.agui;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvidenceItem(String label, boolean present) {
}
```

`workbench-common/src/main/java/com/workbench/common/agui/ActionItem.java`:
```java
package com.workbench.common.agui;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionItem(String id, String label) {
}
```

`workbench-common/src/main/java/com/workbench/common/merge/DocumentTypes.java`:
```java
package com.workbench.common.merge;

import java.util.Map;

/**
 * Document-type-code to human-readable name mapping (PLATFORM_CONTRACT.md §10/§B3).
 * Package-private: only {@link EvidenceReadiness} needs it this session.
 */
final class DocumentTypes {

    private static final Map<String, String> HUMAN_READABLE_NAMES = Map.of(
            "TRANSACTION_RECORD", "Transaction record",
            "MERCHANT_RESPONSE", "Merchant response",
            "CUSTOMER_DECLARATION", "Customer declaration",
            "DELIVERY_DISPUTE_PROOF", "Delivery / non-delivery proof");

    private DocumentTypes() {
    }

    static String humanReadable(String docType) {
        return HUMAN_READABLE_NAMES.getOrDefault(docType, docType);
    }
}
```

`workbench-common/src/main/java/com/workbench/common/merge/EvidenceReadiness.java`:
```java
package com.workbench.common.merge;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvidenceReadiness(int present, int required, List<String> missingItems, String label) {

    public static EvidenceReadiness compute(List<String> available, List<String> required) {
        List<String> missingItems = new ArrayList<>();
        int presentCount = 0;
        for (String requiredItem : required) {
            boolean found = available.stream().anyMatch(item -> item.equalsIgnoreCase(requiredItem));
            if (found) {
                presentCount++;
            } else {
                missingItems.add(DocumentTypes.humanReadable(requiredItem));
            }
        }
        String label = presentCount + " of " + required.size() + " required items present";
        return new EvidenceReadiness(presentCount, required.size(), missingItems, label);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -pl workbench-common test`
Expected: PASS, 4 tests green (plus the 3 from Task 2 still green).

- [ ] **Step 5: Commit**

```bash
git add workbench-common/src/main/java/com/workbench/common/agui workbench-common/src/main/java/com/workbench/common/merge workbench-common/src/test/java/com/workbench/common/merge
git commit -m "Add workbench-common EvidenceItem/ActionItem and EvidenceReadiness.compute"
```

---

### Task 4: workbench-common — session package (PendingApproval)

**Files:**
- Create: `workbench-common/src/main/java/com/workbench/common/session/PendingApproval.java`
- Test: `workbench-common/src/test/java/com/workbench/common/session/PendingApprovalTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `com.workbench.common.session.PendingApproval(String threadId, String surfaceId, String caseId, String taskType, List<String> missingItems, boolean executed)` with instance method `withExecuted(boolean executed)` — consumed by `orchestrator-agent` in Session 5 to implement the idempotency rule in `PLATFORM_CONTRACT.md` §6.4.

- [ ] **Step 1: Write the failing test**

`workbench-common/src/test/java/com/workbench/common/session/PendingApprovalTest.java`:
```java
package com.workbench.common.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingApprovalTest {

    @Test
    void withExecutedReturnsNewRecordWithUpdatedValueAndLeavesOriginalUnchanged() {
        PendingApproval original = new PendingApproval(
                "thread-1", "surface-1", "D-10291", "MISSING_EVIDENCE_REQUEST",
                List.of("CUSTOMER_DECLARATION", "DELIVERY_DISPUTE_PROOF"), false);

        PendingApproval executed = original.withExecuted(true);

        assertTrue(executed.executed());
        assertFalse(original.executed());
        assertEquals(original.threadId(), executed.threadId());
        assertEquals(original.surfaceId(), executed.surfaceId());
        assertEquals(original.caseId(), executed.caseId());
        assertEquals(original.taskType(), executed.taskType());
        assertEquals(original.missingItems(), executed.missingItems());
        assertEquals(original, original.withExecuted(false));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `mvn -q -pl workbench-common test`
Expected: FAIL — `cannot find symbol: class PendingApproval`.

- [ ] **Step 3: Implement `PendingApproval`**

`workbench-common/src/main/java/com/workbench/common/session/PendingApproval.java`:
```java
package com.workbench.common.session;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PendingApproval(
        String threadId,
        String surfaceId,
        String caseId,
        String taskType,
        List<String> missingItems,
        boolean executed) {

    public PendingApproval withExecuted(boolean executed) {
        return new PendingApproval(threadId, surfaceId, caseId, taskType, missingItems, executed);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -pl workbench-common test`
Expected: PASS, 1 new test green (8 total in the module).

- [ ] **Step 5: Run the full module test suite and confirm zero failures**

Run: `mvn -pl workbench-common test`
Expected: `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0` (3 from `A2aSerializationTest` + 4 from `EvidenceReadinessTest` + 1 from `PendingApprovalTest`).

- [ ] **Step 6: Commit**

```bash
git add workbench-common/src/main/java/com/workbench/common/session workbench-common/src/test/java/com/workbench/common/session
git commit -m "Add workbench-common PendingApproval with withExecuted"
```

---

### Task 5: agui-support — module scaffold + events package (AguiEvents, JsonPatchOp)

**Files:**
- Create: `agui-support/pom.xml`
- Create: `agui-support/src/main/java/com/workbench/agui/events/AguiEvents.java`
- Create: `agui-support/src/main/java/com/workbench/agui/events/JsonPatchOp.java`
- Test: `agui-support/src/test/java/com/workbench/agui/events/AguiEventsSerializationTest.java`

**Interfaces:**
- Consumes: `workbench-common` module (Task 1's parent POM + Tasks 2–4's JAR), though this task's records don't actually reference any `workbench-common` type directly.
- Produces: `com.workbench.agui.events.AguiEvents` containing nested public records `RunStartedEvent(String threadId, String runId)`, `RunFinishedEvent(String threadId, String runId)`, `RunErrorEvent(String threadId, String runId, String message[, String code])`, `CustomEvent` with static factories `progress(String source, String text)` / `a2ui(Object a2uiMessage)`, `StateDeltaEvent(List<JsonPatchOp> delta)`, `StateSnapshotEvent(Object snapshot)` — consumed by `AguiEmitter` (Task 6) and `orchestrator-agent` (Session 5). `com.workbench.agui.events.JsonPatchOp(String op, String path, Object value)`.

- [ ] **Step 1: Create the module POM**

`agui-support/pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.workbench</groupId>
    <artifactId>agentic-dispute-workbench-platform</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>agui-support</artifactId>
  <packaging>jar</packaging>

  <!--
    Depends on workbench-common and WebFlux only (for Flux/Sinks/ServerSentEvent).
    NO @SpringBootApplication here — this is a library module, not a Boot app.
    com.ag-ui:core is intentionally NOT a dependency: it does not exist as a
    resolvable Maven Central artifact (verified 2026-07-15; see the comment at
    the top of AguiEvents.java for the full finding). This module defines its
    own event records per PLATFORM_CONTRACT.md §11.
  -->

  <dependencies>
    <dependency>
      <groupId>com.workbench</groupId>
      <artifactId>workbench-common</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-webflux</artifactId>
    </dependency>
    <dependency>
      <groupId>tools.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-annotations</artifactId>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>io.projectreactor</groupId>
      <artifactId>reactor-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Write the failing serialization test**

`agui-support/src/test/java/com/workbench/agui/events/AguiEventsSerializationTest.java`:
```java
package com.workbench.agui.events;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AguiEventsSerializationTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void runStartedEventRoundTrips() {
        AguiEvents.RunStartedEvent original = new AguiEvents.RunStartedEvent("thread-1", "run-1");

        String json = objectMapper.writeValueAsString(original);
        JsonNode node = objectMapper.readTree(json);
        assertEquals("RUN_STARTED", node.get("type").asString());
        assertEquals("thread-1", node.get("threadId").asString());
        assertEquals("run-1", node.get("runId").asString());

        assertEquals(original, objectMapper.readValue(json, AguiEvents.RunStartedEvent.class));
    }

    @Test
    void runFinishedEventRoundTrips() {
        AguiEvents.RunFinishedEvent original = new AguiEvents.RunFinishedEvent("thread-1", "run-1");

        String json = objectMapper.writeValueAsString(original);
        JsonNode node = objectMapper.readTree(json);
        assertEquals("RUN_FINISHED", node.get("type").asString());

        assertEquals(original, objectMapper.readValue(json, AguiEvents.RunFinishedEvent.class));
    }

    @Test
    void runErrorEventWithCodeRoundTrips() {
        AguiEvents.RunErrorEvent original = new AguiEvents.RunErrorEvent("thread-1", "run-1", "boom", "E123");

        String json = objectMapper.writeValueAsString(original);
        JsonNode node = objectMapper.readTree(json);
        assertEquals("RUN_ERROR", node.get("type").asString());
        assertEquals("boom", node.get("message").asString());
        assertEquals("E123", node.get("code").asString());

        assertEquals(original, objectMapper.readValue(json, AguiEvents.RunErrorEvent.class));
    }

    @Test
    void runErrorEventWithoutCodeOmitsCodeField() {
        AguiEvents.RunErrorEvent original = new AguiEvents.RunErrorEvent("thread-1", "run-1", "boom");

        String json = objectMapper.writeValueAsString(original);

        assertFalse(json.contains("\"code\""));
    }

    @Test
    void customProgressEventRoundTrips() {
        AguiEvents.CustomEvent original = AguiEvents.CustomEvent.progress("orchestrator", "Understanding dispute...");

        String json = objectMapper.writeValueAsString(original);
        JsonNode node = objectMapper.readTree(json);
        assertEquals("CUSTOM", node.get("type").asString());
        assertEquals("progress", node.get("name").asString());
        assertEquals("orchestrator", node.get("value").get("source").asString());
        assertEquals("Understanding dispute...", node.get("value").get("text").asString());
    }

    @Test
    void customA2uiEventWrapsArbitraryPayload() {
        AguiEvents.CustomEvent original = AguiEvents.CustomEvent.a2ui(Map.of("version", "v0.9"));

        String json = objectMapper.writeValueAsString(original);
        JsonNode node = objectMapper.readTree(json);
        assertEquals("CUSTOM", node.get("type").asString());
        assertEquals("a2ui", node.get("name").asString());
        assertEquals("v0.9", node.get("value").get("version").asString());
    }

    @Test
    void stateDeltaEventWithReplaceOpSerializesToRfc6902Shape() {
        AguiEvents.StateDeltaEvent event = new AguiEvents.StateDeltaEvent(
                List.of(new JsonPatchOp("replace", "/evidenceReadiness", "2 of 4 required items present")));

        String json = objectMapper.writeValueAsString(event);
        JsonNode node = objectMapper.readTree(json);
        assertEquals("STATE_DELTA", node.get("type").asString());
        JsonNode op = node.get("delta").get(0);
        assertEquals("replace", op.get("op").asString());
        assertEquals("/evidenceReadiness", op.get("path").asString());
        assertEquals("2 of 4 required items present", op.get("value").asString());
    }

    @Test
    void stateSnapshotEventWithPlainMapSnapshotSerializesCorrectly() {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("evidenceReadiness", null);
        AguiEvents.StateSnapshotEvent event = new AguiEvents.StateSnapshotEvent(snapshot);

        String json = objectMapper.writeValueAsString(event);
        JsonNode node = objectMapper.readTree(json);
        assertEquals("STATE_SNAPSHOT", node.get("type").asString());
        assertTrue(node.get("snapshot").has("evidenceReadiness"));
    }
}
```

- [ ] **Step 3: Run the test to verify it fails to compile**

Run: `mvn -q -pl agui-support test`
Expected: FAIL — `cannot find symbol: class AguiEvents` (and `JsonPatchOp`).

- [ ] **Step 4: Implement `AguiEvents` and `JsonPatchOp`**

`agui-support/src/main/java/com/workbench/agui/events/AguiEvents.java`:
```java
package com.workbench.agui.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * AG-UI event records mirroring the AG-UI wire protocol exactly (PLATFORM_CONTRACT.md §6.1).
 * Field names are EXACT camelCase matches to the spec — the UI validates these field names.
 *
 * <p><b>SDK finding (verified against Maven Central and the ag-ui-protocol/ag-ui GitHub repo,
 * 2026-07-15):</b> {@code com.ag-ui:core} is NOT a resolvable Maven Central artifact.
 * {@code com/ag-ui/core/maven-metadata.xml} returns HTTP 404. The upstream repository's
 * {@code sdks/community/java} directory contains only a {@code .gitkeep} placeholder — no
 * Java/JVM code has been published there — and per the repo's {@code jitpack.yml}
 * ({@code cd sdks/community/java && mvn install}), the Java SDK, when it exists, is
 * distributed via JitPack, never Maven Central. There is therefore no SDK type to evaluate
 * for Jackson 3 compatibility: this file defines the platform's own records per
 * PLATFORM_CONTRACT.md §11, matching AG-UI wire field names exactly.
 */
public final class AguiEvents {

    private AguiEvents() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunStartedEvent(
            @JsonProperty("type") String type,
            @JsonProperty("threadId") String threadId,
            @JsonProperty("runId") String runId) {

        public RunStartedEvent(String threadId, String runId) {
            this("RUN_STARTED", threadId, runId);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunFinishedEvent(
            @JsonProperty("type") String type,
            @JsonProperty("threadId") String threadId,
            @JsonProperty("runId") String runId) {

        public RunFinishedEvent(String threadId, String runId) {
            this("RUN_FINISHED", threadId, runId);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunErrorEvent(
            @JsonProperty("type") String type,
            @JsonProperty("threadId") String threadId,
            @JsonProperty("runId") String runId,
            @JsonProperty("message") String message,
            @JsonProperty("code") String code) {

        public RunErrorEvent(String threadId, String runId, String message) {
            this("RUN_ERROR", threadId, runId, message, null);
        }

        public RunErrorEvent(String threadId, String runId, String message, String code) {
            this("RUN_ERROR", threadId, runId, message, code);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CustomEvent(
            @JsonProperty("type") String type,
            @JsonProperty("name") String name,
            @JsonProperty("value") Object value) {

        public static CustomEvent progress(String source, String text) {
            return new CustomEvent("CUSTOM", "progress", new ProgressValue(source, text));
        }

        public static CustomEvent a2ui(Object a2uiMessage) {
            return new CustomEvent("CUSTOM", "a2ui", a2uiMessage);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProgressValue(
            @JsonProperty("source") String source,
            @JsonProperty("text") String text) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StateDeltaEvent(
            @JsonProperty("type") String type,
            @JsonProperty("delta") List<JsonPatchOp> delta) {

        public StateDeltaEvent(List<JsonPatchOp> delta) {
            this("STATE_DELTA", delta);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StateSnapshotEvent(
            @JsonProperty("type") String type,
            @JsonProperty("snapshot") Object snapshot) {

        public StateSnapshotEvent(Object snapshot) {
            this("STATE_SNAPSHOT", snapshot);
        }
    }
}
```

`agui-support/src/main/java/com/workbench/agui/events/JsonPatchOp.java`:
```java
package com.workbench.agui.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonPatchOp(
        @JsonProperty("op") String op,
        @JsonProperty("path") String path,
        @JsonProperty("value") Object value) {
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -q -pl agui-support test`
Expected: PASS, 8 tests green.

- [ ] **Step 6: Commit**

```bash
git add agui-support/pom.xml agui-support/src/main/java/com/workbench/agui/events agui-support/src/test/java/com/workbench/agui/events
git commit -m "Add agui-support module and AguiEvents/JsonPatchOp records"
```

---

### Task 6: agui-support — emitter package (AguiEmitter)

**Files:**
- Create: `agui-support/src/main/java/com/workbench/agui/emitter/AguiEmitter.java`
- Test: `agui-support/src/test/java/com/workbench/agui/emitter/AguiEmitterTest.java`

**Interfaces:**
- Consumes: `AguiEvents.RunStartedEvent/RunFinishedEvent/RunErrorEvent/CustomEvent` from Task 5; `tools.jackson.databind.ObjectMapper`.
- Produces: `com.workbench.agui.emitter.AguiEmitter(ObjectMapper objectMapper)` with `void emit(Object event)`, `void complete(String threadId, String runId)`, `void error(String threadId, String runId, String message)`, `Flux<ServerSentEvent<String>> flux()` — consumed by `orchestrator-agent` in Session 5 to serve the `GET /agui` SSE endpoint.

- [ ] **Step 1: Write the failing test**

`agui-support/src/test/java/com/workbench/agui/emitter/AguiEmitterTest.java`:
```java
package com.workbench.agui.emitter;

import com.workbench.agui.events.AguiEvents;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AguiEmitterTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void emitRunStartedEventProducesMatchingSseData() {
        AguiEmitter emitter = new AguiEmitter(objectMapper);

        emitter.emit(new AguiEvents.RunStartedEvent("thread-1", "run-1"));
        emitter.complete("thread-1", "run-1");

        StepVerifier.create(emitter.flux())
                .assertNext(sse -> {
                    JsonNode node = objectMapper.readTree(sse.data());
                    assertEquals("RUN_STARTED", node.get("type").asString());
                    assertEquals("thread-1", node.get("threadId").asString());
                    assertEquals("run-1", node.get("runId").asString());
                })
                .assertNext(sse -> {
                    JsonNode node = objectMapper.readTree(sse.data());
                    assertEquals("RUN_FINISHED", node.get("type").asString());
                })
                .expectComplete()
                .verify(Duration.ofSeconds(2));
    }

    @Test
    void emitProgressCustomEventProducesNestedValueShape() {
        AguiEmitter emitter = new AguiEmitter(objectMapper);

        emitter.emit(AguiEvents.CustomEvent.progress("orchestrator", "Understanding dispute..."));
        emitter.complete("thread-1", "run-1");

        StepVerifier.create(emitter.flux())
                .assertNext(sse -> {
                    JsonNode node = objectMapper.readTree(sse.data());
                    assertEquals("CUSTOM", node.get("type").asString());
                    assertEquals("progress", node.get("name").asString());
                    assertEquals("orchestrator", node.get("value").get("source").asString());
                    assertEquals("Understanding dispute...", node.get("value").get("text").asString());
                })
                .expectNextCount(1)
                .expectComplete()
                .verify(Duration.ofSeconds(2));
    }

    @Test
    void concurrentEmitsFromTwoThreadsBothAppearInFlux() throws InterruptedException {
        AguiEmitter emitter = new AguiEmitter(objectMapper);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Thread t1 = new Thread(() -> {
            ready.countDown();
            awaitLatch(go);
            emitter.emit(AguiEvents.CustomEvent.progress("case-review", "Checking transaction status..."));
        });
        Thread t2 = new Thread(() -> {
            ready.countDown();
            awaitLatch(go);
            emitter.emit(AguiEvents.CustomEvent.progress("policy", "Searching policy document..."));
        });

        t1.start();
        t2.start();
        ready.await(2, TimeUnit.SECONDS);
        go.countDown();
        t1.join(2000);
        t2.join(2000);
        emitter.complete("thread-1", "run-1");

        AtomicInteger progressCount = new AtomicInteger();
        StepVerifier.create(emitter.flux())
                .thenConsumeWhile(sse -> {
                    JsonNode node = objectMapper.readTree(sse.data());
                    if ("CUSTOM".equals(node.get("type").asString())) {
                        progressCount.incrementAndGet();
                    }
                    return !"RUN_FINISHED".equals(node.get("type").asString());
                })
                .expectComplete()
                .verify(Duration.ofSeconds(2));

        assertEquals(2, progressCount.get());
    }

    @Test
    void completeEmitsRunFinishedThenTerminatesFlux() {
        AguiEmitter emitter = new AguiEmitter(objectMapper);

        emitter.complete("thread-1", "run-1");

        StepVerifier.create(emitter.flux())
                .assertNext(sse -> {
                    JsonNode node = objectMapper.readTree(sse.data());
                    assertEquals("RUN_FINISHED", node.get("type").asString());
                })
                .expectComplete()
                .verify(Duration.ofSeconds(2));
    }

    @Test
    void errorEmitsRunErrorThenTerminatesFlux() {
        AguiEmitter emitter = new AguiEmitter(objectMapper);

        emitter.error("thread-1", "run-1", "Something went wrong");

        StepVerifier.create(emitter.flux())
                .assertNext(sse -> {
                    JsonNode node = objectMapper.readTree(sse.data());
                    assertEquals("RUN_ERROR", node.get("type").asString());
                    assertEquals("Something went wrong", node.get("message").asString());
                })
                .expectComplete()
                .verify(Duration.ofSeconds(2));
    }

    @Test
    void serializationErrorEmitsRunErrorAndFluxContinues() {
        AguiEmitter emitter = new AguiEmitter(objectMapper);

        emitter.emit(new ExplodingEvent());
        emitter.complete("thread-1", "run-1");

        StepVerifier.create(emitter.flux())
                .assertNext(sse -> {
                    JsonNode node = objectMapper.readTree(sse.data());
                    assertEquals("RUN_ERROR", node.get("type").asString());
                })
                .assertNext(sse -> {
                    JsonNode node = objectMapper.readTree(sse.data());
                    assertEquals("RUN_FINISHED", node.get("type").asString());
                })
                .expectComplete()
                .verify(Duration.ofSeconds(2));
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record ExplodingEvent(String type) {
        ExplodingEvent() {
            this("EXPLODE");
        }

        @Override
        public String type() {
            throw new IllegalStateException("Deliberate serialization failure for test");
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `mvn -q -pl agui-support test`
Expected: FAIL — `cannot find symbol: class AguiEmitter`.

- [ ] **Step 3: Implement `AguiEmitter`**

`agui-support/src/main/java/com/workbench/agui/emitter/AguiEmitter.java`:
```java
package com.workbench.agui.emitter;

import com.workbench.agui.events.AguiEvents;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.ObjectMapper;

public class AguiEmitter {

    private final Sinks.Many<ServerSentEvent<String>> sink;
    private final ObjectMapper objectMapper;
    private final Object emitLock = new Object();

    public AguiEmitter(ObjectMapper objectMapper) {
        this.sink = Sinks.many().unicast().onBackpressureBuffer();
        this.objectMapper = objectMapper;
    }

    public void emit(Object event) {
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (RuntimeException e) {
            json = objectMapper.writeValueAsString(
                    new AguiEvents.RunErrorEvent(null, null, "Failed to serialize event: " + e.getMessage()));
        }
        synchronized (emitLock) {
            sink.tryEmitNext(ServerSentEvent.builder(json).build());
        }
    }

    public void complete(String threadId, String runId) {
        emit(new AguiEvents.RunFinishedEvent(threadId, runId));
        synchronized (emitLock) {
            sink.tryEmitComplete();
        }
    }

    public void error(String threadId, String runId, String message) {
        emit(new AguiEvents.RunErrorEvent(threadId, runId, message));
        synchronized (emitLock) {
            sink.tryEmitComplete();
        }
    }

    public Flux<ServerSentEvent<String>> flux() {
        return sink.asFlux();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -pl agui-support test`
Expected: PASS, 6 new tests green (14 total in the module).

- [ ] **Step 5: Commit**

```bash
git add agui-support/src/main/java/com/workbench/agui/emitter agui-support/src/test/java/com/workbench/agui/emitter
git commit -m "Add AguiEmitter with thread-safe SSE emission and RUN_ERROR fallback"
```

---

### Task 7: agui-support — a2ui package (A2uiMessages, A2uiComponents)

**Files:**
- Create: `agui-support/src/main/java/com/workbench/agui/a2ui/A2uiMessages.java`
- Create: `agui-support/src/main/java/com/workbench/agui/a2ui/A2uiComponents.java`
- Test: `agui-support/src/test/java/com/workbench/agui/a2ui/A2uiMessagesTest.java`

**Interfaces:**
- Consumes: `com.workbench.common.agui.EvidenceItem` and `com.workbench.common.agui.ActionItem` from Task 3 (`workbench-common`).
- Produces: `com.workbench.agui.a2ui.A2uiMessages.createSurface(String surfaceId, String catalogId)`, `.updateComponents(String surfaceId, List<Object> components)`; `com.workbench.agui.a2ui.A2uiComponents.decisionCard(...)`, `.evidenceChecklist(...)`, `.nextActions(...)`, `.approvalPreview(...)`, `.taskCreatedCard(...)` — all consumed by `orchestrator-agent` in Session 5 via `AguiEvents.CustomEvent.a2ui(...)`.

- [ ] **Step 1: Write the failing test**

`agui-support/src/test/java/com/workbench/agui/a2ui/A2uiMessagesTest.java`:
```java
package com.workbench.agui.a2ui;

import com.workbench.common.agui.ActionItem;
import com.workbench.common.agui.EvidenceItem;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2uiMessagesTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void createSurfaceSerializesToExactShape() {
        Object message = A2uiMessages.createSurface(
                "case-D-10291", "https://dispute-workbench.internal/catalogs/v1.json");

        String json = objectMapper.writeValueAsString(message);

        assertEquals(
                "{\"version\":\"v0.9\",\"createSurface\":{\"surfaceId\":\"case-D-10291\","
                        + "\"catalogId\":\"https://dispute-workbench.internal/catalogs/v1.json\"}}",
                json);
    }

    @Test
    void updateComponentsWithDecisionCardChecklistAndActionsProducesSection72Shape() {
        Object decisionCard = A2uiComponents.decisionCard(
                "decision-1", "checklist-1", "actions-1",
                "Needs More Evidence", "Goods Not Received",
                "2 of 4 required items present", "Create evidence request task");
        Object checklist = A2uiComponents.evidenceChecklist("checklist-1", List.of(
                new EvidenceItem("Transaction record", true),
                new EvidenceItem("Merchant response", true),
                new EvidenceItem("Customer declaration", false),
                new EvidenceItem("Delivery / non-delivery proof", false)));
        Object actions = A2uiComponents.nextActions("actions-1", List.of(
                new ActionItem("create_evidence_request_task", "Create Evidence Request Task"),
                new ActionItem("escalate_to_reviewer", "Escalate to Reviewer"),
                new ActionItem("save_case_note", "Save Case Note")));

        Object message = A2uiMessages.updateComponents("case-D-10291", List.of(decisionCard, checklist, actions));

        String json = objectMapper.writeValueAsString(message);
        JsonNode node = objectMapper.readTree(json);

        assertEquals("v0.9", node.get("version").asString());
        assertEquals("case-D-10291", node.get("updateComponents").get("surfaceId").asString());
        JsonNode components = node.get("updateComponents").get("components");
        assertEquals(3, components.size());

        JsonNode decisionCardNode = components.get(0);
        assertEquals("DecisionCard", decisionCardNode.get("component").asString());
        assertEquals("checklist-1", decisionCardNode.get("checklistId").asString());
        assertEquals("actions-1", decisionCardNode.get("actionsId").asString());

        JsonNode checklistNode = components.get(1);
        assertEquals("EvidenceChecklist", checklistNode.get("component").asString());
        assertEquals(4, checklistNode.get("items").size());
        assertTrue(checklistNode.get("items").get(0).get("present").asBoolean());

        JsonNode actionsNode = components.get(2);
        assertEquals("NextActions", actionsNode.get("component").asString());
        assertEquals(3, actionsNode.get("actions").size());
    }

    @Test
    void approvalPreviewSerializesWithAllFieldsPerSection72() {
        Object approvalPreview = A2uiComponents.approvalPreview(
                "approval-1", "D-10291", "Pending Evidence",
                List.of("Customer declaration", "Delivery / non-delivery proof"),
                "Create task in case system and update case status.");

        String json = objectMapper.writeValueAsString(approvalPreview);
        JsonNode node = objectMapper.readTree(json);

        assertEquals("approval-1", node.get("id").asString());
        assertEquals("ApprovalPreview", node.get("component").asString());
        assertEquals("D-10291", node.get("caseId").asString());
        assertEquals("Pending Evidence", node.get("newCaseStatus").asString());
        assertEquals(2, node.get("missingItems").size());
        assertEquals("Create task in case system and update case status.",
                node.get("actionAfterApproval").asString());
        assertEquals("approve_task_creation", node.get("onApprove").get("id").asString());
        assertEquals("edit_task_creation", node.get("onEdit").get("id").asString());
        assertEquals("cancel_task_creation", node.get("onCancel").get("id").asString());
    }

    @Test
    void taskCreatedCardSerializesWithAllFieldsPerSection72() {
        Object taskCreatedCard = A2uiComponents.taskCreatedCard(
                "task-created-1", "EVID-88421", "Pending Evidence", "Created", "Dispute Operations Queue");

        String json = objectMapper.writeValueAsString(taskCreatedCard);
        JsonNode node = objectMapper.readTree(json);

        assertEquals("task-created-1", node.get("id").asString());
        assertEquals("TaskCreatedCard", node.get("component").asString());
        assertEquals("EVID-88421", node.get("taskId").asString());
        assertEquals("Pending Evidence", node.get("caseStatus").asString());
        assertEquals("Created", node.get("auditEntry").asString());
        assertEquals("Dispute Operations Queue", node.get("nextOwner").asString());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `mvn -q -pl agui-support test`
Expected: FAIL — `cannot find symbol: class A2uiMessages` (and `A2uiComponents`).

- [ ] **Step 3: Implement `A2uiMessages` and `A2uiComponents`**

`agui-support/src/main/java/com/workbench/agui/a2ui/A2uiMessages.java`:
```java
package com.workbench.agui.a2ui;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A2UI envelope builders (PLATFORM_CONTRACT.md §7). Component-level builders live in
 * {@link A2uiComponents}.
 */
public final class A2uiMessages {

    private static final String VERSION = "v0.9";

    private A2uiMessages() {
    }

    /** §7.1 — createSurface, sent once per session, idempotent. */
    public static Object createSurface(String surfaceId, String catalogId) {
        return new CreateSurfaceMessage(VERSION, new CreateSurfacePayload(surfaceId, catalogId));
    }

    /** §7.2 — updateComponents, flat entries, id-referenced composition. */
    public static Object updateComponents(String surfaceId, List<Object> components) {
        return new UpdateComponentsMessage(VERSION, new UpdateComponentsPayload(surfaceId, components));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record CreateSurfaceMessage(
            @JsonProperty("version") String version,
            @JsonProperty("createSurface") CreateSurfacePayload createSurface) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record CreateSurfacePayload(
            @JsonProperty("surfaceId") String surfaceId,
            @JsonProperty("catalogId") String catalogId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record UpdateComponentsMessage(
            @JsonProperty("version") String version,
            @JsonProperty("updateComponents") UpdateComponentsPayload updateComponents) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record UpdateComponentsPayload(
            @JsonProperty("surfaceId") String surfaceId,
            @JsonProperty("components") List<Object> components) {
    }
}
```

`agui-support/src/main/java/com/workbench/agui/a2ui/A2uiComponents.java`:
```java
package com.workbench.agui.a2ui;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.workbench.common.agui.ActionItem;
import com.workbench.common.agui.EvidenceItem;

import java.util.List;

/** Flat component entries for the {@code updateComponents.components} list (PLATFORM_CONTRACT.md §7.2). */
public final class A2uiComponents {

    private A2uiComponents() {
    }

    /** §7.2 — DecisionCard entry. */
    public static Object decisionCard(String id, String checklistId, String actionsId,
            String status, String disputeType, String evidenceReadiness, String recommendedAction) {
        return new DecisionCardEntry(id, "DecisionCard", status, disputeType, evidenceReadiness,
                recommendedAction, checklistId, actionsId);
    }

    /** §7.2 — EvidenceChecklist entry. */
    public static Object evidenceChecklist(String id, List<EvidenceItem> items) {
        return new EvidenceChecklistEntry(id, "EvidenceChecklist", items);
    }

    /** §7.2 — NextActions entry. */
    public static Object nextActions(String id, List<ActionItem> actions) {
        return new NextActionsEntry(id, "NextActions", actions);
    }

    /** §7.2 — ApprovalPreview entry. onApprove/onEdit/onCancel action ids are frozen by the contract. */
    public static Object approvalPreview(String id, String caseId, String newCaseStatus,
            List<String> missingItems, String actionAfterApproval) {
        return new ApprovalPreviewEntry(id, "ApprovalPreview", caseId, newCaseStatus, missingItems,
                actionAfterApproval,
                new ActionRef("approve_task_creation"),
                new ActionRef("edit_task_creation"),
                new ActionRef("cancel_task_creation"));
    }

    /** §7.2 — TaskCreatedCard entry. */
    public static Object taskCreatedCard(String id, String taskId, String caseStatus,
            String auditEntry, String nextOwner) {
        return new TaskCreatedCardEntry(id, "TaskCreatedCard", taskId, caseStatus, auditEntry, nextOwner);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record DecisionCardEntry(
            @JsonProperty("id") String id,
            @JsonProperty("component") String component,
            @JsonProperty("status") String status,
            @JsonProperty("disputeType") String disputeType,
            @JsonProperty("evidenceReadiness") String evidenceReadiness,
            @JsonProperty("recommendedAction") String recommendedAction,
            @JsonProperty("checklistId") String checklistId,
            @JsonProperty("actionsId") String actionsId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record EvidenceChecklistEntry(
            @JsonProperty("id") String id,
            @JsonProperty("component") String component,
            @JsonProperty("items") List<EvidenceItem> items) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record NextActionsEntry(
            @JsonProperty("id") String id,
            @JsonProperty("component") String component,
            @JsonProperty("actions") List<ActionItem> actions) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ApprovalPreviewEntry(
            @JsonProperty("id") String id,
            @JsonProperty("component") String component,
            @JsonProperty("caseId") String caseId,
            @JsonProperty("newCaseStatus") String newCaseStatus,
            @JsonProperty("missingItems") List<String> missingItems,
            @JsonProperty("actionAfterApproval") String actionAfterApproval,
            @JsonProperty("onApprove") ActionRef onApprove,
            @JsonProperty("onEdit") ActionRef onEdit,
            @JsonProperty("onCancel") ActionRef onCancel) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record TaskCreatedCardEntry(
            @JsonProperty("id") String id,
            @JsonProperty("component") String component,
            @JsonProperty("taskId") String taskId,
            @JsonProperty("caseStatus") String caseStatus,
            @JsonProperty("auditEntry") String auditEntry,
            @JsonProperty("nextOwner") String nextOwner) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ActionRef(@JsonProperty("id") String id) {
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -pl agui-support test`
Expected: PASS, 4 new tests green (18 total in the module).

- [ ] **Step 5: Commit**

```bash
git add agui-support/src/main/java/com/workbench/agui/a2ui agui-support/src/test/java/com/workbench/agui/a2ui
git commit -m "Add A2uiMessages and A2uiComponents builders matching PLATFORM_CONTRACT §7"
```

---

### Task 8: Final verification (Acceptance §D)

**Files:** none created — verification only.

**Interfaces:**
- Consumes: everything from Tasks 1–7.
- Produces: nothing new; this task is the acceptance gate before Session 2.

- [ ] **Step 1: Run the full reactor test suite**

Run: `mvn test`
Expected: `BUILD SUCCESS`. Both `workbench-common` (8 tests) and `agui-support` (18 tests) report zero failures/errors. The four stub modules (`packaging=pom`) report no tests (nothing to run — expected, not a failure).

- [ ] **Step 2: Run the full reactor package build**

Run: `mvn package`
Expected: `BUILD SUCCESS`. `workbench-common/target/workbench-common-1.0.0-SNAPSHOT.jar` and `agui-support/target/agui-support-1.0.0-SNAPSHOT.jar` exist as plain library JARs (no `spring-boot-maven-plugin` repackaging — verify with `jar tf agui-support/target/agui-support-1.0.0-SNAPSHOT.jar | grep -c BOOT-INF` returning `0`).

- [ ] **Step 3: Verify no illegal Jackson 2 core/databind imports**

Run:
```bash
grep -rn "com\.fasterxml\.jackson\.core\.\|com\.fasterxml\.jackson\.databind\." workbench-common/src agui-support/src
```
Expected: no output (exit code 1 / nothing matched). This targets Jackson 2's `core`/`databind` packages specifically — it will not flag `com.fasterxml.jackson.annotation.*` imports, which are the documented, necessary exception (see Global Constraints above and the comment in `AguiEvents.java`).

- [ ] **Step 4: Confirm the Jackson 3 finding is documented in `AguiEvents.java`**

Run: `grep -n "SDK finding" agui-support/src/main/java/com/workbench/agui/events/AguiEvents.java`
Expected: one match — the class Javadoc comment written in Task 5, Step 4.

- [ ] **Step 5: Print the `updateComponents` JSON shape the frontend contract depends on**

Run:
```bash
mvn -q -pl agui-support test -Dtest=A2uiMessagesTest#updateComponentsWithDecisionCardChecklistAndActionsProducesSection72Shape -DfailIfNoTests=false
```
Then, to see the literal JSON (not just pass/fail), temporarily add a `System.out.println(json)` in that test method locally, rerun, capture the output, and paste it into the session handoff notes — then remove the printout before the final commit. This is the shape `orchestrator-agent` (Session 5) must reproduce byte-for-byte in its `CUSTOM/a2ui updateComponents` events.

- [ ] **Step 6: Final commit if any verification step required a fix**

If Steps 1–5 all passed without needing changes, there is nothing to commit here — Task 7's commit is already the final state. If a fix was needed, stage it and commit:
```bash
git add -A
git commit -m "Fix issues found during Session 1 acceptance verification"
```
