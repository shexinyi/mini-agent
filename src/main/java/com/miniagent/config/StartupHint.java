package com.miniagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class StartupHint implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupHint.class);

    private final AgentProperties properties;
    private final Environment environment;

    public StartupHint(AgentProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String port = environment.getProperty("local.server.port",
                environment.getProperty("server.port", "8080"));
        String mode = properties.getLlm().getMode();
        log.info("MiniAgent 已启动。模式={}，浏览器打开 http://localhost:{}/", mode, port);
        if ("openai".equalsIgnoreCase(mode) && isBlank(properties.getLlm().getApiKey())) {
            log.warn("当前是 openai 模式，但 LLM_API_KEY 为空。");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
