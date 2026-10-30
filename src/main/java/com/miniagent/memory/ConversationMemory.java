package com.miniagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniagent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内的短期记忆。同一会话的消息数组会一直增长，重启后清空。
 * 线上会在这里做滑窗、摘要，或把稳定事实放到长期存储。
 */
@Component
public class ConversationMemory {

    private final ConcurrentHashMap<String, Conversation> store = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final AgentProperties properties;

    public ConversationMemory(ObjectMapper mapper, AgentProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    public Conversation getOrCreate(String id) {
        String key = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
        return store.computeIfAbsent(key, this::create);
    }

    public Optional<Conversation> find(String id) {
        return Optional.ofNullable(store.get(id));
    }

    public void delete(String id) {
        store.remove(id);
    }

    private Conversation create(String id) {
        ArrayNode messages = mapper.createArrayNode();
        ObjectNode system = mapper.createObjectNode();
        system.put("role", "system");
        String prompt = properties.getSystemPrompt();
        system.put("content", prompt == null || prompt.isBlank()
                ? "你是一个会使用工具的助手。需要计算、当前时间或知识库时调用工具，不要编造工具结果。"
                : prompt.strip());
        messages.add(system);
        return new Conversation(id, messages);
    }
}
