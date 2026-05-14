package com.oncall.phase3.model;

import java.util.List;

public record AgentChatResponse(
        String answer,
        List<AgentToolCall> toolCalls,
        long totalTokens
) {
}

