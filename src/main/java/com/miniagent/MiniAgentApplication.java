package com.miniagent;

import com.miniagent.config.AgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AgentProperties.class)
public class MiniAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniAgentApplication.class, args);
    }
}
