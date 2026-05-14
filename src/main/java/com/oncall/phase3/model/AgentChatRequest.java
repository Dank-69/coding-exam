package com.oncall.phase3.model;

import java.util.List;

public record AgentChatRequest(
        String message,
        List<AgentMessage> history
) {
}

