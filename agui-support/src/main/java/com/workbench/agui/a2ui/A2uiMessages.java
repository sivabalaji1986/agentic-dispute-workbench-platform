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
