package com.miniagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * 一次对话补全。返回的是 assistant 消息本身，不是整段 HTTP 响应。
 */
public interface LlmClient {

    JsonNode chat(ArrayNode messages, ArrayNode tools);
}
