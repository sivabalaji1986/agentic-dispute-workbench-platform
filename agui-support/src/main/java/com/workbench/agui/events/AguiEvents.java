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
