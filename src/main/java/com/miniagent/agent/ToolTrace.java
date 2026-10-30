package com.miniagent.agent;

/** 循环里的一次工具执行，返回给页面和接口，方便对照 memory。 */
public record ToolTrace(int step, String tool, String arguments, String result) {
}
