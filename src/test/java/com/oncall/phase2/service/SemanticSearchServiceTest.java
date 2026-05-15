package com.oncall.phase2.service;

import com.oncall.common.config.MoonshotProperties;
import com.oncall.common.util.TextTokenizer;
import com.oncall.phase1.model.DocumentEntity;
import com.oncall.phase1.model.SearchResult;
import com.oncall.phase1.store.DocumentRepository;
import com.oncall.phase2.client.MoonshotEmbeddingClient;
import com.oncall.phase2.store.VectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticSearchServiceTest {

    @Test
    void apiSearch_shouldUseTextSignalsAndReturnTopKInsteadOfEveryPositiveVector() {
        DocumentRepository repository = new DocumentRepository();
        save(repository, "frontend", "前端Web On-Call SOP", "静态资源服务器 CORS ChunkLoadError 页面白屏 资源加载失败");
        save(repository, "backend", "后端服务 On-Call SOP", "后端服务 服务大面积超时 服务不可用 熔断 降级 核心服务 告警 可用性");
        save(repository, "sre", "SRE基础设施 On-Call SOP", "Kubernetes 集群 Pod 节点 Ingress 网关 API Server 不可用 基础设施故障 故障响应");
        for (int i = 0; i < 6; i++) {
            save(repository, "other-" + i, "无关 SOP " + i, "测试 发布 流水线 日志 报表 工单 质量 数据");
        }

        SemanticSearchService service = new SemanticSearchService(
                new FakeEmbeddingClient(),
                new VectorStore(),
                repository,
                new TextTokenizer()
        );

        List<SearchResult> results = service.search("服务器挂了");
        List<String> ids = results.stream().map(SearchResult::id).toList();

        assertThat(results).hasSizeLessThanOrEqualTo(5);
        assertThat(ids.subList(0, Math.min(2, ids.size())))
                .containsExactlyInAnyOrder("backend", "sre");
        assertThat(ids).doesNotContain("other-5");
    }

    private static void save(DocumentRepository repository, String id, String title, String text) {
        repository.save(new DocumentEntity(id, title, text, text, 1L));
    }

    private static class FakeEmbeddingClient extends MoonshotEmbeddingClient {
        FakeEmbeddingClient() {
            super(WebClient.builder().build(), new MoonshotProperties());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String disabledReason() {
            return "";
        }

        @Override
        public float[] embedSingle(String text) {
            return new float[]{1.0f, 0.0f, 0.0f};
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            return texts.stream()
                    .map(this::vectorForText)
                    .toList();
        }

        private float[] vectorForText(String text) {
            if (text.contains("静态资源服务器")) {
                return new float[]{1.0f, 0.0f, 0.0f};
            }
            if (text.contains("后端服务")) {
                return new float[]{0.95f, 0.05f, 0.0f};
            }
            if (text.contains("Kubernetes")) {
                return new float[]{0.70f, 0.30f, 0.0f};
            }
            return new float[]{0.20f, 0.98f, 0.0f};
        }
    }
}
