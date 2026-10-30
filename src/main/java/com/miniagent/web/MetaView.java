package com.miniagent.web;

public record MetaView(
        String mode,
        String model,
        int maxSteps,
        boolean apiKeyConfigured,
        String hint) {
}
