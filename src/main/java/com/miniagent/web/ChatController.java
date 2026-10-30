package com.miniagent.web;

import com.miniagent.agent.AgentResult;
import com.miniagent.agent.MiniAgent;
import com.miniagent.config.AgentProperties;
import com.miniagent.memory.Conversation;
import com.miniagent.memory.ConversationMemory;
import com.miniagent.tool.ToolRegistry;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * HTTP 只是入口。选工具、执行、再问模型，都在 {@link MiniAgent#run}。
 */
@RestController
public class ChatController {

    private final MiniAgent agent;
    private final ConversationMemory memory;
    private final ToolRegistry toolRegistry;
    private final AgentProperties properties;

    public ChatController(MiniAgent agent,
                          ConversationMemory memory,
                          ToolRegistry toolRegistry,
                          AgentProperties properties) {
        this.agent = agent;
        this.memory = memory;
        this.toolRegistry = toolRegistry;
        this.properties = properties;
    }

    @PostMapping("/api/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        String id = request.conversationId();
        if (id != null && id.isBlank()) {
            id = null;
        }
        Conversation conversation = memory.getOrCreate(id);
        // 整个循环持有会话锁，同一次会话不会并发改 messages。
        synchronized (conversation) {
            AgentResult result = agent.run(conversation.id(), conversation.messages(), request.message());
            return new ChatResponse(
                    conversation.id(),
                    result.answer(),
                    result.finished(),
                    result.steps(),
                    conversation.snapshot());
        }
    }

    @GetMapping("/api/conversations/{id}")
    public HistoryResponse history(@PathVariable String id) {
        Conversation conversation = memory.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        return new HistoryResponse(conversation.id(), conversation.snapshot());
    }

    @DeleteMapping("/api/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        memory.delete(id);
    }

    @GetMapping("/api/tools")
    public List<ToolView> tools() {
        return toolRegistry.all().stream()
                .map(tool -> new ToolView(tool.name(), tool.description(), tool.parameters()))
                .toList();
    }

    @GetMapping("/api/meta")
    public MetaView meta() {
        AgentProperties.Llm llm = properties.getLlm();
        String mode = llm.getMode() == null ? "mock" : llm.getMode().trim();
        boolean openai = "openai".equalsIgnoreCase(mode);
        boolean apiKeyConfigured = llm.getApiKey() != null && !llm.getApiKey().isBlank();
        String hint = openai
                ? "当前请求会发往 " + llm.getBaseUrl() + "，模型 " + llm.getModel()
                + (apiKeyConfigured ? "。" : "。还没有配置 LLM_API_KEY。")
                : "当前是 mock，请求停在本机。工具调用由关键词规则生成，用来把循环看清楚。";
        return new MetaView(
                openai ? "openai" : "mock",
                openai ? llm.getModel() : "mock",
                properties.getMaxSteps(),
                apiKeyConfigured,
                hint);
    }
}
