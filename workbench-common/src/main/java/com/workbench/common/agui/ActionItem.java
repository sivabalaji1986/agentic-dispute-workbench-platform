package com.workbench.common.agui;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionItem(String id, String label) {
}
