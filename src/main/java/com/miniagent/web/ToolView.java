package com.miniagent.web;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolView(String name, String description, JsonNode parameters) {
}
