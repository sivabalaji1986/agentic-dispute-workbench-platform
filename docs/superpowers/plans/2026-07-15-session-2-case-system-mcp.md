# Session 2 — case-system-mcp Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `case-system-mcp` — a real Spring Boot 4 application exposing the case database as five MCP tools over Streamable HTTP — as the third module in the platform, with JPA entities matching the locked schema, Testcontainers-backed integration tests proving all five tools including idempotency, and a contract-tests safety net catching field-name drift before Session 3.

**Architecture:** `case-system-mcp` is a servlet-based (WebMVC, no WebFlux) Spring Boot app: JPA entities/repositories over PostgreSQL, a single `@Service` exposing five `@Tool`-annotated methods, registered with Spring AI's MCP server via a `ToolCallbackProvider` bean. `infra/seed/schema.sql`/`seed-data.sql` are created now (ahead of Session 6) since this session's tests need them as the schema source of truth — Session 6 will reuse these exact files for `docker-compose.yml`. `contract-tests` gains a new test class that boots the real app context and asserts tool output field names, reusing `case-system-mcp` as a test dependency (not copying its types).

**Tech Stack:** Java 25, Spring Boot 4.0.7 (WebMVC/servlet), Spring AI 2.0.0 MCP server, Spring Data JPA, PostgreSQL, Testcontainers 2.0.5, JUnit 5.

**Why TDD looks different in this session:** unlike Session 1's pure-Java records, JPA entity mappings and MCP tool registration have no meaningful behavior to assert without a live Postgres instance — a compile-time "RED" (method doesn't exist) proves nothing about whether an `@Column` mapping or a derived query actually works. Tasks 1–3 build the components (verified by `mvn compile`); Task 4 is where real TDD happens — the Testcontainers test is the first point anything in this module gets proven correct against a real database, and it is written and run for real (not skipped) since Docker is confirmed available in this environment.

## Global Constraints

**Verified findings — do not deviate from these; the session prompt's example code contains inaccuracies that would not compile or would silently misconfigure the server. All verified live against Maven Central and the Spring AI 2.0.0 reference docs on 2026-07-15:**

