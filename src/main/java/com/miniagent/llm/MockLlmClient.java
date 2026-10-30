package com.miniagent.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 没有 API Key 时的替身模型。它只看用户那句话里的关键词，返回和真实模型相同形状的 message。
 * <p>
 * 循环不关心对面是真模型还是这个类。mock 看到工具结果就拼接成最终回答，只演示一轮工具调用。
 * 真实模型可以连续多轮决定还要不要再调工具。
 */
public final class MockLlmClient implements LlmClient {

    static final String NO_TOOL_ANSWER = "这是 mock 模式，我没有调用工具。可以让我计算表达式、问现在几点，或问 agent / RAG / ReAct。";

    private static final Pattern EXPR = Pattern.compile(
            "(?:\\d+(?:\\.\\d+)?|\\([^()]{0,80}\\))(?:\\s*[+\\-*/]\\s*(?:\\d+(?:\\.\\d+)?|\\([^()]{0,80}\\)))+");

    private static final AtomicInteger IDS = new AtomicInteger();

    private final ObjectMapper mapper;

    public MockLlmClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public JsonNode chat(ArrayNode messages, ArrayNode tools) {
        int lastUser = lastUserIndex(messages);
        if (lastUser < 0) {
            return assistantText(NO_TOOL_ANSWER);
        }
        String userText = textOf(messages.get(lastUser).get("content"));
        List<String> observations = observationsAfter(messages, lastUser);
        if (!observations.isEmpty()) {
            return assistantText(summarize(observations));
        }

        ArrayNode calls = mapper.createArrayNode();
        String expression = extractExpression(userText);
        if ((expression != null || userText.contains("算")) && toolExists(tools, "calculator")) {
            String value = expression == null ? incompleteExpression(userText) : expression;
            calls.add(toolCall("calculator", arguments("expression", value)));
        }
        if (wantsTime(userText) && toolExists(tools, "get_current_time")) {
            calls.add(toolCall("get_current_time", "{}"));
        }
        String query = knowledgeQuery(userText);
        if (query != null && toolExists(tools, "search_knowledge")) {
            calls.add(toolCall("search_knowledge", arguments("query", query)));
        }
        if (calls.isEmpty()) {
            return assistantText(NO_TOOL_ANSWER);
        }
        return assistantToolCalls(calls);
    }

    static String extractExpression(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = EXPR.matcher(text);
        String best = null;
        while (matcher.find()) {
            String expr = matcher.group().replaceAll("\\s+", "");
            boolean hasHardOp = expr.indexOf('+') >= 0 || expr.indexOf('*') >= 0
                    || expr.indexOf('/') >= 0 || expr.indexOf('(') >= 0;
            boolean subtractOnly = !hasHardOp && expr.indexOf('-') >= 0;
            if (hasHardOp || (subtractOnly && text.contains("算"))) {
                if (best == null || expr.length() > best.length()) {
                    best = expr;
                }
            }
        }
        return best;
    }

    /** 算式没抽出来，但句子里有半截括号时，把这段交给计算器，让它返回 ERROR。 */
    static String incompleteExpression(String text) {
        int open = text.lastIndexOf('(');
        if (open < 0) {
            return "";
        }
        return text.substring(open).replaceAll("\\s+", "");
    }

    static boolean wantsTime(String text) {
        return text.contains("几点")
                || text.contains("当前时间")
                || text.contains("现在时间")
                || text.contains("几号")
                || text.contains("日期");
    }

    static String knowledgeQuery(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("react")) {
            return "react";
        }
        if (lower.contains("rag") || text.contains("检索")) {
            return "rag";
        }
        if (lower.contains("agent") || text.contains("智能体")) {
            return "agent";
        }
        return null;
    }

    private static int lastUserIndex(ArrayNode messages) {
        int index = -1;
        for (int i = 0; i < messages.size(); i++) {
            if ("user".equals(messages.get(i).path("role").asText())) {
                index = i;
            }
        }
        return index;
    }

    private static List<String> observationsAfter(ArrayNode messages, int lastUser) {
        List<String> observations = new ArrayList<>();
        for (int i = lastUser + 1; i < messages.size(); i++) {
            JsonNode message = messages.get(i);
            if ("tool".equals(message.path("role").asText())) {
                observations.add(textOf(message.get("content")));
            }
        }
        return observations;
    }

    private static String summarize(List<String> observations) {
        StringBuilder sb = new StringBuilder();
        for (String observation : observations) {
            sb.append(observation.strip()).append('\n');
        }
        sb.append('\n').append("以上是工具的原始返回。mock 模式只做拼接；openai 模式下，这段话会由大模型来写。");
        return sb.toString().strip();
    }

    private static boolean toolExists(ArrayNode tools, String name) {
        if (tools == null) {
            return false;
        }
        for (JsonNode tool : tools) {
            if (name.equals(tool.at("/function/name").asText())) {
                return true;
            }
        }
        return false;
    }

    private ObjectNode toolCall(String name, String argumentsJson) {
        ObjectNode call = mapper.createObjectNode();
        call.put("id", "call_mock_" + IDS.incrementAndGet());
        call.put("type", "function");
        ObjectNode function = call.putObject("function");
        function.put("name", name);
        function.put("arguments", argumentsJson);
        return call;
    }

    private String arguments(String key, String value) {
        ObjectNode node = mapper.createObjectNode();
        node.put(key, value);
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private ObjectNode assistantText(String content) {
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "assistant");
        message.put("content", content);
        return message;
    }

    private ObjectNode assistantToolCalls(ArrayNode calls) {
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "assistant");
        message.putNull("content");
        message.set("tool_calls", calls);
        return message;
    }

    private static String textOf(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        return node.asText("");
    }
}
