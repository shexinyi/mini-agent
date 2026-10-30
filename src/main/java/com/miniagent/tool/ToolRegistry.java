package com.miniagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工具表。schema() 就是发给模型的 tools 数组。 */
public final class ToolRegistry {

    private final Map<String, Tool> byName = new LinkedHashMap<>();
    private final ArrayNode schema;

    public ToolRegistry(List<Tool> toolList, ObjectMapper mapper) {
        ArrayNode tools = mapper.createArrayNode();
        for (Tool tool : toolList) {
            byName.put(tool.name(), tool);
            ObjectNode function = mapper.createObjectNode();
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.set("parameters", tool.parameters());
            ObjectNode wrapper = mapper.createObjectNode();
            wrapper.put("type", "function");
            wrapper.set("function", function);
            tools.add(wrapper);
        }
        this.schema = tools;
    }

    public ArrayNode schema() {
        return schema;
    }

    public Tool get(String name) {
        return byName.get(name);
    }

    public Collection<Tool> all() {
        return byName.values();
    }
}