1. **Spring AI MCP server starter:** `org.springframework.ai:spring-ai-starter-mcp-server-webmvc` (version omitted — managed by the parent's `spring-ai-bom` import, resolves to 2.0.0). Confirmed as a real published jar on Maven Central. Provides `McpServerStreamableHttpWebMvcAutoConfiguration`, which auto-configures `WebMvcStreamableServerTransportProvider` for Streamable HTTP transport — this is the correct starter for a servlet-based (non-WebFlux) app using Streamable HTTP, matching PLATFORM_CONTRACT §9.

2. **Tool registration — the session prompt's "expose McpSyncServerExchange" instruction is wrong for Spring AI 2.0.** Decompiling the real `spring-ai-autoconfigure-mcp-server-common:2.0.0` jar shows `ToolCallbackConverterAutoConfiguration` automatically collects any `ToolCallbackProvider` (or `List<ToolCallback>`) bean from the Spring context and converts it to the `List<McpServerFeatures.SyncToolSpecification>` that `McpServerAutoConfiguration`'s `McpSyncServer` bean consumes. No manual `McpSyncServerExchange` bean is needed or correct. The right pattern is:
   ```java
   @Bean
   public ToolCallbackProvider caseSystemToolCallbackProvider(CaseSystemTools tools) {
       return MethodToolCallbackProvider.builder().toolObjects(tools).build();
   }
   ```
   (`org.springframework.ai.tool.method.MethodToolCallbackProvider`, confirmed via decompilation: `public static Builder builder()`, `Builder.toolObjects(Object...)`, `Builder.build()`.)

3. **`@ToolParam` has no `name` attribute in Spring AI 2.0 — the session prompt's example code (`@ToolParam(name = "caseId", ...)`) will not compile.** Decompiling `spring-ai-model:2.0.0`'s `org.springframework.ai.tool.annotation.ToolParam` shows only two attributes: `required()` and `description()`. Tool method parameter names in the generated JSON schema come from real Java reflection over `Method`/`Parameter` (`JsonSchemaGenerator.generateForMethodInput(Method, ...)`), which requires the `-parameters` javac flag to preserve actual parameter names instead of `arg0`/`arg1`. **The parent POM currently does not set this flag — Task 1 adds `<parameters>true</parameters>` to `maven-compiler-plugin`'s configuration.** Use `@ToolParam(description = "...")` only, never `name`.

4. **The session prompt's example property `spring.ai.mcp.server.transport=STREAMABLE_HTTP` does not exist.** Decompiling `McpServerProperties` shows the real property is `spring.ai.mcp.server.protocol`, an enum `McpServerProperties.ServerProtocol` with exactly three values: `SSE`, `STREAMABLE`, `STATELESS`. Use `spring.ai.mcp.server.protocol=STREAMABLE`.

5. **Testcontainers is at 2.0.5 (BOM-managed via the parent's `testcontainers-bom` import), and Testcontainers 2.x renamed its module artifacts** — the session prompt's "Testcontainers: postgresql + junit-jupiter" (Testcontainers 1.x naming) does not match what's actually on the classpath. Verified real artifacts: `org.testcontainers:testcontainers-postgresql` (not `postgresql`) and `org.testcontainers:testcontainers-junit-jupiter` (not `junit-jupiter`). The new canonical container class is `org.testcontainers.postgresql.PostgreSQLContainer` (non-generic; the old generic `org.testcontainers.containers.PostgreSQLContainer<SELF>` still exists as a compatibility shim — use the new one). `withInitScript(String)`/`withInitScripts(String...)` live in the separate `org.testcontainers:testcontainers-jdbc` artifact, pulled in transitively by `testcontainers-postgresql` — no explicit dependency needed. `DockerImageName.parse(String).asCompatibleSubstituteFor(String)` confirmed for using the `pgvector/pgvector:pg16` image (a custom fork, not the official `postgres` image) with `PostgreSQLContainer`'s built-in image-family safety check.

6. **`spring-boot-testcontainers:4.0.7`'s `@ServiceConnection` is at `org.springframework.boot.testcontainers.service.connection.ServiceConnection`** — confirmed present in the real jar. Use it on the `@Container` field to auto-wire the datasource from the running container; do not hand-write `spring.datasource.url` overrides in test config.

7. **Hibernate 7.2.19.Final ships with Boot 4.0.7.** `TaskEntity.missingItems` (Postgres `text[]`) uses the standard Hibernate 6+ explicit array mapping: `@JdbcTypeCode(SqlTypes.ARRAY)` (from `org.hibernate.annotations.JdbcTypeCode` / `org.hibernate.type.SqlTypes`) alongside `@Column(columnDefinition = "text[]")` — the session prompt's bare `@Column(columnDefinition="text[]")` alone is the older/riskier pattern; add the explicit `@JdbcTypeCode` for reliability.

8. **`infra/seed/schema.sql` and `infra/seed/seed-data.sql` do not exist yet** (the `infra/` directory hasn't been created — that's nominally Session 6's job per PLATFORM_CONTRACT §15). This session's own tests need them now, and the session prompt's own §G explicitly anticipates this ("the `infra/seed/schema.sql` used for Testcontainers init scripts in this session must be the single schema source of truth. Verify in Session 6 that `docker-compose.yml` mounts the same file") — confirming Session 2 is expected to create them. Task 1 creates both files verbatim from PLATFORM_CONTRACT §4.

9. **Resolving the session prompt's internal contradiction on `ddl-auto` for tests:** §D says use `ddl-auto=create-drop` for tests; §E says "Do NOT use `ddl-auto=create-drop`... the schema SQL file is the single source of truth." These conflict. Resolution: use `ddl-auto=validate` in both `application.properties` and `application-test.properties` — Hibernate validates entity mappings against the schema loaded from `schema.sql`/`seed-data.sql` via Testcontainers `withInitScripts(...)`, exactly matching production behavior. This is what §E's explicit prohibition requires, and it also means test behavior matches production `ddl-auto=validate` instead of diverging from it.

10. **`case-system-mcp`'s `spring-boot-maven-plugin` needs a `<classifier>` on its `repackage` execution.** Without one, `repackage` replaces the module's published jar with the fat executable jar, whose dependencies are nested under `BOOT-INF/lib/` and are NOT resolvable as normal Maven transitive dependencies — this would break `contract-tests` (Task 5) depending on `case-system-mcp` as a library to reuse `CaseSystemTools` directly. Task 1 configures `<classifier>exec</classifier>` so the thin, normal library jar remains the default artifact and the executable jar is separately classified.

- No `com.fasterxml.jackson.core`/`.databind` imports in `case-system-mcp/src` (annotation imports under `com.fasterxml.jackson.annotation` don't apply here — this module doesn't touch Jackson directly at all; MCP tool serialization is handled entirely by Spring AI's own `tools.jackson.databind.json.JsonMapper` internally).
- Tool method names and `@Tool`/parameter names must match PLATFORM_CONTRACT §9 exactly — the frontend's contract fixtures assert on these.
- `create_task` idempotency (§9, §6.4) is the single most important behavior in this module — verified explicitly via a DB row-count query, not just a returned-value comparison.

---

### Task 1: infra seed SQL, parent POM fix, module scaffold

**Files:**
- Create: `infra/seed/schema.sql`
- Create: `infra/seed/seed-data.sql`
- Modify: `pom.xml` (add `<parameters>true</parameters>` to maven-compiler-plugin config)
- Modify: `case-system-mcp/pom.xml` (replace stub with real module POM)
- Create: `case-system-mcp/src/main/resources/application.properties`
- Create: `case-system-mcp/src/test/resources/application-test.properties`
- Create: `case-system-mcp/src/main/java/com/workbench/mcp/CaseSystemMcpApplication.java`

**Interfaces:**
- Consumes: parent POM's BOM imports (Spring Boot 4.0.7, Spring AI 2.0.0, testcontainers-bom 2.0.5 transitively).
- Produces: a buildable, runnable Spring Boot app skeleton (no entities/tools yet — those are Tasks 2–3) that later tasks add to. `CaseSystemMcpApplication` will gain a `@Bean ToolCallbackProvider` method in Task 3 once `CaseSystemTools` exists.

- [ ] **Step 1: Create the infra seed schema, verbatim from PLATFORM_CONTRACT §4**

`infra/seed/schema.sql`:
```sql
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
    -- does not check unique constraints, so this is additive and safe. Added during
    -- final whole-branch review (see progress ledger) — this session authors the
    -- canonical schema Session 6 freezes for docker-compose, so it belongs here.
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
```

`infra/seed/seed-data.sql`:
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

- [ ] **Step 2: Add the `-parameters` compiler flag to the parent POM**

In `pom.xml`, find the `maven-compiler-plugin` entry inside `<pluginManagement>` and add `<parameters>true</parameters>`:

```xml
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-compiler-plugin</artifactId>
          <version>3.15.0</version>
          <configuration>
            <release>25</release>
            <parameters>true</parameters>
          </configuration>
        </plugin>
```

This is required for Spring AI's `@Tool` method parameter names to be correctly reflected (see Global Constraint #3) — every module benefits from this, not just `case-system-mcp`, so it belongs in the parent's shared configuration.

- [ ] **Step 3: Verify the flag doesn't break the existing reactor**

Run: `mvn -q test`
Expected: PASS — `Tests run` totals unchanged from before (12 workbench-common + 19 agui-support + 11 contract-tests = 42), since `-parameters` only adds debug metadata to bytecode and does not change runtime behavior for existing modules.

- [ ] **Step 4: Replace the case-system-mcp stub POM**

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
  <packaging>jar</packaging>

  <!--
    Spring AI MCP server artifact verified live against Maven Central and the
    Spring AI 2.0.0 reference docs on 2026-07-15 (per session-2-prompt.md's
    instruction not to assume the artifact ID from memory):

      org.springframework.ai:spring-ai-starter-mcp-server-webmvc:2.0.0

    This is the WebMVC (servlet) Streamable HTTP transport starter — confirmed
    via its auto-configuration class McpServerStreamableHttpWebMvcAutoConfiguration,
    which registers WebMvcStreamableServerTransportProvider. No WebFlux.

    Tools are registered by exposing a `ToolCallbackProvider` bean built with
    MethodToolCallbackProvider.builder().toolObjects(...) (see
    CaseSystemMcpApplication) — Spring AI's own ToolCallbackConverterAutoConfiguration
    + McpServerAutoConfiguration pick this up automatically. No manual
    McpSyncServerExchange bean is needed or correct for Spring AI 2.0.

    Testcontainers is BOM-managed at 2.0.5, which renamed its module artifacts:
    org.testcontainers:testcontainers-postgresql (not "postgresql") and
    org.testcontainers:testcontainers-junit-jupiter (not "junit-jupiter").
  -->

  <dependencies>
    <dependency>
      <groupId>com.workbench</groupId>
      <artifactId>workbench-common</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-testcontainers</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>testcontainers-postgresql</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>testcontainers-junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <testResources>
      <testResource>
        <directory>${project.basedir}/src/test/resources</directory>
      </testResource>
      <testResource>
        <!-- infra/seed is the single schema source of truth (Session 6 reuses
             these same files for docker-compose) — Maven copies them onto the
             test classpath at build time rather than duplicating them here. -->
        <directory>${project.basedir}/../infra/seed</directory>
        <includes>
          <include>schema.sql</include>
          <include>seed-data.sql</include>
        </includes>
      </testResource>
    </testResources>
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
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <executions>
          <execution>
            <goals>
              <goal>repackage</goal>
            </goals>
            <configuration>
              <!-- Without a classifier, repackage replaces this module's published
                   jar with the fat executable jar, whose dependencies are nested
                   under BOOT-INF/lib/ and are not resolvable as normal Maven
                   transitive dependencies. contract-tests (Task 5) depends on this
                   module as a library to reuse CaseSystemTools directly, so the
                   thin jar must remain the default artifact. -->
              <classifier>exec</classifier>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 5: Create the main application properties**

`case-system-mcp/src/main/resources/application.properties`:
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
spring.ai.mcp.server.name=case-system-mcp
spring.ai.mcp.server.version=1.0.0
spring.ai.mcp.server.protocol=STREAMABLE
```

- [ ] **Step 6: Create the test properties**

`case-system-mcp/src/test/resources/application-test.properties`:
```properties
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.properties.hibernate.default_schema=workbench
spring.jpa.open-in-view=false
spring.ai.mcp.server.name=case-system-mcp
spring.ai.mcp.server.version=1.0.0
spring.ai.mcp.server.protocol=STREAMABLE
```

`ddl-auto=validate` (not `create-drop`) per Global Constraint #9 — the schema comes from `schema.sql`/`seed-data.sql` via Testcontainers `withInitScripts(...)` in Task 4's test, matching production behavior exactly. No `spring.datasource.*` here — `@ServiceConnection` on the Testcontainers `@Container` field (Task 4) auto-wires the datasource from the running container, overriding `application.properties`' hardcoded values for the test context.

- [ ] **Step 7: Create the main application class with no tools yet**

`case-system-mcp/src/main/java/com/workbench/mcp/CaseSystemMcpApplication.java`:
```java
package com.workbench.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CaseSystemMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(CaseSystemMcpApplication.class, args);
    }
}
```

(The `@Bean ToolCallbackProvider` method is added in Task 3, once `CaseSystemTools` exists to reference.)

- [ ] **Step 8: Verify the module compiles and the reactor still resolves**

Run: `mvn -q -pl case-system-mcp -am compile`
Expected: PASS, `BUILD SUCCESS` — `case-system-mcp` compiles against `workbench-common` and all Spring/Spring AI dependencies resolve.

Run: `mvn -q validate`
Expected: PASS (no output) — all 7 reactor modules still resolve correctly.

- [ ] **Step 9: Commit**

```bash
git add infra/seed/schema.sql infra/seed/seed-data.sql pom.xml case-system-mcp/pom.xml case-system-mcp/src/main/resources/application.properties case-system-mcp/src/test/resources/application-test.properties case-system-mcp/src/main/java/com/workbench/mcp/CaseSystemMcpApplication.java
git commit -m "Add case-system-mcp module scaffold, infra seed SQL, and -parameters compiler flag"
```

---

### Task 2: JPA entities + repositories

**Files:**
- Create: `case-system-mcp/src/main/java/com/workbench/mcp/entity/CaseEntity.java`
- Create: `case-system-mcp/src/main/java/com/workbench/mcp/entity/TransactionEntity.java`
- Create: `case-system-mcp/src/main/java/com/workbench/mcp/entity/EvidenceDocumentEntity.java`
- Create: `case-system-mcp/src/main/java/com/workbench/mcp/entity/TaskEntity.java`
- Create: `case-system-mcp/src/main/java/com/workbench/mcp/entity/AuditEntryEntity.java`
- Create: `case-system-mcp/src/main/java/com/workbench/mcp/repository/CaseRepository.java`
- Create: `case-system-mcp/src/main/java/com/workbench/mcp/repository/EvidenceDocumentRepository.java`
- Create: `case-system-mcp/src/main/java/com/workbench/mcp/repository/TaskRepository.java`
- Create: `case-system-mcp/src/main/java/com/workbench/mcp/repository/AuditEntryRepository.java`

**Interfaces:**
- Consumes: nothing new (plain JPA over the schema from Task 1).
- Produces: `CaseRepository extends JpaRepository<CaseEntity, String>`; `EvidenceDocumentRepository extends JpaRepository<EvidenceDocumentEntity, Integer>` with `List<EvidenceDocumentEntity> findByCaseId(String)`; `TaskRepository` with `Optional<TaskEntity> findByCaseIdAndTaskType(String, String)`; `AuditEntryRepository extends JpaRepository<AuditEntryEntity, Integer>` — all consumed by `CaseSystemTools` in Task 3. (`docId`/`entryId` are `Integer`, not `Long` — Postgres `SERIAL` is a 4-byte `INTEGER`, confirmed by an empirical Hibernate `ddl-auto=validate` failure against a real Postgres instance during Task 2's review; `Long`/`BIGINT` would only be correct for `BIGSERIAL`.) `TransactionEntity` has no repository this session (not used by any of the five tools; exists so Hibernate's `ddl-auto=validate` can validate the full schema, and so it's available for later sessions).

- [ ] **Step 1: Create the five entities**

`case-system-mcp/src/main/java/com/workbench/mcp/entity/CaseEntity.java`:
```java
package com.workbench.mcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "cases")
public class CaseEntity {

    @Id
    @Column(name = "case_id")
    private String caseId;

    @Column(name = "dispute_text", nullable = false)
    private String disputeText;

    @Column(name = "dispute_type")
    private String disputeType;

    @Column(name = "case_status", nullable = false)
    private String caseStatus;

    @Column(name = "amount")
    private BigDecimal amount;

    @Column(name = "currency")
    private String currency;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public String getDisputeText() {
        return disputeText;
    }

    public void setDisputeText(String disputeText) {
        this.disputeText = disputeText;
    }

    public String getDisputeType() {
        return disputeType;
    }

    public void setDisputeType(String disputeType) {
        this.disputeType = disputeType;
    }

    public String getCaseStatus() {
        return caseStatus;
    }

    public void setCaseStatus(String caseStatus) {
        this.caseStatus = caseStatus;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
```

`case-system-mcp/src/main/java/com/workbench/mcp/entity/TransactionEntity.java`:
```java
package com.workbench.mcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "transactions")
public class TransactionEntity {

    @Id
    @Column(name = "txn_id")
    private String txnId;

    @Column(name = "case_id")
    private String caseId;

    @Column(name = "amount")
    private BigDecimal amount;

    @Column(name = "currency")
    private String currency;

    @Column(name = "merchant_name")
    private String merchantName;

    @Column(name = "txn_date")
    private LocalDate txnDate;

    @Column(name = "merchant_position")
    private String merchantPosition;

    public String getTxnId() {
        return txnId;
    }

    public void setTxnId(String txnId) {
        this.txnId = txnId;
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    public LocalDate getTxnDate() {
        return txnDate;
    }

    public void setTxnDate(LocalDate txnDate) {
        this.txnDate = txnDate;
    }

    public String getMerchantPosition() {
        return merchantPosition;
    }

    public void setMerchantPosition(String merchantPosition) {
        this.merchantPosition = merchantPosition;
    }
}
```

`case-system-mcp/src/main/java/com/workbench/mcp/entity/EvidenceDocumentEntity.java`:
```java
package com.workbench.mcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "evidence_documents")
public class EvidenceDocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "doc_id")
    private Integer docId;

    @Column(name = "case_id")
    private String caseId;

    @Column(name = "doc_type", nullable = false)
    private String docType;

    @Column(name = "present", nullable = false)
    private Boolean present;

    @Column(name = "uploaded_at")
    private OffsetDateTime uploadedAt;

    public Integer getDocId() {
        return docId;
    }

    public void setDocId(Integer docId) {
        this.docId = docId;
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public String getDocType() {
        return docType;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public Boolean getPresent() {
        return present;
    }

    public void setPresent(Boolean present) {
        this.present = present;
    }

    public OffsetDateTime getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(OffsetDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }
}
```

`case-system-mcp/src/main/java/com/workbench/mcp/entity/TaskEntity.java`:
```java
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
```

`case-system-mcp/src/main/java/com/workbench/mcp/entity/AuditEntryEntity.java`:
```java
package com.workbench.mcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "audit_entries")
public class AuditEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "entry_id")
    private Integer entryId;

    @Column(name = "case_id")
    private String caseId;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "performed_by")
    private String performedBy;

    @Column(name = "performed_at")
    private OffsetDateTime performedAt;

    public Integer getEntryId() {
        return entryId;
    }

    public void setEntryId(Integer entryId) {
        this.entryId = entryId;
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getPerformedBy() {
        return performedBy;
    }

    public void setPerformedBy(String performedBy) {
        this.performedBy = performedBy;
    }

    public OffsetDateTime getPerformedAt() {
        return performedAt;
    }

    public void setPerformedAt(OffsetDateTime performedAt) {
        this.performedAt = performedAt;
    }
}
```

- [ ] **Step 2: Create the four repositories**

`case-system-mcp/src/main/java/com/workbench/mcp/repository/CaseRepository.java`:
```java
package com.workbench.mcp.repository;

