package com.miniagent.web;

import com.fasterxml.jackson.databind.JsonNode;

public record HistoryResponse(String conversationId, JsonNode messages) {
}
