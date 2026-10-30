package com.miniagent.agent;

import java.util.List;

public record AgentResult(String answer, boolean finished, List<ToolTrace> steps) {

    public AgentResult {
        answer = answer == null ? "" : answer;
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public static AgentResult complete(String answer, List<ToolTrace> steps) {
        return new AgentResult(answer, true, steps);
    }

    public static AgentResult incomplete(String answer, List<ToolTrace> steps) {
        return new AgentResult(answer, false, steps);
    }
}
