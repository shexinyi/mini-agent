package com.miniagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 一个工具 = 名字 + 给模型看的描述 + JSON Schema + 执行逻辑。 */
public interface Tool {

    String name();

    String description();

    ObjectNode parameters();

    String execute(JsonNode args) throws Exception;
}