import com.workbench.mcp.entity.CaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseRepository extends JpaRepository<CaseEntity, String> {
}
```

`case-system-mcp/src/main/java/com/workbench/mcp/repository/EvidenceDocumentRepository.java`:
```java
package com.workbench.mcp.repository;

import com.workbench.mcp.entity.EvidenceDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvidenceDocumentRepository extends JpaRepository<EvidenceDocumentEntity, Integer> {

    List<EvidenceDocumentEntity> findByCaseId(String caseId);
}
```

`case-system-mcp/src/main/java/com/workbench/mcp/repository/TaskRepository.java`:
```java
package com.workbench.mcp.repository;

import com.workbench.mcp.entity.TaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TaskRepository extends JpaRepository<TaskEntity, String> {

    Optional<TaskEntity> findByCaseIdAndTaskType(String caseId, String taskType);
}
```

`case-system-mcp/src/main/java/com/workbench/mcp/repository/AuditEntryRepository.java`:
```java
package com.workbench.mcp.repository;

import com.workbench.mcp.entity.AuditEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEntryRepository extends JpaRepository<AuditEntryEntity, Integer> {
}
```

- [ ] **Step 3: Verify the module still compiles**

Run: `mvn -q -pl case-system-mcp -am compile`
Expected: PASS, `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add case-system-mcp/src/main/java/com/workbench/mcp/entity case-system-mcp/src/main/java/com/workbench/mcp/repository
git commit -m "Add case-system-mcp JPA entities and repositories matching PLATFORM_CONTRACT §4"
```

---

### Task 3: MCP tool service + registration

**Files:**
- Create: `case-system-mcp/src/main/java/com/workbench/mcp/tools/CaseSystemTools.java`
- Modify: `case-system-mcp/src/main/java/com/workbench/mcp/CaseSystemMcpApplication.java` (add the `ToolCallbackProvider` bean)

**Interfaces:**
- Consumes: `CaseRepository`, `EvidenceDocumentRepository`, `TaskRepository`, `AuditEntryRepository` (Task 2); `CaseEntity`, `EvidenceDocumentEntity`, `TaskEntity`, `AuditEntryEntity` (Task 2).
- Produces: `@Service CaseSystemTools` with five `@Tool`-annotated methods (`getCase`, `listCaseDocuments`, `createTask`, `updateCaseStatus`, `createAuditEntry`) matching PLATFORM_CONTRACT §9 method/parameter names exactly — consumed directly (not over HTTP) by Task 4's Testcontainers test and Task 5's contract test, and eventually by the real MCP protocol layer once `case-review-agent`/`orchestrator-agent` connect as clients in later sessions.

- [ ] **Step 1: Implement `CaseSystemTools`**

`case-system-mcp/src/main/java/com/workbench/mcp/tools/CaseSystemTools.java`:
```java
package com.workbench.mcp.tools;

