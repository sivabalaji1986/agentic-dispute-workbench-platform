package com.workbench.agui.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonPatchOp(
        @JsonProperty("op") String op,
        @JsonProperty("path") String path,
        @JsonProperty("value") Object value) {
}
