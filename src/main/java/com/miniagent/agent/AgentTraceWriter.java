package com.miniagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 把每一步追加成 JSONL。面试里这叫 trace：没有它，Agent 出了错只能猜。 */
public final class AgentTraceWriter {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceWriter.class);

    private final ObjectMapper mapper;
    private final String path;

    public AgentTraceWriter(ObjectMapper mapper, String path) {
        this.mapper = mapper;
        this.path = path == null ? "" : path.trim();
    }

    public void append(JsonNode event) {
        if (path.isBlank() || event == null) {
            return;
        }
        synchronized (this) {
            try {
                Path file = Path.of(path);
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                String line = mapper.writeValueAsString(event) + System.lineSeparator();
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.warn("trace 写入失败: {}", e.toString());
            }
        }
    }
}
