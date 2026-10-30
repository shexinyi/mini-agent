package com.miniagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockHeuristicTest {

    @Test
    void articleSentenceSelectsExpressionTimeAndRag() {
        String text = "帮我算一下 128*37-96，再告诉我现在几点，最后解释一下什么是 RAG";
        assertEquals("128*37-96", MockLlmClient.extractExpression(text));
        assertTrue(MockLlmClient.wantsTime(text));
        assertEquals("rag", MockLlmClient.knowledgeQuery(text));
    }

    @Test
    void parenthesesExpressionIsExtracted() {
        assertEquals("(3+5)*2", MockLlmClient.extractExpression("计算 (3+5)*2"));
    }

    @Test
    void dateIsNotTreatedAsMath() {
        assertNull(MockLlmClient.extractExpression("2025-06-01 开会"));
    }

    @Test
    void brokenParenthesisIsSentToTheCalculator() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MockLlmClient client = new MockLlmClient(mapper);
        ArrayNode messages = mapper.createArrayNode();
        ObjectNode user = mapper.createObjectNode();
        user.put("role", "user");
        user.put("content", "帮我算一下 (");
        messages.add(user);
        ArrayNode tools = mapper.createArrayNode();
        ObjectNode tool = mapper.createObjectNode();
        tool.putObject("function").put("name", "calculator");
        tools.add(tool);

        JsonNode reply = client.chat(messages, tools);
        assertEquals("(", mapper.readTree(reply.at("/tool_calls/0/function/arguments").asText()).path("expression").asText());
    }
}
