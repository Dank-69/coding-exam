package com.oncall.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "moonshot")
public class MoonshotProperties {
    private String provider = "moonshot";
    private String baseUrl = "https://api.moonshot.cn/v1";
    private String apiKey;
    private String embeddingProvider;
    private String embeddingBaseUrl;
    private String embeddingApiKey;
    private String embeddingModel;
    private String chatModel = "kimi-k2.6";
    private boolean embeddingsEnabled = true;
    private int requestTimeoutSeconds = 60;
    private int maxToolRounds = 4;

    public String provider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String baseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String apiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String embeddingProvider() { return embeddingProvider; }
    public void setEmbeddingProvider(String embeddingProvider) { this.embeddingProvider = embeddingProvider; }

    public String embeddingBaseUrl() { return embeddingBaseUrl; }
    public void setEmbeddingBaseUrl(String embeddingBaseUrl) { this.embeddingBaseUrl = embeddingBaseUrl; }

    public String embeddingApiKey() { return embeddingApiKey; }
    public void setEmbeddingApiKey(String embeddingApiKey) { this.embeddingApiKey = embeddingApiKey; }

    public String embeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public String chatModel() { return chatModel; }
    public void setChatModel(String chatModel) { this.chatModel = chatModel; }

    public boolean embeddingsEnabled() { return embeddingsEnabled; }
    public void setEmbeddingsEnabled(boolean embeddingsEnabled) { this.embeddingsEnabled = embeddingsEnabled; }

    public int requestTimeoutSeconds() { return requestTimeoutSeconds; }
    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) { this.requestTimeoutSeconds = requestTimeoutSeconds; }

    public int maxToolRounds() { return maxToolRounds; }
    public void setMaxToolRounds(int maxToolRounds) { this.maxToolRounds = maxToolRounds; }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean hasEmbeddingApiKey() {
        return effectiveEmbeddingApiKey() != null && !effectiveEmbeddingApiKey().isBlank();
    }

    public String effectiveEmbeddingApiKey() {
        if (embeddingApiKey != null && !embeddingApiKey.isBlank()) {
            return embeddingApiKey;
        }
        return apiKey;
    }

    public String effectiveEmbeddingBaseUrl() {
        if (embeddingBaseUrl != null && !embeddingBaseUrl.isBlank()) {
            return embeddingBaseUrl;
        }
        return baseUrl;
    }

    public String providerName() {
        return (provider == null || provider.isBlank()) ? "moonshot" : provider.trim().toLowerCase();
    }

    public String embeddingProviderName() {
        return (embeddingProvider == null || embeddingProvider.isBlank())
                ? providerName()
                : embeddingProvider.trim().toLowerCase();
    }

    public boolean supportsEmbeddings() {
        return embeddingsEnabled;
    }
}
