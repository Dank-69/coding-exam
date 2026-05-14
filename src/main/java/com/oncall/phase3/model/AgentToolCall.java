package com.oncall.phase3.model;

import java.util.Map;

public record AgentToolCall(
        String tool,
        Map<String, Object> args,
        boolean success,
        int length,
        String excerpt
) {
}

