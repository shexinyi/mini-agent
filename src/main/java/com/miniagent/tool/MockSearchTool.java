package com.miniagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 写死的三句话知识库，用来演示「模型自己决定去查，而不是你在代码里 if 一下」。 */
public final class MockSearchTool implements Tool {

    private static final List<Map.Entry<String, String>> KB = List.of(
            Map.entry("agent", "AI Agent 是能感知环境、自主决策并调用工具完成目标的系统，核心是 LLM+Tools+Loop+Memory。"),
            Map.entry("rag", "RAG（检索增强生成）：先向量检索相关文档，再拼进 prompt 让模型基于事实回答。"),
            Map.entry("react", "ReAct = Reasoning + Acting，让模型交替输出思考和动作，是 Agent 的经典范式。")
    );

    private final ObjectMapper mapper;

    public MockSearchTool(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "search_knowledge";
    }

    @Override
    public String description() {
        return "查询 AI/Agent 相关的内部知识库。";
    }

    @Override
    public ObjectNode parameters() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode query = schema.putObject("properties").putObject("query");
        query.put("type", "string");
        query.put("description", "关键词，如 agent / rag / react");
        query.putArray("enum").add("agent").add("rag").add("react");
        schema.putArray("required").add("query");
        return schema;
    }

    @Override
    public String execute(JsonNode args) {
        String query = args.path("query").asText("").toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : KB) {
            if (query.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "知识库中未找到与「" + query + "」相关的内容。";
    }
}