import com.workbench.mcp.entity.AuditEntryEntity;
import com.workbench.mcp.entity.CaseEntity;
import com.workbench.mcp.entity.EvidenceDocumentEntity;
import com.workbench.mcp.entity.TaskEntity;
import com.workbench.mcp.repository.AuditEntryRepository;
import com.workbench.mcp.repository.CaseRepository;
import com.workbench.mcp.repository.EvidenceDocumentRepository;
import com.workbench.mcp.repository.TaskRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class CaseSystemTools {

    private final CaseRepository caseRepository;
    private final EvidenceDocumentRepository evidenceDocumentRepository;
    private final TaskRepository taskRepository;
    private final AuditEntryRepository auditEntryRepository;

    public CaseSystemTools(CaseRepository caseRepository,
            EvidenceDocumentRepository evidenceDocumentRepository,
            TaskRepository taskRepository,
            AuditEntryRepository auditEntryRepository) {
        this.caseRepository = caseRepository;
        this.evidenceDocumentRepository = evidenceDocumentRepository;
        this.taskRepository = taskRepository;
        this.auditEntryRepository = auditEntryRepository;
    }

    @Tool(name = "get_case", description = "Retrieve full case details by case ID")
    public Map<String, Object> getCase(
            @ToolParam(description = "The case identifier") String caseId) {
        CaseEntity caseEntity = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseEntity.getCaseId());
        result.put("disputeText", caseEntity.getDisputeText());
        result.put("disputeType", caseEntity.getDisputeType());
        result.put("caseStatus", caseEntity.getCaseStatus());
        result.put("amount", caseEntity.getAmount());
        result.put("currency", caseEntity.getCurrency());
        result.put("createdAt", caseEntity.getCreatedAt());
        result.put("updatedAt", caseEntity.getUpdatedAt());
        return result;
    }

    @Tool(name = "list_case_documents", description = "List all evidence documents for a case")
    public Map<String, Object> listCaseDocuments(
            @ToolParam(description = "The case identifier") String caseId) {
        List<Map<String, Object>> documents = new ArrayList<>();
        for (EvidenceDocumentEntity doc : evidenceDocumentRepository.findByCaseId(caseId)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("docType", doc.getDocType());
            entry.put("present", doc.getPresent());
            documents.add(entry);
        }
        return Map.of("documents", documents);
    }

    @Tool(name = "create_task", description = "Create a missing-evidence request task for a case")
    @Transactional
    public Map<String, Object> createTask(
            @ToolParam(description = "The case identifier") String caseId,
            @ToolParam(description = "The task type, e.g. MISSING_EVIDENCE_REQUEST") String taskType,
            @ToolParam(description = "The document types missing from the case file") List<String> missingItems,
            @ToolParam(description = "The queue this task should be assigned to") String assignedQueue) {
        TaskEntity existing = taskRepository.findByCaseIdAndTaskType(caseId, taskType).orElse(null);
        if (existing != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", existing.getTaskId());
            result.put("createdAt", existing.getCreatedAt());
            return result;
        }

        TaskEntity task = new TaskEntity();
        task.setTaskId(generateTaskId());
        task.setCaseId(caseId);
        task.setTaskType(taskType);
        task.setMissingItems(missingItems.toArray(new String[0]));
        task.setAssignedQueue(assignedQueue);
        task.setCreatedAt(OffsetDateTime.now());
        taskRepository.save(task);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.getTaskId());
        result.put("createdAt", task.getCreatedAt());
        return result;
    }

    @Tool(name = "update_case_status", description = "Update the status of a case")
    @Transactional
    public Map<String, Object> updateCaseStatus(
            @ToolParam(description = "The case identifier") String caseId,
            @ToolParam(description = "The new case status") String newStatus) {
        CaseEntity caseEntity = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));
        caseEntity.setCaseStatus(newStatus);
        caseEntity.setUpdatedAt(OffsetDateTime.now());
        caseRepository.save(caseEntity);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseEntity.getCaseId());
        result.put("status", caseEntity.getCaseStatus());
        result.put("updatedAt", caseEntity.getUpdatedAt());
        return result;
    }

    @Tool(name = "create_audit_entry", description = "Create an audit log entry for a case action")
    @Transactional
    public Map<String, Object> createAuditEntry(
            @ToolParam(description = "The case identifier") String caseId,
            @ToolParam(description = "The action performed") String action,
            @ToolParam(description = "Who performed the action") String performedBy) {
        AuditEntryEntity entry = new AuditEntryEntity();
        entry.setCaseId(caseId);
        entry.setAction(action);
        entry.setPerformedBy(performedBy);
        entry.setPerformedAt(OffsetDateTime.now());
        auditEntryRepository.save(entry);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entryId", entry.getEntryId());
        result.put("createdAt", entry.getPerformedAt());
        return result;
    }

    private static String generateTaskId() {
        return "EVID-" + String.format("%05d", ThreadLocalRandom.current().nextInt(100_000));
    }
}
```

- [ ] **Step 2: Register the tool callback provider bean**

Replace the full contents of `case-system-mcp/src/main/java/com/workbench/mcp/CaseSystemMcpApplication.java`:
```java
package com.workbench.mcp;

