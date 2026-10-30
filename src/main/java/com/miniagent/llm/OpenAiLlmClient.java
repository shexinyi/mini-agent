package com.miniagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniagent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * OpenAI Chat Completions 兼容客户端。DeepSeek、通义、Kimi、Ollama 都走这个协议。
 * <p>
 * 请求：messages + tools
 * 响应：choices[0].message，里面可能有 tool_calls
 */
public final class OpenAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmClient.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public OpenAiLlmClient(AgentProperties.Llm properties, ObjectMapper mapper) {
        this.mapper = mapper;
        this.baseUrl = normalizeBaseUrl(properties.getBaseUrl());
        this.apiKey = properties.getApiKey() == null ? "" : properties.getApiKey().trim();
        this.model = properties.getModel() == null || properties.getModel().isBlank()
                ? "deepseek-chat"
                : properties.getModel().trim();
    }

    @Override
    public JsonNode chat(ArrayNode messages, ArrayNode tools) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("LLM_API_KEY 为空。请设置环境变量，或把 LLM_MODE 改回 mock。");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.set("messages", messages);
        body.put("temperature", 0);
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", tools);
            body.put("tool_choice", "auto");
        }

        try {
            String payload = mapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            log.info("—— 请求模型 {} {} ——\n{}", model, baseUrl, pretty(body));
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("—— 模型返回 HTTP {} ——\n{}", response.statusCode(), snippet(response.body()));
                throw new IllegalStateException("LLM 调用失败 " + response.statusCode() + ": " + snippet(response.body()));
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode message = root.at("/choices/0/message");
            if (message.isMissingNode() || message.isNull()) {
                throw new IllegalStateException("响应里没有 choices[0].message: " + snippet(response.body()));
            }
            log.info("—— 模型返回 finish_reason={} usage={} ——\n{}",
                    root.at("/choices/0/finish_reason").asText(""),
                    root.path("usage").isMissingNode() ? "" : root.path("usage").toString(),
                    pretty(message));
            return message;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待模型响应时被中断", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("agent.llm.base-url 不能为空");
        }
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String pretty(JsonNode node) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return String.valueOf(node);
        }
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.strip();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500) + "...";
    }
}
