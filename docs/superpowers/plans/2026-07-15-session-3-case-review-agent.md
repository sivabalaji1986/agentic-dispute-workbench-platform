# Session 3 — case-review-agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `case-review-agent` — a Spring Boot 4 app that is simultaneously an A2A server (specialist agent, called by the future orchestrator) and an MCP client (reads `case-system-mcp`) — implementing PLATFORM_CONTRACT §8.2's contract exactly, with human-readable evidence labels (closing Session 1 hardening's Minor 2), and a contract-tests fixture the orchestrator will build against in Session 5.

**Architecture:** `CaseReviewAgentExecutor` (`@Service`) holds all business logic behind one directly-testable method (`String execute(String messageText)`) — parse input, call `case-system-mcp` via `CaseMcpClient` (MCP sync client), build `EvidenceItem`s using `workbench-common`'s `DocumentTypes`, use `ChatClient` to extract merchant-response/position from the case's dispute text, and serialize a `CaseReviewResult` + progress lines wrapper as the A2A response. The A2A protocol adapter layer (`DefaultAgentExecutor`, `AgentCard`) is a thin wrapper around this — the real library machinery, not hand-rolled EventQueue/Message plumbing, per this session's own instruction to verify APIs rather than guess.

**Tech Stack:** Java 25, Spring Boot 4.0.7 (servlet, no WebFlux/JPA), Spring AI 2.0.0 MCP client + Ollama ChatClient, Spring AI Community A2A server 0.3.0, JUnit 5, Mockito.

## Global Constraints

**Verified findings — six of them contradict or go beyond the session prompt's assumptions. All verified live against Maven Central, decompiled bytecode, and the actual `spring-ai-community/spring-ai-a2a` GitHub repo on 2026-07-15. Do not deviate from these.**

1. **The A2A Java SDK's real groupId is `io.github.a2asdk`, not `io.a2a`** as the session prompt assumed — there is no `io.a2a:a2a-core`/`a2a-client`/`a2a-server` on Maven Central. We don't depend on it directly anyway: `org.springaicommunity:spring-ai-a2a-server-autoconfigure:0.3.0` (verified latest, explicit version — not managed by our parent's `spring-ai-bom`) pulls in `org.springaicommunity:spring-ai-a2a-server:0.3.0`, which transitively pulls `io.github.a2asdk:a2a-java-sdk-spec:0.3.3.Final` and `a2a-java-sdk-server-common:0.3.3.Final`.

2. **The A2A SDK genuinely, unavoidably depends on Jackson 2's `jackson-databind`** (confirmed at compile scope in both `a2a-java-sdk-spec` and `a2a-java-sdk-server-common`'s real POMs on Maven Central) for its own internal protocol message serialization — no Jackson-3-based A2A SDK exists. This trips the parent POM's `enforce-no-jackson2-core-databind` enforcer rule the moment this module adds the A2A dependency. **Resolution (already applied to `PLATFORM_CONTRACT.md`):** `case-review-agent/pom.xml` overrides/disables the inherited enforcer execution locally (re-declare the same execution `id` with `<phase>none</phase>`), with a comment explaining why. This module's OWN code still exclusively uses `tools.jackson.core.*`/`tools.jackson.databind.*` for its own serialization (`CaseReviewResult`, `AgentResponse`) and `com.fasterxml.jackson.annotation.*` for annotations — Jackson 2 exists on the classpath only as the A2A library's internal implementation detail, never touched by our imports. The acceptance grep (`case-review-agent/src` only, not the dependency tree) still passes cleanly.

3. **There is no `AgentCard`-related Spring Boot property** — `A2AServerProperties` (the only properties class `spring-ai-a2a-server-autoconfigure` exposes, prefix `spring.ai.a2a.server`) has exactly one field, `enabled` (boolean). The Agent Card (name, description, url, version, capabilities, skills, etc.) is built entirely as a Java `@Bean AgentCard` using `io.a2a.spec.AgentCard.Builder` — confirmed via both bytecode decompilation and the real `spring-ai-community/spring-ai-a2a` example app (`WeatherAgentApplication.java`, fetched verbatim from GitHub).

