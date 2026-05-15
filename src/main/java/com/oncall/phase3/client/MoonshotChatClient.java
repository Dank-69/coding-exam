package com.oncall.phase3.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.oncall.common.config.MoonshotProperties;
import com.oncall.common.exception.ExternalServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class MoonshotChatClient {
    private static final Logger log = LoggerFactory.getLogger(MoonshotChatClient.class);

    private final WebClient moonshotWebClient;
    private final MoonshotProperties properties;

    public MoonshotChatClient(WebClient moonshotWebClient, MoonshotProperties properties) {
        this.moonshotWebClient = moonshotWebClient;
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.hasApiKey();
    }

    public JsonNode chatCompletion(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        if (!isEnabled()) {
            throw new ExternalServiceException("Chat API key is missing");
        }
        long start = System.currentTimeMillis();
        log.info("chat request provider={} model={} messages={} tools={}",
                properties.providerName(), properties.chatModel(), messages.size(), tools.size());
        JsonNode response = moonshotWebClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", properties.chatModel(),
                        "messages", messages,
                        "tools", tools
                ))
                .retrieve()
                .onStatus(status -> status.isError(), clientResponse ->
                        clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> Mono.error(new ExternalServiceException("Chat API failed: " + body)))
                )
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(Math.max(1, properties.requestTimeoutSeconds())))
                .onErrorMap(ex -> ex instanceof ExternalServiceException
                        ? ex
                        : new ExternalServiceException("Chat API request failed", ex))
                .block();

        log.info("chat ok provider={} model={} took={}ms",
                properties.providerName(), properties.chatModel(), System.currentTimeMillis() - start);
        return response;
    }
}
