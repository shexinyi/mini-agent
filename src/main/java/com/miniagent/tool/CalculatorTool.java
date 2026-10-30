package com.miniagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 四则运算。描述和 parameters 会放进请求的 tools 字段，模型靠它们决定要不要调用、传什么参数。
 */
public final class CalculatorTool implements Tool {

    private final ObjectMapper mapper;

    public CalculatorTool(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String description() {
        return "计算数学表达式，支持 + - * / 和括号。输入应为一个合法表达式字符串。";
    }

    @Override
    public ObjectNode parameters() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putObject("properties")
                .putObject("expression")
                .put("type", "string")
                .put("description", "如 (3+5)*2");
        schema.putArray("required").add("expression");
        return schema;
    }

    @Override
    public String execute(JsonNode args) {
        String expr = args.path("expression").asText("").trim();
        if (expr.isEmpty()) {
            throw new IllegalArgumentException("表达式为空");
        }
        if (expr.length() > 200) {
            throw new IllegalArgumentException("表达式过长");
        }
        Parser parser = new Parser(expr);
        double value = parser.expr();
        if (!parser.finished()) {
            throw new IllegalArgumentException("表达式没有解析完，位置 " + parser.position());
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("计算结果无效");
        }
        String shown = value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
        return expr + " = " + shown;
    }

    /** 递归下降，只覆盖 + - * / 和括号。 */
    private static final class Parser {
        private final String s;
        private int i;

        Parser(String expression) {
            this.s = expression.replaceAll("\\s+", "");
        }

        double expr() {
            double value = term();
            while (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                char op = s.charAt(i++);
                double right = term();
                value = op == '+' ? value + right : value - right;
            }
            return value;
        }

        double term() {
            double value = factor();
            while (i < s.length() && (s.charAt(i) == '*' || s.charAt(i) == '/')) {
                char op = s.charAt(i++);
                double right = factor();
                value = op == '*' ? value * right : value / right;
            }
            return value;
        }

        double factor() {
            if (i < s.length() && s.charAt(i) == '(') {
                i++;
                double value = expr();
                if (i < s.length() && s.charAt(i) == ')') {
                    i++;
                }
                return value;
            }
            if (i < s.length() && s.charAt(i) == '-') {
                i++;
                return -factor();
            }
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                i++;
            }
            if (start == i) {
                throw new IllegalArgumentException("表达式非法，位置 " + i);
            }
            return Double.parseDouble(s.substring(start, i));
        }

        boolean finished() {
            return i >= s.length();
        }

        int position() {
            return i;
        }
    }
}
