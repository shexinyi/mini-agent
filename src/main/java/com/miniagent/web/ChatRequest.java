package com.miniagent.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @Pattern(regexp = "[A-Za-z0-9_-]{1,64}", message = "conversationId 格式不正确")
        @Size(max = 64) String conversationId,
        @NotBlank(message = "message 不能为空")
        @Size(max = 4000) String message) {
}
