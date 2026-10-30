package com.miniagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniagent.llm.LlmClient;
import com.miniagent.tool.CalculatorTool;
import com.miniagent.tool.MockSearchTool;
import com.miniagent.tool.TimeTool;
import com.miniagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniAgentTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void toolErrorIsFedBackAndAssistantMessageStays() {
        MiniAgent agent = agent((messages, tools) -> {
            if (!"tool".equals(messages.get(messages.size() - 1).path("role").asText())) {
                return toolCall("call_err", "calculator", "{\"expression\":\"(\"}");
            }
            JsonNode last = messages.get(messages.size() - 1);
            assertTrue(last.path("content").asText().startsWith("ERROR:"));
            assertEquals("call_err", last.path("tool_call_id").asText());
            return assistant("表达式不完整，请重试");
        }, 4);

        ArrayNode memory = mapper.createArrayNode();
        AgentResult result = agent.run("c1", memory, "算一下");

        assertEquals("表达式不完整，请重试", result.answer());
        assertTrue(result.finished());
        assertEquals(1, result.steps().size());
        assertTrue(result.steps().get(0).result().startsWith("ERROR:"));
        assertEquals(List.of("user", "assistant", "tool", "assistant"), roles(memory));
        assertTrue(memory.get(1).has("tool_calls"));
    }

    @Test
    void multipleToolCallsAreWrittenBackBeforeTheNextModelCall() {
        MiniAgent agent = agent((messages, tools) -> {
            long toolMessages = countRole(messages, "tool");
            if (toolMessages == 0) {
                return toolCalls(
                        call("call_calc", "calculator", "{\"expression\":\"1+1\"}"),
                        call("call_time", "get_current_time", "{}"));
            }
            assertEquals(2, toolMessages);
            assertEquals("call_calc", messages.get(2).path("tool_call_id").asText());
            assertEquals("call_time", messages.get(3).path("tool_call_id").asText());
            return assistant("汇总完成");
        }, 4);

        ArrayNode memory = mapper.createArrayNode();
        AgentResult result = agent.run("c2", memory, "算一下并告诉我时间");

        assertEquals("汇总完成", result.answer());
        assertEquals("calculator", result.steps().get(0).tool());
        assertEquals("1+1 = 2", result.steps().get(0).result());
        assertEquals("get_current_time", result.steps().get(1).tool());
        assertTrue(result.steps().get(1).result().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
        assertEquals(List.of("user", "assistant", "tool", "tool", "assistant"), roles(memory));
    }

    @Test
    void objectArgumentsAreNormalizedToJsonString() {
        MiniAgent agent = agent((messages, tools) -> {
            if (countRole(messages, "tool") == 0) {
                ObjectNode arguments = mapper.createObjectNode().put("expression", "1+1");
                ObjectNode call = mapper.createObjectNode();
                call.put("id", "call_obj");
                call.put("type", "function");
                call.putObject("function").put("name", "calculator").set("arguments", arguments);
                return toolCalls(call);
            }
            return assistant("好了");
        }, 4);

        ArrayNode memory = mapper.createArrayNode();
        AgentResult result = agent.run("c3", memory, "1+1");

        assertEquals("1+1 = 2", result.steps().get(0).result());
        JsonNode stored = memory.get(1).at("/tool_calls/0/function/arguments");
        assertTrue(stored.isTextual());
        assertTrue(stored.asText().contains("1+1"));
        assertEquals("好了", result.answer());
    }

    @Test
    void repeatedIdenticalCallIsBlockedOnTheThirdTry() {
        MiniAgent agent = agent((messages, tools) -> {
            String last = messages.get(messages.size() - 1).path("content").asText("");
            if (last.contains("重复调用")) {
                return assistant("我停止了");
            }
            return toolCall("call_same", "calculator", "{\"expression\":\"1+1\"}");
        }, 6);

        AgentResult result = agent.run("c4", mapper.createArrayNode(), "算");

        assertEquals("我停止了", result.answer());
        assertEquals(3, result.steps().size());
        assertEquals("1+1 = 2", result.steps().get(0).result());
        assertEquals("1+1 = 2", result.steps().get(1).result());
        assertTrue(result.steps().get(2).result().contains("重复调用"));
    }

    @Test
    void maxStepsStopsTheLoop() {
        AtomicInteger calls = new AtomicInteger();
        MiniAgent agent = agent((messages, tools) -> {
            calls.incrementAndGet();
            return toolCall("call_loop", "calculator", "{\"expression\":\"1+1\"}");
        }, 2);

        AgentResult result = agent.run("c5", mapper.createArrayNode(), "一直算");

        assertFalse(result.finished());
        assertTrue(result.answer().contains("最大步数"));
        assertEquals(2, result.steps().size());
        assertEquals(2, calls.get());
    }

    private MiniAgent agent(LlmClient client, int maxSteps) {
        ToolRegistry registry = new ToolRegistry(List.of(
                new CalculatorTool(mapper),
                new TimeTool(mapper),
                new MockSearchTool(mapper)
        ), mapper);
        return new MiniAgent(client, registry, mapper, maxSteps, new AgentTraceWriter(mapper, ""));
    }

    private ObjectNode assistant(String content) {
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "assistant");
        message.put("content", content);
        return message;
    }

    private ObjectNode toolCall(String id, String name, String argumentsJson) {
        return toolCalls(call(id, name, argumentsJson));
    }

    private ObjectNode toolCalls(ObjectNode... calls) {
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "assistant");
        message.putNull("content");
        ArrayNode array = message.putArray("tool_calls");
        for (ObjectNode call : calls) {
            array.add(call);
        }
        return message;
    }

    private ObjectNode call(String id, String name, String argumentsJson) {
        ObjectNode call = mapper.createObjectNode();
        call.put("id", id);
        call.put("type", "function");
        ObjectNode function = call.putObject("function");
        function.put("name", name);
        function.put("arguments", argumentsJson);
        return call;
    }

    private static long countRole(ArrayNode messages, String role) {
        long count = 0;
        for (JsonNode message : messages) {
            if (role.equals(message.path("role").asText())) {
                count++;
            }
        }
        return count;
    }

    private static List<String> roles(ArrayNode memory) {
        List<String> roles = new ArrayList<>();
        memory.forEach(node -> roles.add(node.path("role").asText()));
        return roles;
    }
}
