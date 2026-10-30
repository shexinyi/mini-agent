package com.miniagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniagent.llm.LlmClient;
import com.miniagent.tool.Tool;
import com.miniagent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Agent = LLM + Tools + Loop + Memory。
 * <p>
 * 这个类只负责 Loop。Memory 由调用方传入：单机笔记里它是字段，Web 里按会话放在 {@code ConversationMemory}。
 * Spring 不参与「下一步调哪个工具」，那是模型在运行时决定的。
 */
public final class MiniAgent {

    private static final Logger log = LoggerFactory.getLogger(MiniAgent.class);
    private static final int MAX_TOOL_CHARS = 2000;
    private static final DateTimeFormatter TRACE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final ObjectMapper mapper;
    private final int maxSteps;
    private final AgentTraceWriter trace;

    public MiniAgent(LlmClient llm, ToolRegistry tools, ObjectMapper mapper, int maxSteps, AgentTraceWriter trace) {
        this.llm = Objects.requireNonNull(llm);
        this.tools = Objects.requireNonNull(tools);
        this.mapper = Objects.requireNonNull(mapper);
        this.maxSteps = Math.max(1, maxSteps);
        this.trace = Objects.requireNonNull(trace);
    }

    public AgentResult run(String conversationId, ArrayNode memory, String userInput) {
        Objects.requireNonNull(memory, "memory");
        if (userInput == null || userInput.isBlank()) {
            return AgentResult.incomplete("请输入内容。", List.of());
        }
        String input = userInput.strip();
        memory.add(textMessage("user", input));
        trace(conversationId, 0, "user", "", "", input);

        Map<String, Integer> repeated = new HashMap<>();
        List<ToolTrace> steps = new ArrayList<>();

        for (int step = 1; step <= maxSteps; step++) {
            log.info("[step {}] 调用模型 conversationId={}", step, conversationId == null ? "" : conversationId);
            JsonNode assistant;
            try {
                assistant = llm.chat(memory.deepCopy(), tools.schema().deepCopy());
            } catch (Exception e) {
                log.warn("[step {}] 模型调用异常: {}", step, e.toString());
                trace(conversationId, step, "model_error", "", "", e.toString());
                return AgentResult.incomplete("模型调用异常：" + e.getMessage(), steps);
            }
            if (!(assistant instanceof ObjectNode assistantObject)) {
                trace(conversationId, step, "model_error", "", "", "响应不是 JSON 对象");
                return AgentResult.incomplete("模型返回了无法识别的消息。", steps);
            }

            // assistant 消息要原样放回 memory，包括 tool_calls。下一轮靠 tool_call_id 把结果对上。
            normalize(assistantObject, step);
            memory.add(assistantObject);

            JsonNode toolCalls = assistantObject.path("tool_calls");
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                String content = textOf(assistantObject.get("content"));
                log.info("[step {}] 最终答案: {}", step, abbreviate(content, 160));
                trace(conversationId, step, "final", "", "", content);
                return AgentResult.complete(content, steps);
            }

            List<ParsedCall> calls = parseCalls(toolCalls);
            List<ParsedCall> toRun = new ArrayList<>();
            Map<String, String> results = new LinkedHashMap<>();
            for (ParsedCall call : calls) {
                int seen = repeated.merge(fingerprint(call), 1, Integer::sum);
                if (seen > 2) {
                    results.put(call.id(), "ERROR: 相同工具和参数已重复调用超过 2 次，请停止重试并直接回答用户。");
                } else {
                    toRun.add(call);
                }
            }
            if (toRun.size() > 1) {
                log.info("[step {}] 并行执行 {} 个工具", step, toRun.size());
            }
            results.putAll(executeParallel(toRun));

            for (ParsedCall call : calls) {
                String result = results.getOrDefault(call.id(), "ERROR: 工具没有返回结果");
                log.info("[step {}] 工具 {} 参数 {} => {}", step, call.name(), call.argumentsRaw(), abbreviate(result, 160));
                memory.add(toolMessage(call.id(), result));
                steps.add(new ToolTrace(step, call.name(), call.argumentsRaw(), result));
                trace(conversationId, step, "tool", call.name(), call.argumentsRaw(), result);
            }
        }