4. **`AgentExecutor` should be built via `org.springaicommunity.a2a.server.executor.DefaultAgentExecutor`, not hand-implemented.** The raw `io.a2a.server.agentexecution.AgentExecutor` interface (`execute(RequestContext, EventQueue)`, void, throws `JSONRPCError`) requires manually constructing `Message`/`TextPart`/`EventQueue` objects — real, working library code exists to avoid this: `DefaultAgentExecutor(ChatClient, ChatClientExecutorHandler)`, where `ChatClientExecutorHandler` is a single-method functional interface `String execute(ChatClient, RequestContext)`. This is the verified, real, tested pattern (confirmed against the library's own example app) — using it instead of hand-rolling the EventQueue protocol is far lower risk than an unverified from-scratch implementation.

5. **The real MCP tool-calling and result-extraction API** (`io.modelcontextprotocol.sdk:mcp-core:2.0.0`, the actual implementation artifact behind the `io.modelcontextprotocol.sdk:mcp` facade — the facade jar itself contains zero classes): `McpSyncClient.callTool(McpSchema.CallToolRequest)` returns `McpSchema.CallToolResult`, which has a `structuredContent()` accessor returning `Object` — this is what Spring AI's MCP server populates from a `@Tool` method's `Map<String,Object>` return value, so `case-system-mcp`'s five tools' results come back as a directly-usable `Map`. Client-side sync connection config: `spring.ai.mcp.client.type=SYNC`, `spring.ai.mcp.client.streamable-http.connections.<name>.url=...` (`McpStreamableHttpClientProperties`, confirmed prefix `spring.ai.mcp.client.streamable-http`), via the `org.springframework.ai:spring-ai-starter-mcp-client` starter (non-WebFlux, httpclient-based transport — matches "No WebFlux" for this module). The MCP Java SDK itself is Jackson-3-native (`mcp-json-jackson3` module) — no conflict with our platform's Jackson rule.

6. **Resolving the session prompt's internal contradiction on test strategy:** §A lists Testcontainers (`postgresql` + `junit-jupiter`) as a module dependency; §E explicitly recommends Option B (WireMock/mocking) instead and says not to do the full stack here ("Full stack integration tested in Session 6 infra smoke test"). Resolution: this module has **no Testcontainers dependency at all**. Further refinement of §E's own suggestion: raw WireMock stubbing of the actual MCP JSON-RPC wire protocol (multi-step handshake: initialize, notifications, tool call) is significantly more complex and fragile than the session prompt's "Option B" framing suggests, and Spring AI's `ChatClient` fluent API is equally impractical to stub via raw HTTP without exact knowledge of the Ollama wire format. This plan uses **Mockito mocking at the `CaseMcpClient`/`ChatClient` Java API boundary** instead of WireMock at the HTTP boundary — same stated goal (fast, isolated, tests our serialization/mapping contract rather than real tool behavior), lower implementation risk, and it's a documented, explicit choice per §E's own instruction to "make this choice explicitly and state it in a comment."

**Additional finding — a genuine cross-session visibility gap, not a contradiction:** `workbench-common`'s `DocumentTypes` class (and its `humanReadable(String)` lookup method) is currently **package-private** (`final class DocumentTypes` in `com.workbench.common.merge`, deliberately scoped that way in Session 1: "Package-private: only `EvidenceReadiness` needs it this session"). This session's own §C3 requirement — "Use `DocumentTypes` map from `workbench-common`" — is impossible to satisfy as written, since `case-review-agent` is a different module/package and cannot see a package-private class. Task 1 promotes `DocumentTypes` and `humanReadable(String)` to `public` (the backing map itself stays `private static final`, preserving encapsulation) — this is the natural fulfillment of a need Session 1 explicitly deferred ("only `EvidenceReadiness` needs it **this session**"), not an architecture change.

- No `com.fasterxml.jackson.core`/`.databind` imports in `case-review-agent/src` (the enforcer exemption covers the transitive A2A dependency, not our own code — our own code follows the platform rule exactly like every other module).
- `EvidenceItem.label` must be the human-readable form (`DocumentTypes.humanReadable(docType)`), never the raw `docType` code — this is Minor 2 from Session 1 hardening, now being closed for real.
- Tool method names called via MCP (`get_case`, `list_case_documents`) and A2A response field names (`CaseReviewResult`'s existing `workbench-common` shape) must match PLATFORM_CONTRACT exactly — no renaming.

---

### Task 1: workbench-common visibility fix, module scaffold, application properties

**Files:**
- Modify: `workbench-common/src/main/java/com/workbench/common/merge/DocumentTypes.java` (package-private → public class + public `humanReadable` method; map stays private)
- Modify: `workbench-common/src/test/java/com/workbench/common/merge/EvidenceReadinessTest.java` (no code change needed — confirms the visibility change doesn't break same-package usage; see Step 2)
- Modify: `case-review-agent/pom.xml` (replace stub with real module POM)
- Create: `case-review-agent/src/main/resources/application.properties`
- Create: `case-review-agent/src/main/resources/application-anthropic.properties`
- Create: `case-review-agent/src/main/resources/application-openai.properties`
- Create: `case-review-agent/src/main/java/com/workbench/caseagent/CaseReviewAgentApplication.java`

**Interfaces:**
- Consumes: parent POM's BOM imports; `workbench-common` (sibling).
- Produces: `com.workbench.common.merge.DocumentTypes.humanReadable(String)` (now public) — consumed by `CaseReviewAgentExecutor` in Task 3. A buildable, runnable module skeleton (no A2A/MCP beans yet — those are Tasks 2–3) that later tasks add to.

- [ ] **Step 1: Make `DocumentTypes` and its lookup method public**

Replace the full contents of `workbench-common/src/main/java/com/workbench/common/merge/DocumentTypes.java`:
```java
package com.workbench.common.merge;

import java.util.Map;

/**
 * Document-type-code to human-readable name mapping (PLATFORM_CONTRACT.md §10/§B3).
 * Public: consumed cross-module by case-review-agent (Session 3) to build
 * human-readable EvidenceItem labels, in addition to EvidenceReadiness in this module.
 */
public final class DocumentTypes {

    private static final Map<String, String> HUMAN_READABLE_NAMES = Map.of(
            "TRANSACTION_RECORD", "Transaction record",
            "MERCHANT_RESPONSE", "Merchant response",
            "CUSTOMER_DECLARATION", "Customer declaration",
            "DELIVERY_DISPUTE_PROOF", "Delivery / non-delivery proof");

    private DocumentTypes() {
    }

    public static String humanReadable(String docType) {
        return HUMAN_READABLE_NAMES.getOrDefault(docType, docType);
    }
}
```

- [ ] **Step 2: Verify the visibility change doesn't break workbench-common**

Run: `mvn -q -pl workbench-common test`
Expected: PASS, all 12 pre-existing tests still green (the class is used identically from the same package by `EvidenceReadiness`; only its accessibility to OTHER packages changed).

- [ ] **Step 3: Replace the case-review-agent stub POM**

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
  <packaging>jar</packaging>

  <!--
    Verified live against Maven Central and the spring-ai-community/spring-ai-a2a
    GitHub repo on 2026-07-15 (per session-3-prompt.md's instruction not to assume
    artifact IDs or APIs from memory):

      org.springaicommunity:spring-ai-a2a-server-autoconfigure:0.3.0

    The A2A Java SDK's real groupId is io.github.a2asdk (not io.a2a, which does not
    exist on Maven Central) — consumed transitively, never declared directly here.

    JACKSON 2 EXCEPTION FOR THIS MODULE: the A2A SDK (a2a-java-sdk-spec,
    a2a-java-sdk-server-common, both io.github.a2asdk:*:0.3.3.Final) depends on
    com.fasterxml.jackson.core:jackson-databind (Jackson 2) at compile scope for
    its OWN internal protocol message serialization — a genuine, unavoidable
    transitive dependency of the third-party A2A ecosystem (verified against the
    real published POMs; no Jackson-3-based A2A SDK exists). This module's own
    code still exclusively uses tools.jackson.core.*/tools.jackson.databind.* and
    com.fasterxml.jackson.annotation.* like every other module — Jackson 2 is
    never imported by our own source, only present transitively via the A2A
    library. The parent's enforce-no-jackson2-core-databind execution is
    disabled below (re-declared with phase=none) for this module only. See
    PLATFORM_CONTRACT.md §2 for the full documented exception.

    No WebFlux, no JPA — this agent reads via MCP (spring-ai-starter-mcp-client,
    httpclient/sync transport, not the -webflux variant), not direct DB access.
  -->

  <dependencies>
    <dependency>
      <groupId>com.workbench</groupId>
      <artifactId>workbench-common</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>org.springaicommunity</groupId>
      <artifactId>spring-ai-a2a-server-autoconfigure</artifactId>
      <version>0.3.0</version>
    </dependency>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-starter-mcp-client</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-starter-model-ollama</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
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
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-enforcer-plugin</artifactId>
        <executions>
          <execution>
            <id>enforce-no-jackson2-core-databind</id>
            <phase>none</phase>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 4: Create the main application properties**

`case-review-agent/src/main/resources/application.properties`:
```properties
server.port=8081
spring.application.name=case-review-agent
workbench.mcp.case-system.url=http://localhost:8083
spring.ai.mcp.client.type=SYNC
spring.ai.mcp.client.streamable-http.connections.case-system.url=${workbench.mcp.case-system.url}
# LLM — Ollama default; override via SPRING_PROFILES_ACTIVE=anthropic|openai
spring.ai.ollama.base-url=http://localhost:11434
spring.ai.ollama.chat.model=qwen2.5:14b
```

- [ ] **Step 5: Create the Anthropic/OpenAI profile stubs**

`case-review-agent/src/main/resources/application-anthropic.properties`:
```properties
# Activate via SPRING_PROFILES_ACTIVE=anthropic. Requires ANTHROPIC_API_KEY env var.
spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}
spring.ai.anthropic.chat.model=claude-sonnet-4-6
```

`case-review-agent/src/main/resources/application-openai.properties`:
```properties
# Activate via SPRING_PROFILES_ACTIVE=openai. Requires OPENAI_API_KEY env var.
spring.ai.openai.api-key=${OPENAI_API_KEY}
spring.ai.openai.chat.model=gpt-4o
```

- [ ] **Step 6: Create the main application class — Agent Card only, no AgentExecutor yet**

`case-review-agent/src/main/java/com/workbench/caseagent/CaseReviewAgentApplication.java`:
```java
package com.workbench.caseagent;

import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

@SpringBootApplication
public class CaseReviewAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CaseReviewAgentApplication.class, args);
    }

    @Bean
    public AgentCard agentCard(@Value("${server.port:8081}") int port) {
        return new AgentCard.Builder()
                .name("case-review-agent")
                .description("Reviews operational case facts: transaction, merchant response, "
                        + "and available evidence documents.")
                .url("http://localhost:" + port + "/a2a")
                .version("1.0.0")
                .capabilities(new AgentCapabilities.Builder().streaming(false).build())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of(new AgentSkill.Builder()
                        .id("case_review")
                        .name("Review case facts")
                        .description("Checks transaction status, merchant response, and available "
                                + "evidence documents for a dispute case")
                        .tags(List.of("case-review"))
                        .examples(List.of("Check transaction, merchant response, case status and "
                                + "available evidence for dispute case D-10291, dispute type "
                                + "GOODS_NOT_RECEIVED."))
                        .build()))
                .protocolVersion("0.3.0")
                .build();
    }

    // @Bean AgentExecutor is added in Task 3, once CaseReviewAgentExecutor exists.
}
```

- [ ] **Step 7: Verify the module compiles**

Run: `mvn -q -pl case-review-agent -am compile`
Expected: PASS, `BUILD SUCCESS`. If dependency resolution fails on `spring-ai-a2a-server-autoconfigure` or its transitive `io.github.a2asdk:*` artifacts, stop and report BLOCKED — these were verified against live Maven Central during planning, so a failure indicates a network/environment issue worth investigating directly, not a wrong artifact choice.

Run: `mvn -q validate` from the repo root.
Expected: PASS (no output) — all 8 reactor modules still resolve correctly, and the enforcer override compiles/parses correctly (does not error on the `<phase>none</phase>` override itself — Maven allows disabling an inherited execution this way).

- [ ] **Step 8: Commit**

```bash
git add workbench-common/src/main/java/com/workbench/common/merge/DocumentTypes.java case-review-agent/pom.xml case-review-agent/src/main/resources case-review-agent/src/main/java/com/workbench/caseagent/CaseReviewAgentApplication.java
git commit -m "Add case-review-agent module scaffold, Agent Card bean, and workbench-common DocumentTypes visibility fix"
```

---

### Task 2: MCP client (CaseMcpClient) + AgentResponse wrapper

**Files:**
- Create: `case-review-agent/src/main/java/com/workbench/caseagent/mcp/CaseMcpClient.java`
- Create: `case-review-agent/src/main/java/com/workbench/caseagent/AgentResponse.java`
- Create: `case-review-agent/src/test/java/com/workbench/caseagent/mcp/CaseMcpClientTest.java`

**Interfaces:**
- Consumes: `io.modelcontextprotocol.client.McpSyncClient` (auto-configured `List<McpSyncClient>` bean from `spring-ai-starter-mcp-client`, per Task 1's `spring.ai.mcp.client.streamable-http.connections.case-system.url` config).
- Produces: `CaseMcpClient.getCase(String caseId)` / `.listCaseDocuments(String caseId)` returning `Map<String, Object>` — consumed by `CaseReviewAgentExecutor` in Task 3. `AgentResponse(CaseReviewResult result, List<String> progressLines)` — the §C5 wrapper, consumed by `CaseReviewAgentExecutor` in Task 3 and documented here as the pattern Session 4 (policy-agent) and Session 5 (orchestrator-agent) must handle identically.

- [ ] **Step 1: Write the failing test for CaseMcpClient**

`case-review-agent/src/test/java/com/workbench/caseagent/mcp/CaseMcpClientTest.java`:
```java
package com.workbench.caseagent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mocks McpSyncClient directly (Java API boundary) rather than WireMock (raw HTTP
 * boundary) — see case-review-agent/pom.xml and PLATFORM_CONTRACT.md Global
 * Constraint #6 in the plan for the reasoning. Fast, isolated, tests CaseMcpClient's
 * own mapping/error-handling contract, not the real MCP wire protocol (that was
 * already proven against real Postgres in Session 2's Testcontainers tests).
 */
class CaseMcpClientTest {

    @Test
    void getCase_mapsResponseCorrectly() {
        McpSyncClient mockClient = mock(McpSyncClient.class);
        Map<String, Object> structured = Map.of("caseId", "D-10291", "amount", 250.00);
        when(mockClient.callTool(any())).thenReturn(
                new McpSchema.CallToolResult(List.of(), false, structured, Map.of()));

        CaseMcpClient client = new CaseMcpClient(List.of(mockClient));
        Map<String, Object> result = client.getCase("D-10291");

        assertEquals("D-10291", result.get("caseId"));
        assertEquals(250.00, result.get("amount"));

        ArgumentCaptor<McpSchema.CallToolRequest> captor = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(mockClient).callTool(captor.capture());
        assertEquals("get_case", captor.getValue().name());
        assertEquals("D-10291", captor.getValue().arguments().get("caseId"));
    }

    @Test
    void listCaseDocuments_mapsDocTypeAndPresentFields() {
        McpSyncClient mockClient = mock(McpSyncClient.class);
        Map<String, Object> structured = Map.of("documents", List.of(
                Map.of("docType", "TRANSACTION_RECORD", "present", true),
                Map.of("docType", "MERCHANT_RESPONSE", "present", true)));
        when(mockClient.callTool(any())).thenReturn(
                new McpSchema.CallToolResult(List.of(), false, structured, Map.of()));

        CaseMcpClient client = new CaseMcpClient(List.of(mockClient));
        Map<String, Object> result = client.listCaseDocuments("D-10291");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents = (List<Map<String, Object>>) result.get("documents");
        assertEquals(2, documents.size());
        assertTrue(documents.get(0).containsKey("docType"));
        assertTrue(documents.get(0).containsKey("present"));
        assertEquals("TRANSACTION_RECORD", documents.get(0).get("docType"));
        assertEquals(true, documents.get(0).get("present"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `mvn -q -pl case-review-agent test`
Expected: FAIL — `cannot find symbol: class CaseMcpClient` (doesn't exist yet). Note: `mockito-core` comes transitively via `spring-boot-starter-test`, no separate dependency needed.

- [ ] **Step 3: Implement CaseMcpClient and AgentResponse**

`case-review-agent/src/main/java/com/workbench/caseagent/mcp/CaseMcpClient.java`:
```java
package com.workbench.caseagent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class CaseMcpClient {

    private final McpSyncClient client;

    public CaseMcpClient(List<McpSyncClient> syncClients) {
        if (syncClients.isEmpty()) {
            throw new IllegalStateException("No MCP sync client configured for case-system-mcp");
        }
        this.client = syncClients.get(0);
    }

    public Map<String, Object> getCase(String caseId) {
        return callTool("get_case", Map.of("caseId", caseId));
    }

    public Map<String, Object> listCaseDocuments(String caseId) {
        return callTool("list_case_documents", Map.of("caseId", caseId));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callTool(String toolName, Map<String, Object> arguments) {
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder(toolName)
                .arguments(arguments)
                .build();
        McpSchema.CallToolResult result = client.callTool(request);
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException("MCP tool call failed: " + toolName);
        }
        Object structured = result.structuredContent();
        if (structured instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalStateException("MCP tool " + toolName + " returned no structured content");
    }
}
```

`case-review-agent/src/main/java/com/workbench/caseagent/AgentResponse.java`:
```java
package com.workbench.caseagent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.workbench.common.a2a.CaseReviewResult;

import java.util.List;

/**
 * The A2A Java SDK has no built-in mechanism to attach both a structured result AND
 * a list of progress lines to a single response (see PLATFORM_CONTRACT.md §8.2's
 * "Progress lines emitted during execution" vs. the structured JSON payload — these
 * are two logically separate things the orchestrator needs, but A2A's Message type
 * carries one text payload). This wrapper is serialized as the single JSON text
 * response; the orchestrator (Session 5) deserializes this same wrapper shape.
 * policy-agent (Session 4) must use this identical pattern (PLATFORM_CONTRACT.md
 * §H forward-note).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentResponse(CaseReviewResult result, List<String> progressLines) {
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -pl case-review-agent test`
Expected: PASS, 2/2 tests green.

- [ ] **Step 5: Commit**

```bash
git add case-review-agent/src/main/java/com/workbench/caseagent/mcp case-review-agent/src/main/java/com/workbench/caseagent/AgentResponse.java case-review-agent/src/test/java/com/workbench/caseagent/mcp
git commit -m "Add CaseMcpClient and AgentResponse wrapper for case-review-agent"
```

---

### Task 3: CaseReviewAgentExecutor (business logic) + AgentExecutor wiring

**Files:**
- Create: `case-review-agent/src/main/java/com/workbench/caseagent/CaseReviewAgentExecutor.java`
- Modify: `case-review-agent/src/main/java/com/workbench/caseagent/CaseReviewAgentApplication.java` (add the `@Bean AgentExecutor` method)

**Interfaces:**
- Consumes: `CaseMcpClient` (Task 2), `AgentResponse` (Task 2), `CaseReviewResult`/`EvidenceItem` (`workbench-common`), `DocumentTypes.humanReadable` (Task 1), `ChatClient`/`ChatClient.Builder` (Spring AI Ollama autoconfiguration).
- Produces: `CaseReviewAgentExecutor.execute(String messageText)` returning the serialized `AgentResponse` JSON string — consumed directly (not via A2A protocol) by Task 4's tests, and wired into the real A2A response path via `DefaultAgentExecutor` in this task's `CaseReviewAgentApplication` update.

- [ ] **Step 1: Implement CaseReviewAgentExecutor**

`case-review-agent/src/main/java/com/workbench/caseagent/CaseReviewAgentExecutor.java`:
```java
package com.workbench.caseagent;

import com.workbench.caseagent.mcp.CaseMcpClient;
import com.workbench.common.a2a.CaseReviewResult;
import com.workbench.common.agui.EvidenceItem;
import com.workbench.common.merge.DocumentTypes;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CaseReviewAgentExecutor {

    private static final Pattern INPUT_PATTERN =
            Pattern.compile("dispute case ([\\w-]+), dispute type (\\w+)");

    private static final Set<String> CUSTOMER_DOC_TYPES =
            Set.of("CUSTOMER_DECLARATION", "DELIVERY_DISPUTE_PROOF");

    private final CaseMcpClient caseMcpClient;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public CaseReviewAgentExecutor(CaseMcpClient caseMcpClient, ChatClient.Builder chatClientBuilder) {
        this.caseMcpClient = caseMcpClient;
        this.chatClient = chatClientBuilder.build();
    }

    public String execute(String messageText) {
        Matcher matcher = INPUT_PATTERN.matcher(messageText == null ? "" : messageText);
        if (!matcher.find()) {
            return errorResponse("UNKNOWN", "Unable to parse dispute case request");
        }
        String caseId = matcher.group(1);

        List<String> progressLines = new ArrayList<>();
        progressLines.add("Checking transaction status...");

        Map<String, Object> caseData;
        try {
            caseData = caseMcpClient.getCase(caseId);
        } catch (RuntimeException e) {
            return errorResponse(caseId, "Case not found: " + caseId);
        }

        String transactionAmount = formatAmount(caseData);
        progressLines.add("Transaction found for " + transactionAmount);
        progressLines.add("Merchant response available");

        Map<String, Object> documentsResponse = caseMcpClient.listCaseDocuments(caseId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents =
                (List<Map<String, Object>>) documentsResponse.getOrDefault("documents", List.of());

        List<EvidenceItem> availableDocuments = new ArrayList<>();
        boolean hasCustomerDocs = false;
        for (Map<String, Object> doc : documents) {
            String docType = (String) doc.get("docType");
            boolean present = Boolean.TRUE.equals(doc.get("present"));
            availableDocuments.add(new EvidenceItem(DocumentTypes.humanReadable(docType), present));
            if (present) {
                progressLines.add("Case file contains " + DocumentTypes.humanReadable(docType).toLowerCase());
            }
            if (CUSTOMER_DOC_TYPES.contains(docType) && present) {
                hasCustomerDocs = true;
            }
        }
        if (!hasCustomerDocs) {
            progressLines.add("No additional customer documents found in case file");
        }

        LlmSummary summary = summarizeMerchantResponse(caseData);

        CaseReviewResult result = new CaseReviewResult(
                caseId,
                true,
                transactionAmount,
                summary.merchantResponse(),
                summary.merchantPosition(),
                availableDocuments,
                (String) caseData.get("caseStatus"));

        return serialize(new AgentResponse(result, progressLines));
    }

    private LlmSummary summarizeMerchantResponse(Map<String, Object> caseData) {
        String caseJson = objectMapper.writeValueAsString(caseData);
        String prompt = "Given this case data: " + caseJson + "\n"
                + "Summarise: 1) Has the merchant responded? (yes/no) 2) What is the merchant's "
                + "position in one sentence?\n"
                + "Respond only with JSON: {\"merchantResponse\": \"...\", \"merchantPosition\": \"...\"}";
        try {
            String content = chatClient.prompt().user(prompt).call().content();
            JsonNode node = objectMapper.readTree(content);
            return new LlmSummary(node.get("merchantResponse").asString(), node.get("merchantPosition").asString());
        } catch (RuntimeException e) {
            return new LlmSummary("unknown", "Unable to determine");
        }
    }

    private static String formatAmount(Map<String, Object> caseData) {
        Object amountObj = caseData.get("amount");
        Object currencyObj = caseData.get("currency");
        String currency = currencyObj == null ? "" : currencyObj.toString();
        if (amountObj instanceof BigDecimal bigDecimal) {
            return (currency + " " + bigDecimal.stripTrailingZeros().toPlainString()).trim();
        }
        return (currency + " " + amountObj).trim();
    }

    private String errorResponse(String caseId, String message) {
        CaseReviewResult errorResult = new CaseReviewResult(
                caseId, false, null, "unknown", message, List.of(), "UNKNOWN");
        return serialize(new AgentResponse(errorResult, List.of(message)));
    }

    private String serialize(AgentResponse response) {
        return objectMapper.writeValueAsString(response);
    }

    private record LlmSummary(String merchantResponse, String merchantPosition) {
    }
}
```

- [ ] **Step 2: Wire AgentExecutor into the application**

Replace the full contents of `case-review-agent/src/main/java/com/workbench/caseagent/CaseReviewAgentApplication.java`:
```java
package com.workbench.caseagent;

import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;
import org.springaicommunity.a2a.server.executor.DefaultAgentExecutor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

@SpringBootApplication
public class CaseReviewAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CaseReviewAgentApplication.class, args);
    }

    @Bean
    public AgentCard agentCard(@Value("${server.port:8081}") int port) {
        return new AgentCard.Builder()
                .name("case-review-agent")
                .description("Reviews operational case facts: transaction, merchant response, "
                        + "and available evidence documents.")
                .url("http://localhost:" + port + "/a2a")
                .version("1.0.0")
                .capabilities(new AgentCapabilities.Builder().streaming(false).build())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of(new AgentSkill.Builder()
                        .id("case_review")
                        .name("Review case facts")
                        .description("Checks transaction status, merchant response, and available "
                                + "evidence documents for a dispute case")
                        .tags(List.of("case-review"))
                        .examples(List.of("Check transaction, merchant response, case status and "
                                + "available evidence for dispute case D-10291, dispute type "
                                + "GOODS_NOT_RECEIVED."))
                        .build()))
                .protocolVersion("0.3.0")
                .build();
    }

    @Bean
    public AgentExecutor agentExecutor(ChatClient.Builder chatClientBuilder,
            CaseReviewAgentExecutor caseReviewAgentExecutor) {
        ChatClient placeholderClient = chatClientBuilder.build();
        return new DefaultAgentExecutor(placeholderClient, (chat, requestContext) -> {
            String userMessage = DefaultAgentExecutor.extractTextFromMessage(requestContext.getMessage());
            return caseReviewAgentExecutor.execute(userMessage);
        });
    }
}
```

Note: `DefaultAgentExecutor` requires a `ChatClient` in its constructor but the lambda's own `chat` parameter is intentionally unused here — `CaseReviewAgentExecutor` builds and owns its own separately-configured `ChatClient` (constructor-injected `ChatClient.Builder`) for the §C4 reasoning step, so the `placeholderClient` here only satisfies `DefaultAgentExecutor`'s constructor requirement.

- [ ] **Step 3: Verify the module compiles**

Run: `mvn -q -pl case-review-agent -am compile`
Expected: PASS, `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add case-review-agent/src/main/java/com/workbench/caseagent/CaseReviewAgentExecutor.java case-review-agent/src/main/java/com/workbench/caseagent/CaseReviewAgentApplication.java
git commit -m "Add CaseReviewAgentExecutor business logic and wire it into DefaultAgentExecutor"
```

---

### Task 4: Tests — CaseReviewAgentExecutorTest + AgentCard smoke test

**Files:**
- Create: `case-review-agent/src/test/java/com/workbench/caseagent/CaseReviewAgentExecutorTest.java`
- Create: `case-review-agent/src/test/java/com/workbench/caseagent/AgentCardSmokeTest.java`

**Interfaces:**
- Consumes: `CaseReviewAgentExecutor` (Task 3), `CaseMcpClient` (mocked), `ChatClient` (mocked via `RETURNS_DEEP_STUBS`), `AgentResponse`/`CaseReviewResult`/`EvidenceItem`.
- Produces: nothing consumed by later tasks — this is the acceptance surface for the module's business logic.

**Why `AgentCardSmokeTest` beyond the session prompt's literal test list:** none of §E's 7 listed tests boot the actual Spring context (they test `CaseReviewAgentExecutor`/`CaseMcpClient` as plain Java objects, matching the WireMock-avoidance reasoning in Global Constraint #6). That means nothing in this session would otherwise verify the `@Bean AgentCard`/`@Bean AgentExecutor` wiring actually works, or that PLATFORM_CONTRACT §8.1's exact JSON is served — a real risk given `AgentCard`'s 18-parameter builder and this session's multiple corrections to the A2A API surface. One `@SpringBootTest` hitting the real `/.well-known/agent-card.json` endpoint closes that gap now instead of leaving it for Session 6's smoke test to discover for the first time.

- [ ] **Step 1: Write the failing tests**

`case-review-agent/src/test/java/com/workbench/caseagent/CaseReviewAgentExecutorTest.java`:
```java
package com.workbench.caseagent;

import com.workbench.caseagent.mcp.CaseMcpClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CaseReviewAgentExecutorTest {

    private static final String DEMO_MESSAGE =
            "Check transaction, merchant response, case status and available evidence "
                    + "for dispute case D-10291, dispute type GOODS_NOT_RECEIVED.";

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private CaseMcpClient mockMcpClient() {
        CaseMcpClient client = mock(CaseMcpClient.class);
        when(client.getCase("D-10291")).thenReturn(Map.of(
                "caseId", "D-10291",
                "disputeText", "I paid SGD 250 for an item, but I never received it. "
                        + "The merchant says the item was delivered, but I disagree.",
                "disputeType", "GOODS_NOT_RECEIVED",
                "caseStatus", "OPEN",
                "amount", new java.math.BigDecimal("250.00"),
                "currency", "SGD"));
        when(client.listCaseDocuments("D-10291")).thenReturn(Map.of("documents", List.of(
                Map.of("docType", "TRANSACTION_RECORD", "present", true),
                Map.of("docType", "MERCHANT_RESPONSE", "present", true))));
        return client;
    }

    private ChatClient.Builder chatClientBuilderReturning(String jsonContent) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn(jsonContent);
        return builder;
    }

    @Test
    void execute_validCase_returnsStructuredResult() {
        String llmJson = "{\"merchantResponse\": \"available\", \"merchantPosition\": \"Item was delivered\"}";
        CaseReviewAgentExecutor executor = new CaseReviewAgentExecutor(mockMcpClient(), chatClientBuilderReturning(llmJson));

        String responseJson = executor.execute(DEMO_MESSAGE);
        JsonNode node = objectMapper.readTree(responseJson);
        JsonNode result = node.get("result");

        assertTrue(result.get("transactionFound").asBoolean());
        assertEquals("SGD 250", result.get("transactionAmount").asString());
        assertEquals(2, result.get("availableDocuments").size());
    }

    @Test
    void execute_validCase_progressLinesPresent() {
        String llmJson = "{\"merchantResponse\": \"available\", \"merchantPosition\": \"Item was delivered\"}";
        CaseReviewAgentExecutor executor = new CaseReviewAgentExecutor(mockMcpClient(), chatClientBuilderReturning(llmJson));

        String responseJson = executor.execute(DEMO_MESSAGE);
        JsonNode progressLines = objectMapper.readTree(responseJson).get("progressLines");

        List<String> lines = new java.util.ArrayList<>();
        progressLines.forEach(n -> lines.add(n.asString()));

        assertTrue(lines.contains("Checking transaction status..."));
        assertTrue(lines.contains("Transaction found for SGD 250"));
        assertTrue(lines.contains("Merchant response available"));
        assertTrue(lines.contains("Case file contains transaction record"));
        assertTrue(lines.contains("Case file contains merchant response"));
        assertTrue(lines.contains("No additional customer documents found in case file"));
    }

    @Test
    void execute_documentsUseHumanReadableLabels() {
        String llmJson = "{\"merchantResponse\": \"available\", \"merchantPosition\": \"Item was delivered\"}";
        CaseReviewAgentExecutor executor = new CaseReviewAgentExecutor(mockMcpClient(), chatClientBuilderReturning(llmJson));

        String responseJson = executor.execute(DEMO_MESSAGE);
        JsonNode documents = objectMapper.readTree(responseJson).get("result").get("availableDocuments");

        List<String> labels = new java.util.ArrayList<>();
        documents.forEach(n -> labels.add(n.get("label").asString()));

        assertFalse(labels.contains("TRANSACTION_RECORD"));
        assertFalse(labels.contains("MERCHANT_RESPONSE"));
        assertTrue(labels.contains("Transaction record"));
        assertTrue(labels.contains("Merchant response"));
    }

    @Test
    void execute_unknownCase_returnsErrorGracefully() {
        CaseMcpClient client = mock(CaseMcpClient.class);
        when(client.getCase("D-UNKNOWN")).thenThrow(new IllegalStateException("Case not found: D-UNKNOWN"));
        String message = "Check transaction, merchant response, case status and available evidence "
                + "for dispute case D-UNKNOWN, dispute type GOODS_NOT_RECEIVED.";

        CaseReviewAgentExecutor executor = new CaseReviewAgentExecutor(client, chatClientBuilderReturning("{}"));

        String responseJson = executor.execute(message);
        JsonNode result = objectMapper.readTree(responseJson).get("result");

        assertFalse(result.get("transactionFound").asBoolean());
        assertTrue(result.get("merchantPosition").asString().contains("D-UNKNOWN"));
    }

    @Test
    void execute_malformedLlmOutput_defaultsGracefully() {
        CaseReviewAgentExecutor executor =
                new CaseReviewAgentExecutor(mockMcpClient(), chatClientBuilderReturning("not valid json"));

        String responseJson = executor.execute(DEMO_MESSAGE);
        JsonNode result = objectMapper.readTree(responseJson).get("result");

        assertEquals("unknown", result.get("merchantResponse").asString());
        assertEquals("Unable to determine", result.get("merchantPosition").asString());
    }
}
```

`case-review-agent/src/test/java/com/workbench/caseagent/AgentCardSmokeTest.java`:

**Note on `TestRestTemplate`:** Boot 4 moved `TestRestTemplate` to a new `org.springframework.boot.resttestclient` package in a separate `spring-boot-resttestclient` artifact, and it now requires an explicit `@AutoConfigureTestRestTemplate` annotation to be auto-injected (verified against the real Boot 4.0.7 jars — this is a genuine breaking change from Boot 3, not a guess). Rather than add a new test dependency and annotation for one simple GET assertion, this test uses a plain `RestTemplate` (already transitively available via `spring-boot-starter-web`, no extra dependency needed) constructed directly in the test.

```java
package com.workbench.caseagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the real Spring context (AgentCard bean + A2A autoconfiguration) and hits the
 * real HTTP endpoint. None of this session's other tests exercise this wiring — see
 * this task's brief for why this is added beyond the session prompt's literal test list.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentCardSmokeTest {

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate = new RestTemplate();

    @Test
    void agentCardEndpoint_returnsContractShape() {
        String body = restTemplate.getForObject(
                "http://localhost:" + port + "/.well-known/agent-card.json", String.class);

        assertTrue(body.contains("\"name\":\"case-review-agent\""));
        assertTrue(body.contains("\"description\":\"Reviews operational case facts"));
        assertTrue(body.contains("\"version\":\"1.0.0\""));
        assertTrue(body.contains("\"streaming\":false"));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail to compile**

Run: `mvn -q -pl case-review-agent test`
Expected: FAIL — compilation errors referencing methods/classes not yet matching (should already compile cleanly from Task 3 actually — if `CaseReviewAgentExecutor`/`CaseMcpClient`/`AgentResponse` already exist correctly, these tests should mostly just need to RUN, not fail to compile. If everything from Tasks 1–3 was applied correctly, expect these tests to compile immediately; run them and treat any RED here as a real signal to investigate, not an artifact of missing scaffolding.)

- [ ] **Step 3: Run the tests to verify they pass**

Run: `mvn -pl case-review-agent test`
Expected: PASS, `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0` (5 `CaseReviewAgentExecutorTest` + 1 `AgentCardSmokeTest`), plus the 2 `CaseMcpClientTest` from Task 2 — module total 8/8. `BUILD SUCCESS`.

If `execute_documentsUseHumanReadableLabels` fails with raw `TRANSACTION_RECORD`/`MERCHANT_RESPONSE` labels instead of human-readable ones, the most likely cause is Task 1's `DocumentTypes` visibility fix not having been applied, or `CaseReviewAgentExecutor` calling `doc.get("docType")` directly as the label instead of routing through `DocumentTypes.humanReadable(...)` — check `CaseReviewAgentExecutor.java` against Task 3's exact code before suspecting anything else.

- [ ] **Step 4: Commit**

```bash
git add case-review-agent/src/test/java/com/workbench/caseagent
git commit -m "Add CaseReviewAgentExecutor tests and an AgentCard smoke test"
```

---

### Task 5: contract-tests addition — CaseReviewResultContractTest

**Files:**
- Modify: `contract-tests/pom.xml` (no new dependencies needed — `workbench-common` is already a test dependency from Session 1)
- Create: `contract-tests/src/test/java/com/workbench/contract/CaseReviewResultContractTest.java`

**Interfaces:**
- Consumes: `CaseReviewResult`/`EvidenceItem` (`workbench-common`, already a test dependency of `contract-tests`).
- Produces: nothing consumed by later tasks — this is the fixture the orchestrator (Session 5) will deserialize against.

- [ ] **Step 1: Confirm no new contract-tests dependencies are needed**

`contract-tests/pom.xml` already depends on `workbench-common` (test scope) from Session 1 — `CaseReviewResult` and `EvidenceItem` live there. No pom.xml change is needed for this task; skip straight to Step 2. (If you find `workbench-common` is somehow not already a dependency, stop and report BLOCKED — that would indicate the repo state doesn't match what this plan assumes.)

- [ ] **Step 2: Write the contract test**

`contract-tests/src/test/java/com/workbench/contract/CaseReviewResultContractTest.java`:
```java
package com.workbench.contract;

import com.workbench.common.a2a.CaseReviewResult;
import com.workbench.common.agui.EvidenceItem;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Constructs a CaseReviewResult matching the demo scenario (PLATFORM_CONTRACT.md §8.2)
 * and asserts the serialized shape the orchestrator (Session 5) will deserialize
 * against — in particular, that EvidenceItem labels are human-readable, not raw
 * docType codes (closes Session 1 hardening's Minor 2, now enforced by
 * case-review-agent's DocumentTypes usage).
 */
class CaseReviewResultContractTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void demoScenarioSerializesWithHumanReadableLabelsAndCamelCaseFields() {
        CaseReviewResult result = new CaseReviewResult(
                "D-10291",
                true,
                "SGD 250",
                "available",
                "Item was delivered",
                List.of(
                        new EvidenceItem("Transaction record", true),
                        new EvidenceItem("Merchant response", true)),
                "OPEN");

        String json = objectMapper.writeValueAsString(result);
        JsonNode node = objectMapper.readTree(json);

        assertEquals("Transaction record", node.get("availableDocuments").get(0).get("label").asString());
        assertFalse("TRANSACTION_RECORD".equals(node.get("availableDocuments").get(0).get("label").asString()));
        assertEquals("Merchant response", node.get("availableDocuments").get(1).get("label").asString());
        assertFalse("MERCHANT_RESPONSE".equals(node.get("availableDocuments").get(1).get("label").asString()));
        assertEquals("SGD 250", node.get("transactionAmount").asString());

        assertTrue(json.contains("\"caseId\":\"D-10291\""));
        assertTrue(json.contains("\"transactionFound\":true"));
        assertTrue(json.contains("\"transactionAmount\":\"SGD 250\""));
        assertTrue(json.contains("\"merchantResponse\":\"available\""));
        assertTrue(json.contains("\"merchantPosition\":\"Item was delivered\""));
        assertTrue(json.contains("\"availableDocuments\":[{\"label\":\"Transaction record\",\"present\":true},"
                + "{\"label\":\"Merchant response\",\"present\":true}]"));
        assertTrue(json.contains("\"caseStatus\":\"OPEN\""));

        CaseReviewResult roundTripped = objectMapper.readValue(json, CaseReviewResult.class);
        assertEquals(result, roundTripped);
    }
}
```

- [ ] **Step 3: Run the test**

Run: `mvn -pl contract-tests test -Dtest=CaseReviewResultContractTest`
Expected: PASS, `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.

- [ ] **Step 4: Run the full contract-tests suite to confirm no regressions**

Run: `mvn -pl contract-tests test`
Expected: `Tests run: 16, Failures: 0, Errors: 0, Skipped: 0` (15 pre-existing + 1 new).

- [ ] **Step 5: Commit**

```bash
git add contract-tests/src/test/java/com/workbench/contract/CaseReviewResultContractTest.java
git commit -m "Add CaseReviewResultContractTest asserting human-readable EvidenceItem labels"
```

---

### Task 6: Final acceptance verification

**Files:** none created — verification only.

**Interfaces:**
- Consumes: everything from Tasks 1–5.
- Produces: nothing new; this is the acceptance gate before Session 4.

- [ ] **Step 1: Run the full reactor test suite**

Run: `mvn install -pl case-system-mcp -am -DskipTests` (ensures `case-system-mcp`'s thin jar is available for `contract-tests`, per Session 2's dependency), then `mvn test` from the repo root.
Expected: `BUILD SUCCESS`. `workbench-common`: 12. `agui-support`: 19. `case-system-mcp`: 7. `case-review-agent`: 8 (2 `CaseMcpClientTest` + 5 `CaseReviewAgentExecutorTest` + 1 `AgentCardSmokeTest`). `contract-tests`: 16. Total: 62. The two remaining stub modules (`policy-agent`, `orchestrator-agent`, `packaging=pom`) report no tests.

- [ ] **Step 2: Verify case-review-agent's test count specifically**

Run: `mvn -pl case-review-agent test`
Expected: `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`, including the human-readable-label assertion (`execute_documentsUseHumanReadableLabels`).

- [ ] **Step 3: Verify contract-tests specifically**

Run: `mvn -pl contract-tests test -Dtest=CaseReviewResultContractTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 4: Jackson import check**

Run:
```bash
grep -r "com\.fasterxml\.jackson\.core\|com\.fasterxml\.jackson\.databind" case-review-agent/src 2>/dev/null
```
Expected: no output (this module's own source never imports Jackson 2 core/databind — the enforcer exemption only covers the A2A SDK's transitive presence on the classpath, not anything in `case-review-agent/src`).

- [ ] **Step 5: Confirm the -parameters flag and A2A artifact comments**

Run: `grep -n -A2 "maven-compiler-plugin" pom.xml | grep -A3 "parameters"`
Expected: shows `<parameters>true</parameters>` in the parent POM (confirmed already present from Session 2 — no change needed this session, just verify).

Run: `grep -n "spring-ai-a2a-server-autoconfigure" case-review-agent/pom.xml`
Expected: matches inside both the dependency declaration and the explanatory comment block (Task 1 Step 3).

- [ ] **Step 6: Show the serialized CaseReviewResult JSON for the demo scenario**

Run: `mvn -pl contract-tests test -Dtest=CaseReviewResultContractTest -Dtest.verbose=true 2>&1 | tail -5` — if this doesn't print the JSON directly (the test doesn't `System.out.println` by default), temporarily add a `System.out.println("DEMO_JSON: " + json);` after the `json` variable is built in `CaseReviewResultContractTest`, rerun with `mvn -pl contract-tests test -Dtest=CaseReviewResultContractTest`, capture the printed line for the acceptance summary, then revert the printout (confirm `git status` shows the file unchanged afterward) before the final commit — same pattern as Session 1's Task 8 Step 5.

- [ ] **Step 7: Final commit if any verification step required a fix**

If Steps 1–6 all passed without needing changes, there is nothing to commit here. If a fix was needed, stage it and commit:
```bash
git add -A
git commit -m "Fix issues found during Session 3 acceptance verification"
```
