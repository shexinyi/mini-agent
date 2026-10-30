package com.miniagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.AgentTraceWriter;
import com.miniagent.agent.MiniAgent;
import com.miniagent.llm.LlmClient;
import com.miniagent.llm.MockLlmClient;
import com.miniagent.llm.OpenAiLlmClient;
import com.miniagent.tool.CalculatorTool;
import com.miniagent.tool.MockSearchTool;
import com.miniagent.tool.TimeTool;
import com.miniagent.tool.ToolRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AgentConfig {

    @Bean
    public ToolRegistry toolRegistry(ObjectMapper mapper) {
        return new ToolRegistry(List.of(
                new CalculatorTool(mapper),
                new TimeTool(mapper),
                new MockSearchTool(mapper)
        ), mapper);
    }

    @Bean
    public LlmClient llmClient(AgentProperties properties, ObjectMapper mapper) {
        String mode = properties.getLlm().getMode() == null ? "mock" : properties.getLlm().getMode().trim();
        if ("openai".equalsIgnoreCase(mode)) {
            return new OpenAiLlmClient(properties.getLlm(), mapper);
        }
        if ("mock".equalsIgnoreCase(mode)) {
            return new MockLlmClient(mapper);
        }
        throw new IllegalStateException("agent.llm.mode 只支持 mock 或 openai，当前是 " + mode);
    }

    @Bean
    public AgentTraceWriter agentTraceWriter(AgentProperties properties, ObjectMapper mapper) {
        return new AgentTraceWriter(mapper, properties.getTraceFile());
    }

    @Bean
    public MiniAgent miniAgent(LlmClient llmClient,
                                ToolRegistry tools,
                                ObjectMapper mapper,
                                AgentProperties properties,
                                AgentTraceWriter trace) {
        return new MiniAgent(llmClient, tools, mapper, properties.getMaxSteps(), trace);
    }
}
