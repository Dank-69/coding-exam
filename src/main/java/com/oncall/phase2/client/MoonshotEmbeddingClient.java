package com.oncall.phase2.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.oncall.common.config.MoonshotProperties;
import com.oncall.common.exception.ExternalServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class MoonshotEmbeddingClient {
    private static final Logger log = LoggerFactory.getLogger(MoonshotEmbeddingClient.class);
    private static final int MAX_BATCH_SIZE = 10;

    private final WebClient aliyunWebClient;
    private final MoonshotProperties properties;

    public MoonshotEmbeddingClient(@Qualifier("aliyunWebClient") WebClient aliyunWebClient, MoonshotProperties properties) {
        this.aliyunWebClient = aliyunWebClient;
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.hasEmbeddingApiKey() && properties.supportsEmbeddings();
    }

    public String disabledReason() {
        if (!properties.hasEmbeddingApiKey()) {
            return "API key missing";
        }
        if (!properties.supportsEmbeddings()) {
            return "embedding disabled for provider=" + properties.embeddingProviderName();
        }
        return "";
    }

    public float[] embedSingle(String text) {
        List<float[]> vectors = embedBatch(List.of(text));
        if (vectors.isEmpty()) {
            throw new ExternalServiceException("Embedding response is empty");
        }
        return vectors.get(0);
    }

    public List<float[]> embedBatch(List<String> texts) {
        if (!isEnabled()) {
            throw new ExternalServiceException("Embedding client disabled: " + disabledReason());
        }
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        List<float[]> vectors = new ArrayList<>();
        for (int from = 0; from < texts.size(); from += MAX_BATCH_SIZE) {
            int to = Math.min(texts.size(), from + MAX_BATCH_SIZE);
            vectors.addAll(requestBatch(texts.subList(from, to), texts.size()));
        }
        return vectors;
    }

    private List<float[]> requestBatch(List<String> texts, int totalSize) {
        long start = System.currentTimeMillis();
        log.info("embeddings request provider={} baseUrl={} model={} batch={}/{}",
                properties.embeddingProviderName(), properties.effectiveEmbeddingBaseUrl(), properties.embeddingModel(), texts.size(), totalSize);
        JsonNode response = aliyunWebClient.post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", properties.embeddingModel(),
                        "input", texts,
                        "encoding_format", "float"
                ))
                .retrieve()
                .onStatus(status -> status.isError(), clientResponse ->
                        clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> Mono.error(new ExternalServiceException("Embedding API failed: " + body)))
                )
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(Math.max(1, properties.requestTimeoutSeconds())))
                .onErrorMap(ex -> ex instanceof ExternalServiceException
                        ? ex
                        : new ExternalServiceException("Embedding API request failed", ex))
                .block();

        if (response == null || !response.has("data") || !response.get("data").isArray()) {
            throw new ExternalServiceException("Embedding API response missing data field");
        }

        List<float[]> ordered = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            ordered.add(null);
        }

        for (JsonNode row : response.get("data")) {
            int index = row.path("index").asInt(-1);
            if (index < 0 || index >= texts.size()) {
                throw new ExternalServiceException("Embedding API returned invalid row index");
            }
            JsonNode embeddingNode = row.path("embedding");
            if (!embeddingNode.isArray() || embeddingNode.isEmpty()) {
                continue;
            }
            float[] vector = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                vector[i] = (float) embeddingNode.get(i).asDouble();
            }
            if (ordered.get(index) != null) {
                throw new ExternalServiceException("Embedding API returned duplicate row index");
            }
            ordered.set(index, vector);
        }

        List<float[]> vectors = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            float[] vector = ordered.get(i);
            if (vector == null) {
                throw new ExternalServiceException("Embedding API returned incomplete batch");
            }
            vectors.add(vector);
        }
        if (vectors.isEmpty()) {
            throw new ExternalServiceException("Embedding API returned empty vectors");
        }
        if (vectors.size() != texts.size()) {
            throw new ExternalServiceException("Embedding API returned unexpected vector count");
        }
        log.info("embeddings ok provider={} baseUrl={} model={} batch={}/{} dim={} took={}ms",
                properties.embeddingProviderName(), properties.effectiveEmbeddingBaseUrl(), properties.embeddingModel(),
                texts.size(), totalSize, vectors.get(0).length, System.currentTimeMillis() - start);
        return vectors;
    }
}
