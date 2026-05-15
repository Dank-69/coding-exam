package com.oncall.phase2;

import com.oncall.common.config.MoonshotProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "moonshot.embedding-provider=aliyun",
        "moonshot.embedding-base-url=https://dashscope.aliyuncs.com/compatible-mode/v1",
        "moonshot.embedding-api-key=test-key",
        "moonshot.embedding-model=text-embedding-v3"
})
class EmbeddingPropertiesTest {

    @Autowired
    private MoonshotProperties props;

    @Test
    void embeddingProperties_shouldBeBound() {
        System.out.println("=== MoonshotProperties binding check ===");
        System.out.println("provider: " + props.provider());
        System.out.println("embeddingProvider: " + props.embeddingProvider());
        System.out.println("baseUrl: " + props.baseUrl());
        System.out.println("apiKey: " + (props.apiKey() == null ? "null" : "[present, length=" + props.apiKey().length() + "]"));
        System.out.println("embeddingBaseUrl: " + props.embeddingBaseUrl());
        System.out.println("embeddingApiKey: " + (props.embeddingApiKey() == null ? "null" : "[present, length=" + props.embeddingApiKey().length() + "]"));
        System.out.println("effectiveEmbeddingApiKey: " + (props.effectiveEmbeddingApiKey() == null ? "null" : "[present, length=" + props.effectiveEmbeddingApiKey().length() + "]"));
        System.out.println("embeddingModel: " + props.embeddingModel());
        System.out.println("chatModel: " + props.chatModel());
        System.out.println("embeddingsEnabled: " + props.embeddingsEnabled());
        System.out.println("hasEmbeddingApiKey: " + props.hasEmbeddingApiKey());
        System.out.println("supportsEmbeddings: " + props.supportsEmbeddings());

        assertThat(props.embeddingProviderName()).isEqualTo("aliyun");
        assertThat(props.embeddingBaseUrl()).isNotNull();
        assertThat(props.embeddingApiKey()).isNotNull().isNotBlank();
        assertThat(props.hasEmbeddingApiKey()).isTrue();
        assertThat(props.embeddingsEnabled()).isTrue();
    }
}