import com.workbench.mcp.tools.CaseSystemTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class CaseSystemMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(CaseSystemMcpApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider caseSystemToolCallbackProvider(CaseSystemTools caseSystemTools) {
        return MethodToolCallbackProvider.builder().toolObjects(caseSystemTools).build();
    }
}
```

- [ ] **Step 3: Verify the module still compiles**

Run: `mvn -q -pl case-system-mcp -am compile`
Expected: PASS, `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add case-system-mcp/src/main/java/com/workbench/mcp/tools case-system-mcp/src/main/java/com/workbench/mcp/CaseSystemMcpApplication.java
git commit -m "Add CaseSystemTools with the five PLATFORM_CONTRACT §9 MCP tools and register with the MCP server"
```

---

### Task 4: Testcontainers integration test (real Postgres, real verification)

**Files:**
- Create: `case-system-mcp/src/test/java/com/workbench/mcp/test/CaseSystemMcpToolsTest.java`

**Interfaces:**
- Consumes: `CaseSystemTools`, `TaskRepository` (Task 2/3), Testcontainers `PostgreSQLContainer`, `@ServiceConnection`.
- Produces: nothing consumed by later tasks — this is the acceptance surface for the whole module's business logic, and the first point anything in `case-system-mcp` is proven correct against a real database.

Docker is confirmed running in this environment — these tests execute for real, not skipped.

- [ ] **Step 1: Write the test class**

`case-system-mcp/src/test/java/com/workbench/mcp/test/CaseSystemMcpToolsTest.java`:
```java
package com.workbench.mcp.test;

