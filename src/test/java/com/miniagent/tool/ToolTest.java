package com.miniagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CalculatorTool calculator = new CalculatorTool(mapper);

    @Test
    void calculatesTheArticleExample() {
        assertEquals("128*37-96 = 4640", eval("128*37-96"));
        assertEquals("(3+5)*2 = 16", eval("(3+5)*2"));
        assertEquals("2*(3+4) = 14", eval("2*(3+4)"));
        assertEquals("-3+5 = 2", eval("-3+5"));
        assertEquals("7/2 = 3.5", eval("7/2"));
        assertEquals("128 * 37 - 96 = 4640", eval("128 * 37 - 96"));
    }

    @Test
    void rejectsBadExpressions() {
        assertThrows(IllegalArgumentException.class, () -> eval(""));
        assertThrows(IllegalArgumentException.class, () -> eval("1/0"));
        assertThrows(IllegalArgumentException.class, () -> eval("1+2x"));
        assertThrows(IllegalArgumentException.class, () -> eval("("));
    }

    @Test
    void schemaRequiresExpression() {
        assertEquals("expression", calculator.parameters().path("required").path(0).asText());
    }

    @Test
    void searchHitsAndMisses() throws Exception {
        MockSearchTool search = new MockSearchTool(mapper);
        assertTrue(search.execute(args("query", "什么是 RAG")).contains("检索增强生成"));
        assertTrue(search.execute(args("query", "unknown")).contains("未找到"));
    }

    @Test
    void timeLooksLikeATimestamp() throws Exception {
        String value = new TimeTool(mapper).execute(mapper.createObjectNode());
        assertTrue(value.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    private String eval(String expression) {
        return calculator.execute(args("expression", expression));
    }

    private ObjectNode args(String key, String value) {
        return mapper.createObjectNode().put(key, value);
    }
}
