package com.miniagent.web;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "agent.llm.mode=mock",
        "agent.trace-file=",
        "spring.main.banner-mode=off"
})
@AutoConfigureMockMvc
class ChatApiTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void articleExampleRunsCalculatorTimeAndSearch() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content("""
                                {"message":"帮我算一下 128*37-96，再告诉我现在几点，最后解释一下什么是 RAG"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finished").value(true))
                .andExpect(jsonPath("$.answer", containsString("4640")))
                .andExpect(jsonPath("$.answer", containsString("RAG")))
                .andExpect(jsonPath("$.steps.length()").value(3))
                .andExpect(jsonPath("$.steps[0].tool").value("calculator"))
                .andExpect(jsonPath("$.steps[0].result", containsString("4640")))
                .andExpect(jsonPath("$.steps[1].tool").value("get_current_time"))
                .andExpect(jsonPath("$.steps[1].result", matchesPattern("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")))
                .andExpect(jsonPath("$.steps[2].tool").value("search_knowledge"))
                .andExpect(jsonPath("$.steps[2].result", containsString("检索增强生成")))
                .andExpect(jsonPath("$.messages.length()").value(7))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content", containsString("calculator")))
                .andExpect(jsonPath("$.messages[2].tool_calls[0].function.name").value("calculator"))
                .andExpect(jsonPath("$.messages[3].role").value("tool"));
    }

    @Test
    void plainChatDoesNotCallTools() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finished").value(true))
                .andExpect(jsonPath("$.steps.length()").value(0))
                .andExpect(jsonPath("$.answer", containsString("没有调用工具")));
    }

    @Test
    void badExpressionIsReturnedToTheModel() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content("{\"message\":\"帮我算一下\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].tool").value("calculator"))
                .andExpect(jsonPath("$.steps[0].result", containsString("表达式为空")))
                .andExpect(jsonPath("$.answer", containsString("ERROR:")));
    }

    @Test
    void memorySurvivesTheNextTurn() throws Exception {
        MvcResult first = mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content("{\"message\":\"什么是 RAG\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].tool").value("search_knowledge"))
                .andReturn();
        String id = JsonPath.read(first.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.conversationId");

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content("{\"conversationId\":\"" + id + "\",\"message\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.length()").value(0))
                .andExpect(jsonPath("$.messages.length()").value(7))
                .andExpect(jsonPath("$.messages[1].content", containsString("RAG")));

        mvc.perform(get("/api/conversations/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(7));

        mvc.perform(delete("/api/conversations/" + id))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/conversations/" + id))
                .andExpect(status().isNotFound());
    }

    @Test
    void blankMessageIsRejected() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void toolsAndMetaAreVisible() throws Exception {
        mvc.perform(get("/api/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].name").value("calculator"))
                .andExpect(jsonPath("$[2].parameters.properties.query.enum[1]").value("rag"));
        mvc.perform(get("/api/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("mock"))
                .andExpect(jsonPath("$.maxSteps").value(8));
    }
}