import com.workbench.mcp.repository.TaskRepository;
import com.workbench.mcp.tools.CaseSystemTools;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers
class CaseSystemMcpToolsTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("workbench")
            .withUsername("workbench")
            .withPassword("workbench")
            .withInitScripts("schema.sql", "seed-data.sql");

    @Autowired
    private CaseSystemTools caseSystemTools;

    @Autowired
    private TaskRepository taskRepository;

    @Test
    void getCase_existingCase_returnsAllFields() {
        Map<String, Object> result = caseSystemTools.getCase("D-10291");

        assertEquals("D-10291", result.get("caseId"));
        assertNotNull(result.get("disputeType"));
        assertNotNull(result.get("amount"));
        assertNotNull(result.get("currency"));
    }

    @Test
    void getCase_unknownCase_throwsDescriptiveException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> caseSystemTools.getCase("D-UNKNOWN"));
        assertTrue(ex.getMessage().contains("D-UNKNOWN"));
    }

    @Test
    void listCaseDocuments_returnsDocumentsWithCamelCaseFields() {
        Map<String, Object> result = caseSystemTools.listCaseDocuments("D-10291");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents = (List<Map<String, Object>>) result.get("documents");
        assertEquals(2, documents.size());
        assertTrue(documents.get(0).containsKey("docType"));
        assertTrue(documents.get(0).containsKey("present"));
    }

    @Test
    void createTask_newTask_insertsAndReturns() {
        Map<String, Object> result = caseSystemTools.createTask(
                "D-10291", "MISSING_EVIDENCE_REQUEST",
                List.of("CUSTOMER_DECLARATION", "DELIVERY_DISPUTE_PROOF"), "Dispute Operations");

        assertTrue(((String) result.get("taskId")).startsWith("EVID-"));
        assertNotNull(result.get("createdAt"));
    }

    @Test
    void createTask_duplicate_returnsExistingWithoutInsert() {
        Map<String, Object> first = caseSystemTools.createTask(
                "D-10291", "DUPLICATE_CHECK_TASK",
                List.of("CUSTOMER_DECLARATION"), "Dispute Operations");
        Map<String, Object> second = caseSystemTools.createTask(
                "D-10291", "DUPLICATE_CHECK_TASK",
                List.of("CUSTOMER_DECLARATION"), "Dispute Operations");

        assertEquals(first.get("taskId"), second.get("taskId"));

        long rowCount = taskRepository.findAll().stream()
                .filter(t -> "D-10291".equals(t.getCaseId()) && "DUPLICATE_CHECK_TASK".equals(t.getTaskType()))
                .count();
        assertEquals(1, rowCount);
    }

    @Test
    void updateCaseStatus_updatesAndReturnsTimestamp() {
        Map<String, Object> result = caseSystemTools.updateCaseStatus("D-10291", "PENDING_EVIDENCE");

        assertEquals("PENDING_EVIDENCE", result.get("status"));
        assertNotNull(result.get("updatedAt"));

        Map<String, Object> reloaded = caseSystemTools.getCase("D-10291");
        assertEquals("PENDING_EVIDENCE", reloaded.get("caseStatus"));
    }

    @Test
    void createAuditEntry_insertsAndReturnsEntryId() {
        Map<String, Object> result = caseSystemTools.createAuditEntry(
                "D-10291", "EVIDENCE_REQUEST_TASK_CREATED", "ORCHESTRATOR");

        assertInstanceOf(Integer.class, result.get("entryId"));
        assertTrue((Integer) result.get("entryId") > 0);
        assertNotNull(result.get("createdAt"));
    }
}
```

- [ ] **Step 2: Run the tests against real Postgres**

Run: `mvn -pl case-system-mcp test`
Expected: PASS, `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`. This is a real Testcontainers run — a Postgres container starts (image `pgvector/pgvector:pg16`, substituted as Postgres-compatible), `schema.sql` and `seed-data.sql` load, and all seven tests exercise real JPA/Hibernate mappings and real SQL.

If it fails, the failure is real and must be fixed — do not weaken an assertion or skip a test to make this pass. Likely failure points to check first: the `TaskEntity.missingItems` array mapping (`@JdbcTypeCode(SqlTypes.ARRAY)`), the `-parameters` compiler flag not taking effect (rebuild with `mvn -q clean compile -pl case-system-mcp` if tool parameter names look wrong), or `ddl-auto=validate` rejecting a column type mismatch against `schema.sql` (fix the entity mapping to match the schema, never the other way around — the schema SQL is the source of truth per Global Constraint #9).

- [ ] **Step 3: Capture and report the idempotency test's DB row-count assertion output specifically**

Run: `mvn -pl case-system-mcp test -Dtest=CaseSystemMcpToolsTest#createTask_duplicate_returnsExistingWithoutInsert`
Expected: PASS, `Tests run: 1, Failures: 0, Errors: 0`. Keep this output — it's required evidence for Task 6's acceptance summary (PLATFORM_CONTRACT §H: "Idempotency test (E5) explicitly verified — show the DB row count assertion output").

