# On-Call Assistant 技术设计说明

## 1. 项目目标

本项目用于完成 On-Call 助手编程题，目标是围绕 `data/` 目录下的 SOP 文档实现三阶段能力：

1. `v1`：基于关键词的搜索
2. `v2`：基于 embedding 和文本信号的语义搜索
3. `v3`：基于 `readFile` 工具的 Agent 对话

三个阶段保留独立路由，便于逐阶段验收。

## 2. 总体架构

```text
Browser
  -> Thymeleaf pages (/ /v1 /v2 /v3)
  -> Spring Boot Controllers
     -> Phase 1: document parse + inverted index
     -> Phase 2: semantic ranking + vector cache
     -> Phase 3: agent routing + readFile + SSE
  -> data/*.html
  -> data/sop_index.json + data/sop-xxx/*.md
```

核心原则：

- 先保证题目功能可验收，再增强效果
- Phase 1/2/3 职责分层，避免互相污染
- 外部 AI 调用失败时允许优雅降级
- Agent 只暴露一个文件读取工具，并收紧路径安全边界

## 3. 模块划分

### 3.1 common

- `MoonshotProperties`：统一读取 AI/embedding 配置
- `HttpClientConfig`：外部 HTTP 客户端配置
- `GlobalExceptionHandler`：统一错误返回
- `HtmlParser`、`TextTokenizer`：HTML 解析与分词工具
- `HomeController`：`/` 首页入口

### 3.2 phase1

- `Phase1Controller`
- `DocumentService`
- `KeywordSearchService`
- `InvertedIndex`
- `DocumentRepository`

职责：

- 接收 HTML 文档
- 提取标题与正文
- 构建倒排索引
- 执行关键词检索并返回摘要片段

### 3.3 phase2

- `Phase2Controller`
- `SemanticSearchService`
- `MoonshotEmbeddingClient`
- `VectorStore`
- `VectorWarmupRunner`

职责：

- 对查询和文档分块做 embedding
- 结合 cosine similarity 与文本信号混合排序
- 启动时预热文档向量
- embedding 不可用时退化到本地语义排序

### 3.4 phase3

- `Phase3Controller`
- `AgentService`
- `ReadFileTool`
- `SopIndexCacheService`
- `SopIndexWarmupRunner`
- `DataDirectorySopSyncScheduler`

职责：

- 提供 JSON 与 SSE 两种对话接口
- 将原始 SOP HTML 结构化为索引与模块 Markdown
- 约束 Agent 从 `sop_index.json` 开始逐步定位
- 展示工具调用链
- 支持本地 fallback Agent

## 4. 数据流设计

### 4.1 文档导入流程

```text
data/*.html
  -> DataBootstrapLoader
  -> DocumentService.upsert()
  -> HtmlParser
  -> DocumentRepository
  -> InvertedIndex
```

说明：

- 启动时自动导入 `data/*.html`
- `DocumentRepository` 为内存存储
- `InvertedIndex` 记录 token 到文档的映射

### 4.2 SOP 模块化流程

```text
原始 HTML
  -> SopIndexCacheService.parseIndex()
  -> sop_index.json
  -> sop-xxx/index.json
  -> 01_duties.md ~ 06_commands.md
  -> 03_xx_*.md 场景文件
```

这样做的原因：

- 降低 Agent 直接读取整份 HTML 的不稳定性
- 让工具调用路径固定、可测试、可追踪
- 支持 follow-up 场景只读取必要模块

## 5. Phase 1 设计

### 5.1 检索策略

- HTML 解析后只索引正文
- 显式排除 `script/style`
- 保留 `&` 等特殊字符
- 使用分词结果构建倒排索引
- 检索时按 token 累积分数并排序

### 5.2 输出

返回字段：

- `id`
- `title`
- `snippet`
- `score`
- `took`

### 5.3 设计取舍

- 使用内存索引而不是外部搜索引擎
- 优先满足题目验证用例，而非做复杂全文检索平台

## 6. Phase 2 设计

### 6.1 混合排序

当前实现不是单纯 embedding 排序，而是混合两类信号：

- 向量相似度：query vector 与文档 chunk vectors 的最大 cosine similarity
- 文本信号：BM25 风格词频、标题权重、短语命中、领域概念扩展

最终通过加权方式生成结果分数。

### 6.2 降级策略

以下情况会自动走本地 fallback：

- 未配置 embedding key
- 配置显式关闭 embedding
- embedding 返回空向量或调用失败
- API 排序结果为空

### 6.3 预热策略

- 启动后遍历当前文档
- 将文档切块并生成向量
- 结果存入 `VectorStore`

作用：

- 减少首次查询冷启动开销
- 提高 Phase 2 演示稳定性

## 7. Phase 3 设计

### 7.1 接口设计

- `GET /v3`：页面
- `GET /v3/chat`：SSE
- `POST /v3/chat/stream`：SSE
- `POST /v3/chat`：JSON

### 7.2 Agent 路由策略

`AgentService` 内部结合多种信号做候选 SOP 选择：

- 明确关键词强匹配
- Phase 2 语义检索结果
- Phase 1 关键词检索结果
- `SopIndexCacheService.score()` 的结构化索引分
- 对 follow-up 问题复用历史上下文主题

### 7.3 工具限制

唯一工具：`readFile`

限制规则：

- 只能读取 `data/` 下文件
- 禁止 `..`
- 禁止绝对路径
- 禁止 `*`、`?`
- 仅允许安全字符集

这样可满足题目“只能按文件名读取、不能列目录、不能通配”的要求。

### 7.4 对话追踪

SSE 事件类型：

- `thinking`
- `tool_call`
- `tool_result`
- `message`
- `done`
- `error`

页面会实时显示：

- 助手回答
- 工具调用链
- 工具返回片段

### 7.5 fallback Agent

当 Chat API 不可用时，系统不会直接报错，而是：

- 先用本地候选选择策略定位 SOP
- 再按规则读取 `sop_index.json`、`sop-xxx/index.json` 和模块文件
- 组装基于证据的回答

这样 Phase 3 即使离线也可以演示核心能力。

## 8. 自动同步设计

`DataDirectorySopSyncScheduler` 定时扫描 `data/`：

- 新增或变更的 HTML 会重新导入
- 触发对应 SOP 的模块索引刷新
- 异步触发向量预热
- 更新全局 `sop_index.json`

价值：

- 支持面试中动态替换或补充 SOP 文档
- 减少手工重启或重复上传成本

## 9. 安全与可靠性

### 9.1 安全点

- `readFile` 路径约束
- 统一异常处理
- Agent 不暴露目录枚举能力
- AI 能力缺失时不阻断主流程

### 9.2 可靠性点

- 启动自动导入数据
- Phase 2/3 均有 fallback
- 关键路径有日志
- 测试覆盖官方验收场景和 API 路径

## 10. 测试结论

当前仓库已包含：

- `Phase1OfficialAcceptanceTest`
- `Phase2OfficialAcceptanceTest`
- `Phase3ControllerTest`
- `Phase3ApiPathTest`
- 以及若干单测/控制器测试

本次检查结果：

- `mvn -q test` 通过

## 11. 当前适合的交付口径

这套实现更适合以下场景：

- 面试题提交
- 本地演示
- 功能验收

不适合作为生产版直接上线的部分：

- 内存存储无持久化
- 无鉴权
- 无多租户/权限模型
- 无大规模索引存储与异步任务体系
