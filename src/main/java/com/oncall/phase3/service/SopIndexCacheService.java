package com.oncall.phase3.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oncall.phase1.model.DocumentEntity;
import com.oncall.phase3.model.SopIndex;
import com.oncall.phase3.model.SopScenario;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class SopIndexCacheService {
    private static final Logger log = LoggerFactory.getLogger(SopIndexCacheService.class);
    private static final String PARSER_VERSION = "sop-section-parser-v2";
    private static final String GLOBAL_INDEX_FILE = "sop_index.json";
    private static final int SECTION_PROMPT_LIMIT = 220;
    private static final Pattern STRUCTURED_BLOCK = Pattern.compile(
            "(?is)<(h2|h3|p|li|pre|code)\\b[^>]*>(.*?)(?=<h2\\b|<h3\\b|<p\\b|<li\\b|<pre\\b|<code\\b|</main>|</body>|</html>|$)"
    );
    private static final List<String> KNOWN_TERMS = List.of(
            "p0", "p1", "故障", "异常", "级别", "流程", "响应", "处理", "排查", "升级", "沟通", "复盘",
            "止血", "恢复", "回滚", "降级", "限流", "隔离", "告警", "监控", "指标",
            "oom", "outofmemory", "outofmemoryerror", "jvm", "pod", "kubernetes", "k8s",
            "数据库", "主从", "连接池", "延迟", "慢查询", "redis", "kafka", "积压",
            "白屏", "js", "cdn", "dns", "ddos", "安全", "攻击", "模型", "推荐", "搜索",
            "点击率", "相关性", "效果下降", "质量下降", "特征", "召回", "精排", "ab", "实验",
            "gpu", "推理", "app", "崩溃", "推送", "测试", "质量", "ci", "cd"
    );
    private static final Map<String, List<String>> TOPIC_ALIASES = Map.ofEntries(
            Map.entry("sop-001", List.of("后端", "backend", "Java服务", "服务超时", "接口超时", "OOM", "JVM", "数据库连接池", "Kafka积压")),
            Map.entry("sop-002", List.of("数据库", "DBA", "MySQL", "Redis", "主从延迟", "慢查询", "连接数暴涨", "备份恢复")),
            Map.entry("sop-003", List.of("前端", "Web", "白屏", "JS错误", "静态资源", "CORS", "页面性能")),
            Map.entry("sop-004", List.of("SRE", "基础设施", "Kubernetes", "K8s", "Etcd", "Ingress", "CI/CD", "云资源")),
            Map.entry("sop-005", List.of("安全", "信息安全", "DDoS", "SQL注入", "数据泄露", "恶意软件", "权限滥用")),
            Map.entry("sop-006", List.of("数据平台", "ETL", "Spark", "Flink", "Hive", "HDFS", "Kafka集群", "数据质量")),
            Map.entry("sop-007", List.of("移动", "客户端", "App", "iOS", "Android", "崩溃率", "推送", "应用商店", "OOM", "内存泄漏")),
            Map.entry("sop-008", List.of("AI算法", "算法", "模型", "推荐", "搜索", "特征", "AB实验", "GPU", "推荐质量下降")),
            Map.entry("sop-009", List.of("QA", "质量保障", "测试", "自动化测试", "测试环境", "性能测试", "线上Bug")),
            Map.entry("sop-010", List.of("网络", "CDN", "DNS", "负载均衡", "BGP", "跨区域网络", "带宽"))
    );

    private final ObjectMapper objectMapper;
    private final Path cacheDirectory;

    public SopIndexCacheService(
            ObjectMapper objectMapper,
            @Value("${oncall.phase3.sop-index.dir:data}") String cacheDir
    ) {
        this.objectMapper = objectMapper;
        this.cacheDirectory = Path.of(cacheDir).toAbsolutePath().normalize();
    }

    public void warmUp(List<DocumentEntity> documents) {
        if (documents == null || documents.isEmpty()) {
            log.info("v3 sop index warm-up skipped: no documents loaded");
            return;
        }
        ensureCacheDirectory();
        log.info("v3 sop index warm-up begin docs={} dir='{}'", documents.size(), cacheDirectory);
        long start = System.currentTimeMillis();
        int reused = 0;
        int generated = 0;
        int failed = 0;
        List<SopIndex> indexes = new ArrayList<>();
        for (DocumentEntity document : documents.stream()
                .sorted(Comparator.comparing(DocumentEntity::id))
                .toList()) {
            try {
                CacheResult result = ensureFreshIndex(document);
                indexes.add(result.index());
                if (result.reused()) {
                    reused++;
                } else {
                    generated++;
                }
            } catch (Exception ex) {
                failed++;
                log.warn("v3 sop index warm-up failed id={} reason={}", document.id(), ex.getMessage());
            }
        }
        try {
            writeGlobalIndex(indexes);
        } catch (Exception ex) {
            failed++;
            log.warn("v3 global sop index write failed reason={}", ex.getMessage());
        }
        log.info("v3 sop index warm-up done reused={} generated={} failed={} took={}ms",
                reused, generated, failed, System.currentTimeMillis() - start);
    }

    public void ensureGlobalIndex(List<DocumentEntity> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        List<SopIndex> indexes = documents.stream()
                .sorted(Comparator.comparing(DocumentEntity::id))
                .map(this::indexForPrompt)
                .toList();
        try {
            writeGlobalIndex(indexes);
        } catch (Exception ex) {
            log.debug("v3 global sop index on-demand write skipped reason={}", ex.getMessage());
        }
    }

    public Optional<SopIndex> findFreshIndex(DocumentEntity document) {
        if (document == null) {
            return Optional.empty();
        }
        try {
            Optional<SopIndex> cached = readCachedIndex(document.id());
            if (cached.isPresent() && isFresh(cached.get(), document)) {
                return cached;
            }
        } catch (Exception ex) {
            log.debug("v3 sop index read skipped id={} reason={}", document.id(), ex.getMessage());
        }
        return Optional.empty();
    }

    public SopIndex indexForPrompt(DocumentEntity document) {
        Optional<SopIndex> cached = findFreshIndex(document);
        if (cached.isPresent()) {
            return cached.get();
        }
        SopIndex index = parseIndex(document);
        try {
            writeIndex(index);
            log.info("v3 sop index generated on demand id={} file='{}'",
                    document.id(), index.indexFile());
        } catch (Exception ex) {
            log.debug("v3 sop index on-demand write skipped id={} reason={}", document.id(), ex.getMessage());
        }
        return index;
    }

    public double score(DocumentEntity document, String query) {
        if (document == null || query == null || query.isBlank()) {
            return 0;
        }
        SopIndex index = indexForPrompt(document);
        double score = 0;
        String normalizedQuery = normalize(query);
        String title = normalize(index.title());
        if (!normalizedQuery.isBlank() && title.contains(normalizedQuery)) {
            score += 10;
        }
        for (String alias : TOPIC_ALIASES.getOrDefault(index.id(), List.of())) {
            if (normalizedQuery.contains(normalize(alias))) {
                score += 8;
            }
        }
        for (String term : queryTerms(query)) {
            String normalizedTerm = normalize(term);
            if (normalizedTerm.isBlank()) {
                continue;
            }
            if (title.contains(normalizedTerm)) {
                score += 6;
            }
            score += sectionScore(index.duty(), normalizedTerm, 1.5);
            score += sectionScore(index.metrics(), normalizedTerm, 2.0);
            score += sectionScore(index.escalation(), normalizedTerm, 3.0);
            score += sectionScore(index.forbidden(), normalizedTerm, 2.5);
            score += sectionScore(index.commands(), normalizedTerm, 1.5);
            for (SopScenario scenario : safeScenarios(index)) {
                if (normalize(scenario.name()).contains(normalizedTerm)) {
                    score += 8;
                }
                if (normalize(String.join(" ", safeList(scenario.keywords()))).contains(normalizedTerm)) {
                    score += 6;
                }
                if (normalize(scenario.content()).contains(normalizedTerm)) {
                    score += 4;
                }
            }
        }
        return score;
    }

    public String promptBlock(SopIndex index) {
        StringBuilder block = new StringBuilder();
        block.append(index.filename()).append(": ").append(blankToUnknown(index.title())).append("\n");
        block.append("  sopIndex: ").append(index.indexFile()).append("\n");
        block.append("  moduleFiles: ")
                .append(String.join(", ", List.of(
                        index.dutyFile(),
                        index.metricsFile(),
                        index.troubleshootingIndexFile(),
                        index.escalationFile(),
                        index.forbiddenFile(),
                        index.commandsFile()
                )))
                .append("\n");
        appendLine(block, "duty", index.duty());
        appendLine(block, "metrics", index.metrics());
        if (!safeScenarios(index).isEmpty()) {
            block.append("  scenarios:\n");
            for (SopScenario scenario : safeScenarios(index).stream().limit(6).toList()) {
                block.append("    - ").append(limitText(scenario.name(), 60))
                        .append(" | file: ").append(scenario.file());
                if (scenario.keywords() != null && !scenario.keywords().isEmpty()) {
                    block.append(" | keywords: ")
                            .append(scenario.keywords().stream().limit(8).collect(Collectors.joining(", ")));
                }
                block.append(" | excerpt: ").append(limitText(scenario.content(), 140)).append("\n");
            }
        }
        appendLine(block, "escalation", index.escalation());
        appendLine(block, "forbidden", index.forbidden());
        appendLine(block, "commands", index.commands());
        return block.toString();
    }

    private CacheResult ensureFreshIndex(DocumentEntity document) throws IOException {
        Optional<SopIndex> cached = readCachedIndex(document.id());
        if (cached.isPresent() && isFresh(cached.get(), document)) {
            log.info("v3 sop index cache hit id={} indexFile={}", document.id(), cached.get().indexFile());
            return new CacheResult(cached.get(), true);
        }
        SopIndex index = parseIndex(document);
        writeIndex(index);
        log.info("v3 sop index cache write id={} indexFile='{}' scenarios={}",
                document.id(), index.indexFile(), safeScenarios(index).size());
        return new CacheResult(index, false);
    }

    private SopIndex parseIndex(DocumentEntity documentEntity) {
        String htmlSource = documentEntity.html() == null ? "" : documentEntity.html();
        org.jsoup.nodes.Document html = Jsoup.parse(htmlSource);
        String title = firstNonBlank(textOf(html.selectFirst("h1")), documentEntity.title(), documentEntity.id());
        Map<String, StringBuilder> sections = new LinkedHashMap<>();
        sections.put("duty", new StringBuilder());
        sections.put("metrics", new StringBuilder());
        sections.put("escalation", new StringBuilder());
        sections.put("forbidden", new StringBuilder());
        sections.put("commands", new StringBuilder());

        List<SopScenario> scenarios = new ArrayList<>();
        String currentSection = "";
        String currentScenarioName = "";
        String currentScenarioFile = "";
        StringBuilder currentScenarioContent = new StringBuilder();
        int scenarioCount = 0;

        for (HtmlBlock block : extractBlocks(htmlSource)) {
            String tag = block.tag();
            String text = cleanText(block.text());
            if (text.isBlank()) {
                continue;
            }
            if ("h2".equals(tag)) {
                flushScenario(scenarios, currentScenarioName, currentScenarioFile, currentScenarioContent);
                currentScenarioName = "";
                currentScenarioFile = "";
                currentScenarioContent = new StringBuilder();
                currentSection = canonicalSection(text);
                continue;
            }
            if ("h3".equals(tag)) {
                if ("scenarios".equals(currentSection)) {
                    flushScenario(scenarios, currentScenarioName, currentScenarioFile, currentScenarioContent);
                    scenarioCount++;
                    currentScenarioName = text;
                    currentScenarioFile = scenarioFile(documentEntity.id(), scenarioCount, text);
                    currentScenarioContent = new StringBuilder();
                } else if (sections.containsKey(currentSection)) {
                    appendText(sections.get(currentSection), text);
                }
                continue;
            }
            if ("scenarios".equals(currentSection) && !currentScenarioName.isBlank()) {
                appendText(currentScenarioContent, text);
            } else if (sections.containsKey(currentSection)) {
                appendText(sections.get(currentSection), text);
            }
        }
        flushScenario(scenarios, currentScenarioName, currentScenarioFile, currentScenarioContent);

        String id = documentEntity.id();
        return new SopIndex(
                id,
                id + ".html",
                title,
                sourceHash(documentEntity),
                PARSER_VERSION,
                Instant.now().toString(),
                id + "/index.json",
                id + "/01_duties.md",
                cleanText(sections.get("duty").toString()),
                id + "/02_metrics.md",
                cleanText(sections.get("metrics").toString()),
                id + "/03_troubleshooting.index.md",
                scenarios,
                id + "/04_escalation.md",
                cleanText(sections.get("escalation").toString()),
                id + "/05_forbidden.md",
                cleanText(sections.get("forbidden").toString()),
                id + "/06_commands.md",
                cleanText(sections.get("commands").toString())
        );
    }

    private List<HtmlBlock> extractBlocks(String htmlSource) {
        Matcher matcher = STRUCTURED_BLOCK.matcher(htmlSource == null ? "" : htmlSource);
        List<HtmlBlock> blocks = new ArrayList<>();
        while (matcher.find()) {
            String tag = matcher.group(1).toLowerCase(Locale.ROOT);
            String fragment = matcher.group(2);
            blocks.add(new HtmlBlock(tag, cleanHtmlText(fragment)));
        }
        return blocks;
    }

    private void flushScenario(List<SopScenario> scenarios, String name, String file, StringBuilder content) {
        if (name == null || name.isBlank()) {
            return;
        }
        String scenarioContent = cleanText(content.toString());
        scenarios.add(new SopScenario(name, file, scenarioContent, keywords(name + " " + scenarioContent)));
    }

    private void writeIndex(SopIndex index) throws IOException {
        ensureCacheDirectory();
        writeJson(index.indexFile(), index);
        writeText(index.dutyFile(), markdown("一、值班职责", index.duty()));
        writeText(index.metricsFile(), markdown("二、监控指标", index.metrics()));
        writeText(index.troubleshootingIndexFile(), troubleshootingIndexMarkdown(index));
        for (SopScenario scenario : safeScenarios(index)) {
            writeText(scenario.file(), markdown(scenario.name(), scenario.content()));
        }
        writeText(index.escalationFile(), markdown("四、升级流程", index.escalation()));
        writeText(index.forbiddenFile(), markdown("五、禁止操作", index.forbidden()));
        writeText(index.commandsFile(), markdown("六、工具与命令参考", index.commands()));
    }

    private void writeGlobalIndex(List<SopIndex> indexes) throws IOException {
        ensureCacheDirectory();
        List<Map<String, Object>> sops = indexes.stream()
                .sorted(Comparator.comparing(SopIndex::id))
                .map(this::globalSopEntry)
                .toList();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("description", "全局 SOP 入口索引。Agent 应先读取本文件，再按 topic/module/scenario 读取 data/sop-xxx/ 下的具体索引或模块文件。");
        root.put("globalIntents", Map.of(
                "incident_response", Map.of(
                        "aliases", List.of("故障响应流程", "告警处理流程", "事故处理", "P0流程", "升级流程"),
                        "preferredModules", List.of("01_duties.md", "04_escalation.md", "05_forbidden.md")
                ),
                "vague_service_issue", Map.of(
                        "aliases", List.of("服务异常", "服务故障", "系统异常", "线上问题"),
                        "preferredModules", List.of("03_troubleshooting.index.md")
                )
        ));
        root.put("sops", sops);
        writeJson(GLOBAL_INDEX_FILE, root);
    }

    private Map<String, Object> globalSopEntry(SopIndex index) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", index.id());
        entry.put("title", index.title());
        entry.put("topic", topicFromTitle(index.title()));
        entry.put("aliases", TOPIC_ALIASES.getOrDefault(index.id(), List.of()));
        entry.put("originalFile", index.filename());
        entry.put("indexFile", index.indexFile());
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("duties", Map.of("title", "一、值班职责", "file", index.dutyFile()));
        sections.put("metrics", Map.of("title", "二、监控指标", "file", index.metricsFile()));
        sections.put("troubleshooting", Map.of(
                "title", "三、常见故障处理",
                "indexFile", index.troubleshootingIndexFile(),
                "scenarios", safeScenarios(index).stream().map(this::scenarioEntry).toList()
        ));
        sections.put("escalation", Map.of("title", "四、升级流程", "file", index.escalationFile()));
        sections.put("forbidden", Map.of("title", "五、禁止操作", "file", index.forbiddenFile()));
        sections.put("commands", Map.of("title", "六、工具与命令参考", "file", index.commandsFile()));
        entry.put("sections", sections);
        return entry;
    }

    private Map<String, Object> scenarioEntry(SopScenario scenario) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("title", scenario.name());
        entry.put("keywords", safeList(scenario.keywords()));
        entry.put("file", scenario.file());
        entry.put("summary", limitText(scenario.content(), 120));
        return entry;
    }

    private String troubleshootingIndexMarkdown(SopIndex index) {
        StringBuilder builder = new StringBuilder();
        builder.append("# 三、常见故障处理索引\n\n");
        if (safeScenarios(index).isEmpty()) {
            builder.append("_暂无常见故障场景。_\n");
            return builder.toString();
        }
        for (SopScenario scenario : safeScenarios(index)) {
            builder.append("- ").append(scenario.name())
                    .append("：文件 `").append(scenario.file()).append("`");
            if (scenario.keywords() != null && !scenario.keywords().isEmpty()) {
                builder.append("；关键词：").append(String.join("、", scenario.keywords()));
            }
            if (scenario.content() != null && !scenario.content().isBlank()) {
                builder.append("；摘要：").append(limitText(scenario.content(), 100));
            }
            builder.append("\n");
        }
        return builder.toString();
    }

    private void writeJson(String relativePath, Object value) throws IOException {
        Path target = resolveDataPath(relativePath);
        Files.createDirectories(target.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), value);
    }

    private void writeText(String relativePath, String content) throws IOException {
        Path target = resolveDataPath(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private Optional<SopIndex> readCachedIndex(String id) throws IOException {
        Path path = cachePath(id);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        return Optional.of(objectMapper.readValue(path.toFile(), SopIndex.class));
    }

    private boolean isFresh(SopIndex index, DocumentEntity document) {
        return PARSER_VERSION.equals(index.parserVersion())
                && sourceHash(document).equals(index.sourceHash())
                && generatedFilesExist(index);
    }

    private boolean generatedFilesExist(SopIndex index) {
        List<String> files = new ArrayList<>();
        files.add(index.indexFile());
        files.add(index.dutyFile());
        files.add(index.metricsFile());
        files.add(index.troubleshootingIndexFile());
        files.add(index.escalationFile());
        files.add(index.forbiddenFile());
        files.add(index.commandsFile());
        for (SopScenario scenario : safeScenarios(index)) {
            files.add(scenario.file());
        }
        return files.stream()
                .filter(file -> file != null && !file.isBlank())
                .allMatch(file -> Files.isRegularFile(resolveDataPath(file)));
    }

    private Path cachePath(String id) {
        return cacheDirectory.resolve(id).resolve("index.json").normalize();
    }

    private Path resolveDataPath(String relativePath) {
        Path target = cacheDirectory.resolve(relativePath).normalize();
        if (!target.startsWith(cacheDirectory)) {
            throw new IllegalArgumentException("generated SOP path must stay inside data/: " + relativePath);
        }
        return target;
    }

    private void ensureCacheDirectory() {
        try {
            Files.createDirectories(cacheDirectory);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to create SOP index cache directory: " + cacheDirectory, ex);
        }
    }

    private String markdown(String title, String content) {
        return "# " + title + "\n\n" + (content == null || content.isBlank() ? "_暂无内容_" : content.trim()) + "\n";
    }

    private String scenarioFile(String id, int scenarioNumber, String name) {
        return "%s/03_%02d_%s.md".formatted(id, scenarioNumber, scenarioSlug(name));
    }

    private String scenarioSlug(String name) {
        String normalized = normalize(name);
        List<SlugRule> rules = List.of(
                new SlugRule("服务大面积超时", "service_timeout"),
                new SlugRule("单服务oom崩溃", "oom"),
                new SlugRule("数据库连接池耗尽", "db_pool_exhausted"),
                new SlugRule("消息队列积压", "kafka_backlog"),
                new SlugRule("配置变更导致服务异常", "config_change"),
                new SlugRule("第三方接口故障", "third_party_api"),
                new SlugRule("主从复制中断", "replication_interruption"),
                new SlugRule("数据库连接数暴涨", "db_connections_spike"),
                new SlugRule("磁盘空间告急", "disk_space"),
                new SlugRule("大事务阻塞", "large_transaction_blocking"),
                new SlugRule("redis内存溢出", "redis_oom"),
                new SlugRule("白屏页面无法加载", "blank_page"),
                new SlugRule("js错误率突增", "js_errors"),
                new SlugRule("页面加载性能下降", "page_performance"),
                new SlugRule("样式错乱布局异常", "layout_issue"),
                new SlugRule("接口跨域cors错误", "cors_error"),
                new SlugRule("kubernetes节点notready", "kubernetes_node_not_ready"),
                new SlugRule("etcd集群异常", "etcd_cluster"),
                new SlugRule("ingress网关过载", "ingress_overload"),
                new SlugRule("cicd流水线大面积失败", "cicd_pipeline_failure"),
                new SlugRule("云资源配额不足", "cloud_quota"),
                new SlugRule("sql注入攻击", "sql_injection"),
                new SlugRule("数据泄露事件", "data_leak"),
                new SlugRule("恶意软件感染", "malware"),
                new SlugRule("内部权限滥用", "privilege_abuse"),
                new SlugRule("离线任务大面积失败", "offline_jobs_failure"),
                new SlugRule("flink实时任务异常", "flink_job_failure"),
                new SlugRule("hdfs存储异常", "hdfs_storage"),
                new SlugRule("数据质量问题", "data_quality"),
                new SlugRule("kafka集群故障", "kafka_cluster"),
                new SlugRule("崩溃率突增", "crash_rate"),
                new SlugRule("网络请求异常", "network_request"),
                new SlugRule("内存泄漏导致oom", "mobile_oom"),
                new SlugRule("推送服务异常", "push_service"),
                new SlugRule("应用商店审核被拒", "app_store_review"),
                new SlugRule("模型推理延迟突增", "inference_latency"),
                new SlugRule("模型效果下降", "model_quality_drop"),
                new SlugRule("gpu集群资源不足", "gpu_resource"),
                new SlugRule("特征服务故障", "feature_service"),
                new SlugRule("ab实验异常", "ab_experiment"),
                new SlugRule("自动化测试大面积失败", "automated_tests_failure"),
                new SlugRule("测试环境不可用", "test_env_unavailable"),
                new SlugRule("性能测试环境异常", "performance_test_env"),
                new SlugRule("测试数据问题", "test_data"),
                new SlugRule("线上bug紧急验证", "online_bug_verification"),
                new SlugRule("cdn节点故障", "cdn_node"),
                new SlugRule("dns解析异常", "dns_resolution"),
                new SlugRule("跨区域网络延迟", "cross_region_latency"),
                new SlugRule("负载均衡器故障", "load_balancer"),
                new SlugRule("ddos攻击导致带宽打满", "bandwidth_ddos"),
                new SlugRule("ddos攻击", "ddos_attack")
        );
        for (SlugRule rule : rules) {
            if (normalized.contains(normalize(rule.probe()))) {
                return rule.slug();
            }
        }
        Matcher matcher = Pattern.compile("[A-Za-z][A-Za-z0-9]+").matcher(name == null ? "" : name);
        List<String> parts = new ArrayList<>();
        while (matcher.find() && parts.size() < 4) {
            parts.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return parts.isEmpty() ? "scenario" : String.join("_", parts);
    }

    private List<String> keywords(String text) {
        String normalized = normalize(text);
        List<String> values = new ArrayList<>();
        for (String term : KNOWN_TERMS) {
            if (normalized.contains(normalize(term)) && !values.contains(term)) {
                values.add(term);
            }
        }
        Matcher matcher = Pattern
                .compile("[A-Za-z][A-Za-z0-9+#._/-]{1,}")
                .matcher(text == null ? "" : text);
        while (matcher.find() && values.size() < 14) {
            String term = matcher.group();
            if (!values.contains(term)) {
                values.add(term);
            }
        }
        return values.stream().limit(14).toList();
    }

    private List<String> queryTerms(String query) {
        String normalizedQuery = normalize(query);
        List<String> terms = new ArrayList<>();
        for (String term : KNOWN_TERMS) {
            if (normalizedQuery.contains(normalize(term)) && !terms.contains(term)) {
                terms.add(term);
            }
        }
        String[] parts = query.toLowerCase(Locale.ROOT)
                .split("[\\s,，.。:：;；!?！？()（）\\[\\]{}<>《》\"'、/\\\\]+");
        for (String part : parts) {
            String term = part == null ? "" : part.trim();
            if (term.length() >= 2 && !terms.contains(term)) {
                terms.add(term);
            }
        }
        return terms;
    }

    private double sectionScore(String content, String normalizedTerm, double weight) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return normalize(content).contains(normalizedTerm) ? weight : 0;
    }

    private String canonicalSection(String value) {
        String normalized = normalize(value);
        String lower = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (List.of("duty", "metrics", "scenarios", "escalation", "forbidden", "commands").contains(lower)) {
            return lower;
        }
        if (normalized.contains("值班职责")) {
            return "duty";
        }
        if (normalized.contains("监控指标")) {
            return "metrics";
        }
        if (normalized.contains("常见故障") || normalized.contains("故障处理") || normalized.contains("场景")) {
            return "scenarios";
        }
        if (normalized.contains("升级流程") || normalized.contains("升级")) {
            return "escalation";
        }
        if (normalized.contains("禁止操作") || normalized.contains("禁止")) {
            return "forbidden";
        }
        if (normalized.contains("工具") || normalized.contains("命令")) {
            return "commands";
        }
        return "";
    }

    private void appendLine(StringBuilder builder, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        builder.append("  ").append(label).append(": ")
                .append(limitText(value.replace("\n", " "), SECTION_PROMPT_LIMIT))
                .append("\n");
    }

    private void appendText(StringBuilder builder, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(" ");
        }
        builder.append(text.trim());
    }

    private String textOf(Element element) {
        return element == null ? "" : cleanText(element.text());
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "(unknown)";
    }

    private String sourceHash(DocumentEntity document) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = (document.html() == null ? "" : document.html()).getBytes(StandardCharsets.UTF_8);
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private String cleanHtmlText(String value) {
        if (value == null) {
            return "";
        }
        String withBreaks = value.replaceAll("(?i)<br\\s*/?>", " ");
        return cleanText(Jsoup.parse(withBreaks).text());
    }

    private String cleanText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s,，.。:：;；!?！？()（）\\[\\]{}<>《》\"'、/\\\\一二三四五六七八九十0-9&#;-]+", "")
                .trim();
    }

    private String limitText(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "(unknown)" : value.trim();
    }

    private String topicFromTitle(String title) {
        String value = title == null ? "" : title;
        return value.replace("On-Call SOP", "")
                .replace("On&#45;Call SOP", "")
                .replace("& 故障处理指南", "")
                .replace("&amp; 故障处理指南", "")
                .replace("与故障处理指南", "")
                .trim();
    }

    private List<SopScenario> safeScenarios(SopIndex index) {
        return index == null || index.scenarios() == null ? List.of() : index.scenarios();
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private record HtmlBlock(String tag, String text) {
    }

    private record SlugRule(String probe, String slug) {
    }

    private record CacheResult(SopIndex index, boolean reused) {
    }
}