- [ ] **Step 4: Commit**

```bash
git add case-system-mcp/src/test/java/com/workbench/mcp/test
git commit -m "Add CaseSystemMcpToolsTest: Testcontainers-backed verification of all 5 MCP tools including idempotency"
```

---

### Task 5: contract-tests integration

**Files:**
- Modify: `contract-tests/pom.xml` (add `case-system-mcp` as a test dependency, plus the Testcontainers/Spring Boot test infra needed to boot it)
- Create: `contract-tests/src/test/java/com/workbench/contract/McpToolOutputContractTest.java`

**Interfaces:**
- Consumes: `CaseSystemMcpApplication`, `CaseSystemTools` from `case-system-mcp` (via the thin/classified jar produced by Task 1's `<classifier>exec</classifier>` fix — resolvable as a normal Maven dependency).
- Produces: nothing consumed by later tasks — this is Fix-4-style drift protection for Session 3.

- [ ] **Step 1: Add dependencies to contract-tests**

In `contract-tests/pom.xml`, add these four dependencies inside the existing `<dependencies>` block (alongside the existing `workbench-common`/`agui-support`/`jackson-databind`/`junit-jupiter` entries — leave those exactly as they are):
```xml
    <dependency>
      <groupId>com.workbench</groupId>
      <artifactId>case-system-mcp</artifactId>
      <version>${project.version}</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-testcontainers</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>testcontainers-postgresql</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>testcontainers-junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
```

Add a `<testResource>` pointing at `infra/seed` (same pattern as `case-system-mcp/pom.xml`, same rationale — reuse the single schema source of truth, don't copy it) inside `<build>`:
```xml
  <build>
    <testResources>
      <testResource>
        <directory>${project.basedir}/src/test/resources</directory>
      </testResource>
      <testResource>
        <directory>${project.basedir}/../infra/seed</directory>
        <includes>
          <include>schema.sql</include>
          <include>seed-data.sql</include>
        </includes>
      </testResource>
    </testResources>
    <plugins>
```
(Keep the existing `<plugins>` block — `maven-compiler-plugin` and `maven-surefire-plugin` — exactly as it is, just nest it after the new `<testResources>` block above.)

- [ ] **Step 2: Write the contract test**

`contract-tests/src/test/java/com/workbench/contract/McpToolOutputContractTest.java`:
```java
package com.workbench.contract;

import com.workbench.mcp.CaseSystemMcpApplication;
import com.workbench.mcp.tools.CaseSystemTools;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the real case-system-mcp application context and calls its tool service
 * directly (not over MCP/HTTP) to assert the exact field names PLATFORM_CONTRACT.md
 * §9 promises. This catches field-name drift (a renamed key, a case change) before
 * Session 3's Case Review Agent tries to parse these responses.
 */
@SpringBootTest(classes = CaseSystemMcpApplication.class)
@Testcontainers
class McpToolOutputContractTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("workbench")
            .withUsername("workbench")
            .withPassword("workbench")
            .withInitScripts("schema.sql", "seed-data.sql");

    @Autowired
    private CaseSystemTools caseSystemTools;

    @Test
    void listCaseDocumentsResponseHasContractFieldNames() {
        Map<String, Object> result = caseSystemTools.listCaseDocuments("D-10291");

        assertTrue(result.containsKey("documents"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents = (List<Map<String, Object>>) result.get("documents");
        assertTrue(documents.get(0).containsKey("docType"));
        assertTrue(documents.get(0).containsKey("present"));
    }

    @Test
    void createTaskResponseHasContractFieldNames() {
        Map<String, Object> result = caseSystemTools.createTask(
                "D-10291", "CONTRACT_TEST_TASK", List.of("CUSTOMER_DECLARATION"), "Dispute Operations");

        assertTrue(result.containsKey("taskId"));
        assertTrue(result.containsKey("createdAt"));
    }

    @Test
    void updateCaseStatusResponseHasContractFieldNames() {
        Map<String, Object> result = caseSystemTools.updateCaseStatus("D-10291", "PENDING_EVIDENCE");

        assertTrue(result.containsKey("caseId"));
        assertTrue(result.containsKey("status"));
        assertTrue(result.containsKey("updatedAt"));
    }

    @Test
    void createAuditEntryResponseHasContractFieldNames() {
        Map<String, Object> result = caseSystemTools.createAuditEntry(
                "D-10291", "EVIDENCE_REQUEST_TASK_CREATED", "ORCHESTRATOR");

        assertTrue(result.containsKey("entryId"));
        assertTrue(result.containsKey("createdAt"));
    }
}
```

- [ ] **Step 3: Run the contract test**

Run: `mvn -pl contract-tests test -Dtest=McpToolOutputContractTest`
Expected: PASS, `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.

If this fails to resolve `case-system-mcp` as a dependency (e.g. `ClassNotFoundException` for a Spring/JPA/Postgres class at test-compile or test-runtime), the `<classifier>exec</classifier>` fix from Task 1 Step 4 either wasn't applied or `case-system-mcp` hasn't been `mvn install`ed to the local repo yet — run `mvn -q install -pl case-system-mcp -am -DskipTests` first, then retry.

- [ ] **Step 4: Run the full contract-tests suite to confirm no regressions**

Run: `mvn -pl contract-tests test`
Expected: `Tests run: 15, Failures: 0, Errors: 0, Skipped: 0` (11 pre-existing + 4 new).

- [ ] **Step 5: Commit**

```bash
git add contract-tests/pom.xml contract-tests/src/test/java/com/workbench/contract/McpToolOutputContractTest.java
git commit -m "Add McpToolOutputContractTest asserting case-system-mcp tool output field names"
```

---

### Task 6: Final acceptance verification

**Files:** none created — verification only.

**Interfaces:**
- Consumes: everything from Tasks 1–5.
- Produces: nothing new; this is the acceptance gate before Session 3.

- [ ] **Step 1: Run the full reactor test suite**

Run: `mvn install -pl case-system-mcp -am -DskipTests` (ensures `case-system-mcp`'s thin jar is in the local repo for `contract-tests` to resolve), then `mvn test` from the repo root.
Expected: `BUILD SUCCESS`. `workbench-common`: 12. `agui-support`: 19. `case-system-mcp`: 7. `contract-tests`: 15. The three remaining stub modules (`case-review-agent`, `policy-agent`, `orchestrator-agent`, `packaging=pom`) report no tests.

- [ ] **Step 2: Verify case-system-mcp's test count specifically**

Run: `mvn -pl case-system-mcp test`
Expected: `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 3: Verify contract-tests' McpToolOutputContractTest specifically**

Run: `mvn -pl contract-tests test -Dtest=McpToolOutputContractTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 4: Re-confirm the idempotency test evidence**

Run: `mvn -pl case-system-mcp test -Dtest=CaseSystemMcpToolsTest#createTask_duplicate_returnsExistingWithoutInsert`
Expected: PASS. Keep this output for the acceptance summary.

- [ ] **Step 5: Jackson import check**

Run:
```bash
grep -r "com\.fasterxml\.jackson\.core\|com\.fasterxml\.jackson\.databind" case-system-mcp/src 2>/dev/null
```
Expected: no output.

- [ ] **Step 6: Confirm the Spring AI MCP artifact comment is in place**

Run: `grep -n "spring-ai-starter-mcp-server-webmvc" case-system-mcp/pom.xml`
Expected: at least one match, inside the comment block documenting the verified artifact ID and version (Task 1 Step 4).

- [ ] **Step 7: Final commit if any verification step required a fix**

If Steps 1–6 all passed without needing changes, there is nothing to commit here. If a fix was needed, stage it and commit:
```bash
git add -A
git commit -m "Fix issues found during Session 2 acceptance verification"
```
