package com.oncall.phase2.client;

import com.oncall.common.config.MoonshotProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MoonshotEmbeddingClientTest {

    @Test
    void embedBatch_shouldUseAliyunCompatibleEndpointAndSplitLargeBatches() {
        List<URI> seenUris = new ArrayList<>();
        AtomicInteger call = new AtomicInteger();
        ExchangeFunction exchangeFunction = request -> {
            seenUris.add(request.url());
            int batchSize = call.getAndIncrement() == 0 ? 10 : 2;
            return Mono.just(okJson(embeddingResponse(batchSize)));
        };
        MoonshotEmbeddingClient client = new MoonshotEmbeddingClient(webClient(exchangeFunction), properties());

        List<float[]> vectors = client.embedBatch(List.of(
                "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"
        ));

        assertThat(seenUris)
                .extracting(URI::toString)
                .containsExactly(
                        "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings"
                );
        assertThat(vectors).hasSize(12);
        assertThat(call.get()).isEqualTo(2);
    }

    private WebClient webClient(ExchangeFunction exchangeFunction) {
        return WebClient.builder()
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .exchangeFunction(exchangeFunction)
                .build();
    }

    private MoonshotProperties properties() {
        MoonshotProperties properties = new MoonshotProperties();
        properties.setEmbeddingProvider("aliyun");
        properties.setEmbeddingBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        properties.setEmbeddingApiKey("test-key");
        properties.setEmbeddingModel("text-embedding-v3");
        return properties;
    }

    private ClientResponse okJson(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body(body)
                .build();
    }

    private String embeddingResponse(int count) {
        StringBuilder builder = new StringBuilder("{\"data\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append("{\"index\":")
                    .append(i)
                    .append(",\"embedding\":[1.0,0.0]}");
        }
        return builder.append("]}").toString();
    }
}
