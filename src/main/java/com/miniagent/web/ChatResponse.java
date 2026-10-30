package com.miniagent.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.miniagent.agent.ToolTrace;

import java.util.List;

public record ChatResponse(
        String conversationId,
        String answer,
        boolean finished,
        List<ToolTrace> steps,
        JsonNode messages) {
}