        String stop = "已达到最大步数 " + maxSteps + "，任务未完成（防止死循环的兜底）。";
        log.info(stop);
        trace(conversationId, maxSteps, "max_steps", "", "", stop);
        return AgentResult.incomplete(stop, steps);
    }

    /**
     * 一次返回多个 tool_calls 时并行执行。回填仍按模型给出的顺序，tool_call_id 不能错位。
     * 演示用 commonPool；线上应换成有界线程池。
     */
    private Map<String, String> executeParallel(List<ParsedCall> calls) {
        Map<String, String> results = new LinkedHashMap<>();
        if (calls.isEmpty()) {
            return results;
        }
        if (calls.size() == 1) {
            ParsedCall call = calls.get(0);
            results.put(call.id(), executeOne(call));
            return results;
        }
        List<CompletableFuture<String>> futures = calls.stream()
                .map(call -> CompletableFuture.supplyAsync(() -> executeOne(call)))
                .toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        for (int i = 0; i < calls.size(); i++) {
            results.put(calls.get(i).id(), futures.get(i).join());
        }
        return results;
    }

    private String executeOne(ParsedCall call) {
        try {
            if (call.name().isBlank()) {
                return error("工具名为空");
            }
            Tool tool = tools.get(call.name());
            if (tool == null) {
                return error("工具不存在 [" + call.name() + "]");
            }
            JsonNode args;
            try {
                args = mapper.readTree(call.argumentsRaw().isBlank() ? "{}" : call.argumentsRaw());
            } catch (Exception e) {
                return error("参数不是合法 JSON");
            }
            if (!args.isObject()) {
                return error("参数必须是 JSON 对象");
            }
            String result = tool.execute(args);
            if (result == null) {
                result = "";
            }
            if (result.length() > MAX_TOOL_CHARS) {
                result = result.substring(0, MAX_TOOL_CHARS) + "...(已截断)";
            }
            return result;
        } catch (Exception e) {
            // 工具失败也是上下文。把错误文本交回模型，让它换参数或换策略。
            return error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String error(String message) {
        return "ERROR: " + message + "。请换参数或换策略，不要用相同参数重试。";
    }

    private void normalize(ObjectNode assistant, int step) {
        if (!"assistant".equals(assistant.path("role").asText())) {
            assistant.put("role", "assistant");
        }
        if (!assistant.has("content")) {
            assistant.putNull("content");
        }
        JsonNode calls = assistant.get("tool_calls");
        if (calls == null || calls.isNull() || !calls.isArray()) {
            return;
        }
        int index = 0;
        for (JsonNode call : calls) {
            if (call instanceof ObjectNode object) {
                if (object.path("id").asText("").isBlank()) {
                    object.put("id", "call_" + step + "_" + index);
                }
                if (object.path("type").asText("").isBlank()) {
                    object.put("type", "function");
                }
                JsonNode functionNode = object.get("function");
                if (functionNode instanceof ObjectNode function) {
                    JsonNode arguments = function.get("arguments");
                    if (arguments == null || arguments.isNull()) {
                        function.put("arguments", "{}");
                    } else if (!arguments.isTextual()) {
                        String json = arguments.toString();
                        function.put("arguments", json);
                    } else if (arguments.asText().isBlank()) {
                        function.put("arguments", "{}");
                    }
                }
            }
            index++;
        }
    }

    private List<ParsedCall> parseCalls(JsonNode toolCalls) {
        List<ParsedCall> calls = new ArrayList<>();
        for (JsonNode call : toolCalls) {
            calls.add(new ParsedCall(
                    call.path("id").asText(""),
                    call.at("/function/name").asText(""),
                    argumentsText(call)));
        }
        return calls;
    }

    private static String argumentsText(JsonNode call) {
        JsonNode arguments = call.at("/function/arguments");
        if (arguments.isMissingNode() || arguments.isNull()) {
            return "{}";
        }
        if (arguments.isTextual()) {
            String text = arguments.asText();
            return text.isBlank() ? "{}" : text;
        }
        return arguments.toString();
    }

    private String fingerprint(ParsedCall call) {
        String raw = call.argumentsRaw();
        try {
            JsonNode node = mapper.readTree(raw.isBlank() ? "{}" : raw);
            return call.name() + "|" + mapper.writeValueAsString(node);
        } catch (Exception e) {
            return call.name() + "|" + raw;
        }
    }

    private ObjectNode textMessage(String role, String content) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", role);
        node.put("content", content);
        return node;
    }

    private ObjectNode toolMessage(String toolCallId, String content) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", "tool");
        node.put("tool_call_id", toolCallId);
        node.put("content", content);
        return node;
    }

    private static String textOf(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("");
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : node) {
                if (part.isTextual()) {
                    sb.append(part.asText(""));
                } else if (part.has("text")) {
                    sb.append(part.path("text").asText(""));
                }
            }
            return sb.toString();
        }
        return node.asText("");
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "...";
    }

    private void trace(String conversationId, int step, String type, String tool, String arguments, String result) {
        ObjectNode event = mapper.createObjectNode();
        event.put("time", LocalDateTime.now().format(TRACE_TIME));
        event.put("conversationId", conversationId == null ? "" : conversationId);
        event.put("step", step);
        event.put("type", type);
        event.put("tool", tool == null ? "" : tool);
        event.put("arguments", arguments == null ? "" : arguments);
        event.put("result", result == null ? "" : result);
        trace.append(event);
    }

    private record ParsedCall(String id, String name, String argumentsRaw) {
    }
}
