# On-Call 助手 — 技术设计文档

> **项目名称**：On-Call 智能助手（On-Call Assistant）  
> **版本**：v1.0  
> **作者**：[候选人姓名]  
> **日期**：2026-05-14  
> **文档状态**：评审版  

---

## 目录

1. [文档概述](#1-文档概述)
2. [需求分析](#2-需求分析)
3. [技术选型](#3-技术选型)
4. [架构设计](#4-架构设计)
5. [Phase 1 — 搜索引擎 详细设计](#5-phase-1--搜索引擎-详细设计)
6. [Phase 2 — 语义搜索 详细设计](#6-phase-2--语义搜索-详细设计)
7. [Phase 3 — On-Call Agent 详细设计](#7-phase-3--on-call-agent-详细设计)
8. [数据模型设计](#8-数据模型设计)
9. [API 接口规范](#9-api-接口规范)
10. [前端设计](#10-前端设计)
11. [测试策略](#11-测试策略)
12. [部署方案](#12-部署方案)
13. [风险评估与应对](#13-风险评估与应对)
14. [项目排期](#14-项目排期)
15. [附录](#15-附录)

---

## 1. 文档概述

### 1.1 项目背景

在互联网公司的运维体系中，On-Call 值班是保障线上服务稳定性的核心机制。当告警触发时，值班工程师需要在极短时间内定位问题、找到对应的标准操作流程（SOP），并按照 SOP 的指引完成故障处理。

当前痛点：
- **信息检索低效**：100+ 份 SOP 文档分散在不同系统中，值班人员难以在紧急情况下快速定位到正确的 SOP
- **关键词匹配局限**：传统 CTRL+F 式搜索无法理解语义层面的需求，用户必须精确输入 SOP 中出现的术语
- **缺乏智能引导**：新人值班时缺乏经验，即使找到了 SOP 也不知道如何根据实际情况灵活运用

### 1.2 项目目标

构建一个 **On-Call 智能助手 Web 应用**，按三个递进阶段实现：

| 阶段 | 目标 | 核心能力 |
|------|------|---------|
| Phase 1 | 搜索引擎 | 基于关键词的精确文档检索，排除非正文内容干扰 |
| Phase 2 | 语义搜索 | 基于向量嵌入的语义匹配，理解自然语言查询 |
| Phase 3 | AI Agent | 通过大语言模型驱动的 Agent，自主定位文档并给出综合建议 |

### 1.3 术语定义

| 术语 | 定义 |
|------|------|
| SOP | Standard Operating Procedure，标准操作流程 |
| On-Call | 值班机制，工程师轮值处理线上故障 |
| Embedding | 将文本转化为高维向量的技术，用于语义相似度计算 |
| Agent | 具备工具调用能力的大模型对话系统 |
| Function Calling | LLM 根据用户输入自主决定调用哪个工具的能力 |

---

## 2. 需求分析

### 2.1 功能需求

#### 2.1.1 Phase 1 — 搜索引擎

| 需求编号 | 需求描述 | 优先级 |
|----------|---------|--------|
| FR-1.1 | 支持 POST 接口上传 HTML 格式的 SOP 文档 | P0 |
| FR-1.2 | 解析 HTML，提取正文内容（排除 `<script>` 和 `<style>` 标签） | P0 |
| FR-1.3 | 基于关键词的检索，支持中文分词 | P0 |
| FR-1.4 | 返回结果包含文档 ID、标题、摘要片段、相关度评分 | P0 |
| FR-1.5 | 提供简易 Web 搜索页面 | P1 |

**边界条件（来自官方验证用例）**：

| 编号 | 场景 | 预期行为 |
|------|------|---------|
| EC-1 | 查询 "OOM" | 返回 sop-001（后端服务） |
| EC-2 | 查询 "故障" | 返回多个文档（需要此词在正文中，几乎是所有文档） |
| EC-3 | 查询 "replication" | **返回空** — 该词仅在 `<script>` 标签内出现，不应被检索到 |
| EC-4 | 查询 "CDN" | 返回 sop-003（前端）和 sop-010（网络 & CDN） |
| EC-5 | 查询 "&" | 返回正文中包含 `&` 字符的文档 — 特殊字符不能被过滤掉 |

> **EC-3 和 EC-5 是本阶段的决定性测试用例**。EC-3 要求精准排除 script/style 标签内容；EC-5 要求保留特殊字符，不能粗暴 strip。

#### 2.1.2 Phase 2 — 语义搜索

| 需求编号 | 需求描述 | 优先级 |
|----------|---------|--------|
| FR-2.1 | 支持自然语言查询，不需要精确关键词匹配 | P0 |
| FR-2.2 | 结果按语义相关度降序排列 | P0 |
| FR-2.3 | 查询"服务器挂了" → sop-001、sop-004 靠前 | P0 |
| FR-2.4 | 查询"黑客攻击" → sop-005 靠前 | P0 |
| FR-2.5 | 查询"机器学习模型出问题" → sop-008 靠前 | P0 |

#### 2.1.3 Phase 3 — On-Call Agent

| 需求编号 | 需求描述 | 优先级 |
|----------|---------|--------|
| FR-3.1 | 对话式交互界面 | P0 |
| FR-3.2 | Agent 拥有 `readFile(filename)` 工具，可读取 data/ 目录下的文件 | P0 |
| FR-3.3 | Agent 不能列目录、不能使用通配符，只能按文件名读取 | P0 |
| FR-3.4 | 对话过程中展示 Agent 的工具调用过程（tool call trace） | P0 |
| FR-3.5 | Agent 能综合多个 SOP 给出完整回答 | P1 |

### 2.2 非功能需求

| 编号 | 类型 | 要求 | 指标 |
|------|------|------|------|
| NFR-1 | 性能 | Phase 1 搜索响应时间 | < 500ms（10 文档规模） |
| NFR-2 | 性能 | Phase 2 语义搜索响应时间 | < 2s（含 API 调用） |
| NFR-3 | 性能 | Phase 3 Agent 首 token 延迟 | < 5s |
| NFR-4 | 可用性 | API 错误响应格式统一 | JSON `{ "error": "message" }` |
| NFR-5 | 可维护性 | 代码按阶段分包，职责清晰 | — |
| NFR-6 | 安全 | Agent 的 `readFile` 不能访问 data/ 以外的目录 | 路径穿越防护 |

---

## 3. 技术选型

### 3.1 技术栈总览

```
┌─────────────────────────────────────────────────────────┐
│                     前端展示层                           │
│           HTML5 + CSS3 + Vanilla JavaScript             │
│         (Thymeleaf 模板引擎实现服务端渲染)               │
├─────────────────────────────────────────────────────────┤
│                     应用服务层                           │
│              Java 21 + Spring Boot 3.4                  │
│                    Maven 3.9                            │
├──────────────────┬──────────────────┬───────────────────┤
│   Phase 1 检索    │   Phase 2 语义    │   Phase 3 Agent   │
│   Jsoup 解析     │   Moonshot API   │   Moonshot API    │
│   内存倒排索引    │   Embedding      │   Function Calling │
│   HanLP 分词     │   余弦相似度      │   SSE 流式推送    │
├──────────────────┴──────────────────┴───────────────────┤
│                     数据存储层                           │
│              内存（ConcurrentHashMap）                   │
│              data/ 目录文件系统                          │
└─────────────────────────────────────────────────────────┘
```

### 3.2 各组件选型理由

| 组件 | 选型 | 理由 |
|------|------|------|
| **后端框架** | Spring Boot 3.4 | 候选人技术栈为 Java，Spring Boot 生态成熟，开发效率高 |
| **HTML 解析** | Jsoup 1.18 | Java 生态最成熟的 HTML 解析库，支持 CSS Selector，可精准移除 `<script>`/`<style>` |
| **中文分词** | HanLP | Java 原生中文 NLP 库，无需外部进程，支持多种分词模式 |
| **Embedding 模型** | Moonshot Embedding API | 面试公司自身产品，使用其 API 体现对公司的了解，且中文语义理解优秀 |
| **大语言模型** | Moonshot Chat Completion API (moonshot-v1-8k) | 同上，支持 Function Calling，满足 Agent 工具调用需求 |
| **前端** | Thymeleaf + Vanilla JS | 面试不要求复杂前端，Thymeleaf 模板简单直接，避免引入 React/Vue 的构建工具链复杂度 |
| **流式通信** | Server-Sent Events (SSE) | 单向服务器推送，浏览器原生支持，比 WebSocket 更轻量，适合 Agent 流式对话场景 |
| **数据存储** | ConcurrentHashMap（内存） | 10 份文档（demo）规模下内存索引足够。如需扩展到 100+ 份可迁移至嵌入向量数据库 |
| **构建工具** | Maven | Spring Boot 官方推荐，候选人熟悉 |

### 3.3 未选方案说明

| 方案 | 未选理由 |
|------|---------|
| Python + FastAPI | 候选人主语言为 Java，48 小时内跨语言学习成本过高 |
| Elasticsearch | 太重，10 份文档杀鸡用牛刀，且部署复杂 |
| 向量数据库（Milvus/Pinecone） | 同上，demo 阶段内存计算足够 |
| React/Vue 前端 | 面试不要求复杂前端，SPA 框架增加不必要的复杂度 |
| WebSocket | 本项目只需服务端→客户端单向推送（Agent 思考过程），SSE 更合适 |

---

## 4. 架构设计

### 4.1 系统架构图

```
                          ┌──────────────┐
                          │   浏览器      │
                          │  (Thymeleaf  │
                          │   + JS)      │
                          └──┬───┬───┬──┘
                             │   │   │
                    ┌────────┼───┼───┼────────┐
                    │   GET  │   │   │  SSE   │
                    │  /v1   │   │   │ /v3    │
                    │  /v2   │   │   │        │
                    ▼        ▼   ▼   ▼        │
              ┌────────────────────────────────┴──┐
              │         Spring Boot 应用           │
              │                                   │
              │  ┌─────────┐ ┌─────────┐ ┌──────┐│
              │  │ Phase1  │ │ Phase2  │ │Phas3 ││
              │  │Controller│ │Controller│ │Contr ││
              │  └────┬─────┘ └────┬─────┘ └──┬───┘│
              │       │            │           │    │
              │  ┌────▼─────┐ ┌────▼─────┐ ┌──▼───┐│
              │  │Keyword   │ │Semantic  │ │Agent ││
              │  │Search    │ │Search    │ │Orch- ││
              │  │Service   │ │Service   │ │trator││
              │  └────┬─────┘ └────┬─────┘ └──┬───┘│
              │       │            │           │    │
              │  ┌────▼─────┐ ┌────▼─────┐ ┌──▼───┐│
              │  │Inverted  │ │Embedding │ │Tool  ││
              │  │Index     │ │Client    │ │Exec- ││
              │  │(Memory)  │ │          │ │utor  ││
              │  └──────────┘ └────┬─────┘ └──┬───┘│
              │                   │           │    │
              │              ┌────▼─────┐     │    │
              │              │Document  │     │    │
              │              │Vector    │     │    │
              │              │Store     │     │    │
              │              └──────────┘     │    │
              └───────────────┬───────────────┼────┘
                              │               │
                     HTTP POST│       HTTP POST│
                              ▼               ▼
                     ┌──────────────┐ ┌──────────────┐
                     │  Moonshot    │ │  Moonshot    │
                     │  Embedding   │ │  Chat        │
                     │  API         │ │  Completion  │
                     │              │ │  API         │
                     └──────────────┘ └──────────────┘
```

### 4.2 项目目录结构

```
project-root/
├── data/                           # SOP 文档（已提供）
│   ├── sop-001.html
│   ├── sop-002.html
│   ├── ...
│   └── sop-010.html
├── prompt/                         # AI 对话截图（提交时）
├── screenshot/                     # 效果截图（提交时）
├── src/
│   └── main/
│       ├── java/com/oncall/
│       │   ├── OnCallApplication.java          # Spring Boot 入口
│       │   │
│       │   ├── phase1/                         # Phase 1：搜索引擎
│       │   │   ├── controller/
│       │   │   │   └── SearchController.java   # /v1/* 路由
│       │   │   ├── service/
│       │   │   │   ├── DocumentService.java    # 文档上传与解析
│       │   │   │   └── KeywordSearchService.java # 关键词检索
│       │   │   ├── index/
│       │   │   │   └── InvertedIndex.java      # 内存倒排索引
│       │   │   └── model/
│       │   │       ├── Document.java           # 文档模型
│       │   │       └── SearchResult.java       # 搜索结果模型
│       │   │
│       │   ├── phase2/                         # Phase 2：语义搜索
│       │   │   ├── controller/
│       │   │   │   └── SemanticSearchController.java # /v2/* 路由
│       │   │   ├── service/
│       │   │   │   └── SemanticSearchService.java    # 语义搜索服务
│       │   │   ├── embedding/
│       │   │   │   └── EmbeddingClient.java          # Moonshot Embedding API 客户端
│       │   │   └── store/
│       │   │       └── VectorStore.java              # 文档向量存储
│       │   │
│       │   ├── phase3/                         # Phase 3：Agent
│       │   │   ├── controller/
│       │   │   │   └── AgentController.java   # /v3/* 路由（含 SSE）
│       │   │   ├── service/
│       │   │   │   └── AgentService.java      # Agent 编排服务
│       │   │   ├── llm/
│       │   │   │   └── MoonshotChatClient.java # Moonshot Chat API 客户端
│       │   │   ├── tool/
│       │   │   │   └── ReadFileTool.java       # readFile 工具实现
│       │   │   └── model/
│       │   │       ├── AgentMessage.java       # 对话消息模型
│       │   │       └── ToolCall.java           # 工具调用模型
│       │   │
│       │   ├── common/                         # 通用模块
│       │   │   ├── config/
│       │   │   │   ├── MoonshotConfig.java     # Moonshot API 配置
│       │   │   │   └── WebConfig.java          # CORS 等 Web 配置
│       │   │   ├── exception/
│       │   │   │   └── GlobalExceptionHandler.java
│       │   │   └── util/
│       │   │       └── HtmlParser.java         # Jsoup 解析工具
│       │   │
│       │   └── shared/                         # 跨阶段共享
│       │       └── DocumentRepository.java     # 文档仓库（Phase 1&2 共享）
│       │
│       └── resources/
│           ├── application.yml                 # 应用配置
│           ├── templates/                      # Thymeleaf 模板
│           │   ├── v1-search.html
│           │   ├── v2-search.html
│           │   └── v3-chat.html
│           └── static/
│               └── css/
│                   └── style.css
│
├── pom.xml
├── README.md                                   # 项目说明（启动方式、接口说明、验证步骤）
├── RESUME.pdf                                  # 候选人简历（提交材料）
├── PROMPTS.md                                  # 关键 prompt 记录（提交材料）
└── DESIGN_DOC.md                               # 本文档
```

### 4.3 技术架构原则

1. **分层解耦**：每个 Phase 拥有独立的 Controller / Service / 数据层，互不干扰。通过 `DocumentRepository` 共享文档数据，但不共享业务逻辑。
2. **接口先行**：先定义 API 契约，再实现内部逻辑，确保与测试用例对齐。
3. **渐进式复杂度**：Phase 1 全部内存实现 → Phase 2 引入外部 Embedding API → Phase 3 引入 LLM Function Calling。
4. **错误处理统一**：所有异常通过 `GlobalExceptionHandler` 统一兜底，返回标准 JSON 错误响应。

---

## 5. Phase 1 — 搜索引擎 详细设计

### 5.1 核心流程

```
POST /v1/documents  →  Jsoup 解析 HTML  →  提取正文  →  中文分词  →  构建倒排索引
GET  /v1/search     →  查询词分词      →  索引查找  →  计算 TF-IDF →  排序返回
```

### 5.2 HTML 解析策略（关键）

#### 5.2.1 正文提取规则

```
输入 HTML
   │
   ▼
Jsoup.parse(html)
   │
   ├── 移除所有 <script> 标签及其内容    ← EC-3 的关键！
   ├── 移除所有 <style> 标签及其内容
   ├── 移除 <noscript>、<template> 等非渲染标签
   │
   ▼
body.text()  →  纯文本
   │
   ▼
提取 <title> 标签内容作为文档标题
```

**为什么这对 EC-3 至关重要**：

在 `sop-002.html` 中，`<script>` 标签内包含：
```javascript
var alertThresholds = { replicationLag: 10 };
```

如果解析时不排除 `<script>`，搜索 "replication" 将错误地匹配到 sop-002。评测明确要求返回空。

#### 5.2.2 特殊字符处理（EC-5）

查询 `&` 时，需要返回正文中包含 `&` 字符的文档。

- **不能**在分词前将 `&` 等特殊字符直接 strip
- **策略**：索引时将特殊字符视为独立 token 保留；查询时对特殊字符做精确匹配

### 5.3 倒排索引设计

```
数据结构：
  Map<Term, Map<DocId, TermFrequency>>

示例：
  "OOM" → { "sop-001": 3, "sop-003": 1 }
  "数据库" → { "sop-002": 25, "sop-005": 8, "sop-006": 15 }
  "&" → { "sop-003": 2 }
```

**索引字段**：
| 字段 | 说明 |
|------|------|
| term | 分词后的词条 |
| documentId | 文档唯一标识 |
| termFrequency | 词条在该文档中的出现次数 |
| positions | 词条在文档中的位置列表（用于生成 snippet） |

### 5.4 搜索算法

#### 5.4.1 TF-IDF 评分

```
Score(term, doc) = TF(term, doc) × IDF(term)

其中：
  TF(term, doc) = 1 + log(count(term, doc))     # 对数平滑
  IDF(term) = log(N / df(term))                  # N = 文档总数
```

#### 5.4.2 多词查询

对于多词查询，取各词评分的和：

```
Score(query, doc) = Σ Score(term, doc)  for term ∈ tokenize(query)
```

#### 5.4.3 Snippet 生成

在文档正文中定位到最高频匹配词的首次出现位置，向前后各扩展 50 个字符，截取为摘要片段。

### 5.5 API 实现伪代码

```
POST /v1/documents:
  1. 校验 JSON body（id、html 字段非空）
  2. Jsoup.parse(html)
  3. 移除 script、style 标签
  4. 提取 body.text() 和 title
  5. HanLP 分词处理正文
  6. 更新 InvertedIndex
  7. 返回 201 { id, title }

GET /v1/search?q=query:
  1. 校验 q 参数非空
  2. HanLP 分词 query
  3. 在 InvertedIndex 中查找每个 term
  4. 计算 TF-IDF 评分
  5. 降序排列，生成 snippet
  6. 返回 200 { query, results[] }
```

### 5.6 验证用例对照表

| 查询 | 预期结果 | 实现要点 |
|------|---------|---------|
| `OOM` | sop-001 | 直接关键词匹配 |
| `故障` | 多个文档 | 所有 SOP 正文都含"故障" |
| `replication` | **空** | script 标签已排除 |
| `CDN` | sop-003, sop-010 | 两个文档的正文都含 CDN |
| `&` | 含 `&` 的文档 | 特殊字符保留在索引中 |

---

## 6. Phase 2 — 语义搜索 详细设计

### 6.1 核心流程

```
┌─────────────────────────────────────────────────────┐
│                    文档入库（启动时）                 │
│                                                       │
│  SOP HTML → Jsoup 解析 → 正文文本                     │
│                          │                            │
│                          ▼                            │
│              Moonshot Embedding API                   │
│              POST /v1/embeddings                      │
│              model: moonshot-v1-embedding             │
│                          │                            │
│                          ▼                            │
│                   向量 [0.023, -0.451, ...]           │
│                          │                            │
│                          ▼                            │
│                VectorStore.put(id, vector)            │
│                (ConcurrentHashMap<String, float[]>)   │
└─────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────┐
│                    用户搜索                           │
│                                                       │
│  查询文本 → Moonshot Embedding API → 查询向量         │
│                                          │            │
│                                          ▼            │
│                              遍历 VectorStore        │
│                              计算余弦相似度           │
│                                          │            │
│                                          ▼            │
│                              降序排列 → 返回 Top-K    │
└─────────────────────────────────────────────────────┘
```

### 6.2 Embedding API 调用

#### 请求

```json
POST https://api.moonshot.cn/v1/embeddings
Authorization: Bearer sk-xxx
Content-Type: application/json

{
  "model": "moonshot-v1-embedding",
  "input": "后端服务 On-Call SOP 全文内容...",
  "encoding_format": "float"
}
```

#### 响应

```json
{
  "data": [
    {
      "embedding": [0.023456, -0.451234, 0.789012, ...],  // 通常 1536 或更高维度
      "index": 0
    }
  ],
  "usage": { "total_tokens": 512 }
}
```

### 6.3 余弦相似度计算

```java
public static double cosineSimilarity(float[] a, float[] b) {
    double dot = 0.0, normA = 0.0, normB = 0.0;
    for (int i = 0; i < a.length; i++) {
        dot += a[i] * b[i];
        normA += a[i] * a[i];
        normB += b[i] * b[i];
    }
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
}
```

### 6.4 优化策略

#### 6.4.1 文档分块嵌入

长文档直接嵌入可能超出模型的 token 限制，且语义信息被稀释。采用分段策略：

```
完整文档正文
    │
    ├── 按 "\n\n" (双换行) 自然分段
    │
    ├── 每段 ≤ 512 tokens
    │
    ▼
每段独立计算 Embedding
    │
    ▼
查询时取 max(cosineSimilarity(query, chunk)) 作为文档得分
```

#### 6.4.2 缓存

- Embedding 计算结果缓存在 `VectorStore` 中，应用重启后重新计算
- 10 份文档（demo）规模下，重新计算耗时 < 5 秒，可接受

### 6.5 验证用例对照表

| 查询 | 预期结果 | 语义理解要点 |
|------|---------|-------------|
| `服务器挂了` | sop-001, sop-004 靠前 | "挂了"=服务不可用=后端+SRE |
| `黑客攻击` | sop-005 靠前 | "黑客"≠出现于正文，"攻击"+"安全"语义关联 |
| `机器学习模型出问题` | sop-008 靠前 | "机器学习"→"AI"、"模型"→"推理延迟"、语义匹配 |

---

## 7. Phase 3 — On-Call Agent 详细设计

### 7.1 核心流程

```
┌─────────────────────────────────────────────────────┐
│                    Agent 对话循环                     │
│                                                       │
│  用户输入                                              │
│     │                                                 │
│     ▼                                                 │
│  ┌───────────────────────────┐                        │
│  │  构建 System Prompt        │                        │
│  │  + 对话历史                │                        │
│  │  + 工具定义 (readFile)     │                        │
│  │  + 用户新消息              │                        │
│  └───────────┬───────────────┘                        │
│              │                                        │
│              ▼                                        │
│  ┌───────────────────────────┐                        │
│  │  Moonshot Chat API         │                        │
│  │  (Function Calling 模式)   │                        │
│  └───────────┬───────────────┘                        │
│              │                                        │
│      ┌───────┴────────┐                               │
│      ▼                ▼                               │
│  text content    tool_calls[]                          │
│  → 回复用户      → 执行 readFile                       │
│                      │                                │
│                      ▼                                │
│              读取 data/ 目录文件                        │
│                      │                                │
│                      ▼                                │
│              将结果追加到对话历史                        │
│                      │                                │
│                      ▼                                │
│              继续下一轮 LLM 调用                        │
│              (循环直到 LLM 不再请求工具)                 │
└─────────────────────────────────────────────────────┘
```

### 7.2 System Prompt 设计

```
你是一个 On-Call 值班助手，帮助工程师处理线上故障。
你可以使用 readFile 工具读取 SOP 文档。

工作流程：
1. 分析用户描述的问题
2. 判断哪个部门/领域的 SOP 最相关
3. 调用 readFile 读取对应的 SOP 文件
4. 基于 SOP 内容给出具体的处理步骤和建议
5. 如果问题涉及多个领域，读取多个 SOP 并综合分析

data/ 目录下有 10 份 SOP 文档：
- sop-001.html: 后端服务 On-Call SOP（OOM、服务超时、降级）
- sop-002.html: 数据库 DBA On-Call SOP（主从延迟、慢查询、连接池）
- sop-003.html: 前端 On-Call SOP（白屏、CDN 加载失败）
- sop-004.html: SRE On-Call SOP（K8s、监控告警、容量规划）
- sop-005.html: 信息安全 On-Call SOP（入侵检测、漏洞响应）
- sop-006.html: 数据平台 On-Call SOP（数据管道、ETL）
- sop-007.html: 移动端 On-Call SOP（崩溃率、热修复）
- sop-008.html: AI & 算法 On-Call SOP（推理延迟、GPU 集群）
- sop-009.html: QA On-Call SOP（测试环境、自动化测试）
- sop-010.html: 网络 & CDN On-Call SOP（DNS、DDoS 防护）

注意：
- 你只能通过 readFile 按文件名读取，不能列出目录
- 文件名格式为 sop-XXX.html
- 给出回答时，引用具体的 SOP 来源
```

### 7.3 Function Calling 工具定义

```json
{
  "type": "function",
  "function": {
    "name": "readFile",
    "description": "读取 data/ 目录下的 SOP 文档文件。传入文件名（如 sop-001.html），返回文件内容。",
    "parameters": {
      "type": "object",
      "properties": {
        "filename": {
          "type": "string",
          "description": "要读取的文件名，例如 sop-001.html、sop-002.html"
        }
      },
      "required": ["filename"]
    }
  }
}
```

### 7.4 安全防护

```java
public String readFile(String filename) {
    // 1. 参数校验：禁止空文件名
    if (filename == null || filename.isBlank()) {
        throw new SecurityException("Invalid filename: " + filename);
    }

    // 2. 只允许传“文件名”，不能包含目录片段
    if (!filename.equals(Paths.get(filename).getFileName().toString())) {
        throw new SecurityException("Path is not allowed: " + filename);
    }

    // 3. 解析并归一化，防止路径穿越
    Path dataDir = Paths.get("data").toAbsolutePath().normalize();
    Path filePath = dataDir.resolve(filename).normalize();

    if (!filePath.startsWith(dataDir)) {
        throw new SecurityException("Path traversal detected");
    }

    // 4. 检查文件存在性
    if (!Files.exists(filePath)) {
        throw new FileNotFoundException("File not found: " + filename);
    }

    return Files.readString(filePath);
}
```

### 7.5 SSE 流式推送

Agent 思考过程和工具调用通过 SSE 推送至前端，实现实时展示。

#### SSE 事件类型

| event 类型 | 说明 | 数据 |
|-----------|------|------|
| `thinking` | Agent 正在思考 | `"正在分析问题..."` |
| `tool_call` | Agent 调用了工具 | `{"tool":"readFile","args":{"filename":"sop-001.html"}}` |
| `tool_result` | 工具执行完成 | `{"success":true,"length":2048}` |
| `message` | Agent 的文本回复 | delta 文本片段（流式） |
| `done` | 对话回合结束 | `{"totalTokens":1234}` |
| `error` | 发生错误 | `{"message":"错误描述"}` |

#### Java SSE 实现

```java
@GetMapping(value = "/v3/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<SseEvent> chat(@RequestParam String message) {
    return agentService.processMessage(message)
        .map(event -> SseEvent.builder()
            .id(UUID.randomUUID().toString())
            .event(event.type())
            .data(event.data())
            .build());
}
```

使用 Spring WebFlux，统一采用 `Flux` 实现 SSE（不与 `SseEmitter` 混用）。

### 7.6 验证用例对照表

| 用户提问 | 期望 Agent 行为 | 应读取的文件 |
|---------|---------------|------------|
| "数据库主从延迟超过30秒怎么处理？" | 定位数据库 SOP，给出处理步骤 | sop-002.html |
| "服务 OOM 了怎么办？" | 定位后端 SOP，给出排查建议 | sop-001.html |
| "P0 故障的响应流程是什么？" | 综合多个 SOP 给出完整回答 | sop-001 + 其他 |
| "怀疑有人入侵了系统" | 定位安全 SOP，给出响应流程 | sop-005.html |
| "推荐结果质量下降了" | 定位 AI 算法 SOP，给出排查方向 | sop-008.html |

---

## 8. 数据模型设计

### 8.1 文档模型（Document）

```java
public class Document {
    private String id;          // "sop-001"
    private String title;       // "后端服务 On-Call SOP"
    private String html;        // 原始 HTML
    private String plainText;   // 解析后的纯文本（排除 script/style）
    private long createdAt;     // 创建时间戳
}
```

### 8.2 搜索结果模型（SearchResult）

```java
public class SearchResult {
    private String id;          // 文档 ID
    private String title;       // 文档标题
    private String snippet;     // 匹配片段（≤150 字符）
    private double score;       // 相关度评分（0.0 ~ 1.0）
}
```

### 8.3 搜索响应模型（SearchResponse）

```java
public class SearchResponse {
    private String query;               // 原始查询词
    private List<SearchResult> results; // 结果列表
    private long took;                  // 耗时（ms），可选
}
```

### 8.4 向量条目（VectorEntry）

```java
public class VectorEntry {
    private String documentId;
    private float[] embedding;   // 来自 Moonshot API 的向量
    private int tokenCount;      // embedding 消耗的 token 数
    private long createdAt;
}
```

### 8.5 Agent 消息模型

```java
// 对话角色
public enum Role {
    USER,       // 用户
    ASSISTANT,  // Agent 文本回复
    TOOL        // 工具执行结果
}

// 单条消息
public class AgentMessage {
    private Role role;
    private String content;          // 文本内容
    private List<ToolCall> toolCalls; // 工具调用（仅 ASSISTANT 角色可能有）
    private String toolCallId;       // 工具调用 ID（仅 TOOL 角色）
    private long timestamp;
}

// 工具调用
public class ToolCall {
    private String id;
    private String name;             // "readFile"
    private Map<String, Object> arguments; // {"filename": "sop-001.html"}
}
```

---

## 9. API 接口规范

### 9.1 Phase 1 API

#### POST /v1/documents — 上传文档

```
Request:
  Content-Type: application/json
  {
    "id": "sop-001",
    "html": "<html>...</html>"
  }

Response (201 Created):
  {
    "id": "sop-001",
    "title": "后端服务 On-Call SOP"
  }

Error (400 Bad Request):
  {
    "error": "Missing required field: html"
  }
```

#### GET /v1/search — 关键词搜索

```
Request:
  GET /v1/search?q=OOM

Response (200 OK):
  {
    "query": "OOM",
    "results": [
      {
        "id": "sop-001",
        "title": "后端服务 On-Call SOP",
        "snippet": "...Java服务出现OutOfMemoryError时，Kubernetes会自动重启Pod...",
        "score": 0.95
      }
    ],
    "took": 12
  }

Response (200 OK, 无结果):
  {
    "query": "replication",
    "results": [],
    "took": 5
  }
```

#### GET /v1 — 搜索页面

返回 Thymeleaf 渲染的 HTML 页面。

### 9.2 Phase 2 API

#### GET /v2/search — 语义搜索

```
Request:
  GET /v2/search?q=服务器挂了

Response (200 OK):
  {
    "query": "服务器挂了",
    "results": [
      {
        "id": "sop-001",
        "title": "后端服务 On-Call SOP",
        "snippet": "...监控告警、容量规划、故障响应...",
        "score": 0.87
      },
      {
        "id": "sop-004",
        "title": "SRE On-Call SOP",
        "snippet": "...K8s集群问题、监控告警...",
        "score": 0.82
      }
    ],
    "took": 856
  }
```

#### GET /v2 — 搜索页面

返回 Thymeleaf 渲染的 HTML 页面。

### 9.3 Phase 3 API

#### GET /v3 — 对话页面

返回 Thymeleaf 渲染的对话界面 HTML。

#### GET /v3/chat — Agent 对话（SSE 流）

```
Request:
  GET /v3/chat?message=服务OOM了怎么办

Response (text/event-stream):
  event: thinking
  data: 正在分析问题...

  event: tool_call
  data: {"tool":"readFile","args":{"filename":"sop-001.html"}}

  event: tool_result
  data: {"success":true,"length":3580}

  event: message
  data: 根据sop-001.html（后端服务On-Call SOP）...

  event: message
  data: ...当Java服务出现OutOfMemoryError时...

  event: done
  data: {"totalTokens":1245}
```

#### POST /v3/chat — Agent 对话（非流式，备用）

```
Request:
  {
    "message": "服务OOM了怎么办",
    "history": []  // 可选，之前的对话历史
  }

Response (200 OK):
  {
    "answer": "根据后端服务On-Call SOP（sop-001.html）...",
    "toolCalls": [
      {
        "tool": "readFile",
        "args": {"filename": "sop-001.html"},
        "result": "...文件内容摘要..."
      }
    ],
    "totalTokens": 1245
  }
```

### 9.4 通用错误响应格式

```json
{
  "error": "人类可读的错误描述",
  "code": "ERROR_CODE",
  "timestamp": "2026-05-14T10:30:00Z"
}
```

| HTTP 状态码 | 场景 |
|-------------|------|
| 400 | 请求参数不合法 |
| 404 | 资源不存在 |
| 500 | 服务器内部错误（含 Moonshot API 调用失败） |
| 503 | Moonshot API 不可用 |

---

## 10. 前端设计

### 10.1 设计原则

- **极简**：面试不要求复杂前端，每个页面只需满足基本功能
- **渐进增强**：Phase 1/2 为传统表单提交 + 服务端渲染；Phase 3 使用 JS + SSE 实现动态对话
- **无障碍**：语义化 HTML，支持键盘操作

### 10.2 Phase 1/2 搜索页面

```
┌──────────────────────────────────────────────┐
│         On-Call 助手 — 文档搜索               │
│                                              │
│  ┌──────────────────────────────────────┐    │
│  │  输入关键词...                 [搜索] │    │
│  └──────────────────────────────────────┘    │
│                                              │
│  搜索结果 (3 条，耗时 12ms)                   │
│                                              │
│  ┌──────────────────────────────────────┐    │
│  │ sop-001 · 后端服务 On-Call SOP       │    │
│  │ ...出现OutOfMemoryError时，Kuberne.. │    │
│  │ 相关度：0.95                          │    │
│  └──────────────────────────────────────┘    │
│                                              │
│  ┌──────────────────────────────────────┐    │
│  │ sop-004 · SRE On-Call SOP            │    │
│  │ ...K8s集群问题、监控告警、容量规划.. │    │
│  │ 相关度：0.72                          │    │
│  └──────────────────────────────────────┘    │
│                                              │
│  [切换到语义搜索]  [切换到 Agent 对话]        │
└──────────────────────────────────────────────┘
```

### 10.3 Phase 3 Agent 对话页面

```
┌──────────────────────────────────────────────┐
│         On-Call 助手 — Agent 对话             │
│                                              │
│  ┌──────────────────────────────────────┐    │
│  │  对话历史                             │    │
│  │                                      │    │
│  │  🙋 用户：服务OOM了怎么办？           │    │
│  │                                      │    │
│  │  🧠 Agent 思考中...                   │    │
│  │  ┌─ 工具调用 ────────────────────┐   │    │
│  │  │ 🔧 readFile("sop-001.html")   │   │    │
│  │  │ ✅ 读取完成 (3580 字符)        │   │    │
│  │  └──────────────────────────────┘   │    │
│  │                                      │    │
│  │  🤖 Agent：根据后端服务On-Call SOP   │    │
│  │  的建议，当Java服务出现OOM时，您应该：│    │
│  │  1. 保存堆转储文件用于后续分析       │    │
│  │  2. 检查最近是否有代码发布或配置变更 │    │
│  │  3. 通过Grafana的JVM监控面板确认...  │    │
│  └──────────────────────────────────────┘    │
│                                              │
│  ┌──────────────────────────────────────┐    │
│  │  输入问题...                     [发送] │    │
│  └──────────────────────────────────────┘    │
└──────────────────────────────────────────────┘
```

### 10.4 前端技术实现

- **Thymeleaf** 模板引擎渲染页面骨架
- **Vanilla JavaScript** 处理 SSE 连接和 DOM 更新
- **CSS Grid + Flexbox** 布局，无第三方 CSS 框架
- **Fetch API** 调用后端接口，无 jQuery 依赖

---

## 11. 测试策略

### 11.1 测试金字塔

```
        ┌──────┐
        │ E2E  │  ← 手动验证 5 个官方测试用例
        ├──────┤
        │ 集成  │  ← MockMvc 测试 API 接口
        ├──────┤
        │ 单元  │  ← JUnit 5 测试核心算法
        └──────┘
```

### 11.2 单元测试

| 测试类 | 测试对象 | 关键用例 |
|--------|---------|---------|
| `HtmlParserTest` | Jsoup 解析逻辑 | script/style 标签排除、特殊字符保留、title 提取 |
| `InvertedIndexTest` | 倒排索引 | 索引构建、term 查找、TF-IDF 计算 |
| `CosineSimilarityTest` | 余弦相似度 | 相同向量=1.0、正交向量=0.0、边界值 |
| `ReadFileToolTest` | 文件读取工具 | 正常读取、路径穿越拦截、文件不存在处理 |

### 11.3 集成测试

```java
@SpringBootTest
@AutoConfigureMockMvc
class SearchControllerTest {

    @Test
    void searchOOM_shouldReturnSop001() {
        // POST /v1/documents with sop-001
        // GET /v1/search?q=OOM
        // assert results contain sop-001
    }

    @Test
    void searchReplication_shouldReturnEmpty() {
        // POST /v1/documents with sop-002 (replication only in script)
        // GET /v1/search?q=replication
        // assert results is empty
    }

    @Test
    void searchAmpersand_shouldReturnMatchingDocs() {
        // POST /v1/documents with docs containing &
        // GET /v1/search?q=&
        // assert results not empty
    }
}
```

### 11.4 官方验证用例 — 一票通过标准

执行以下 5 个请求，全部通过则 Phase 1 完成：

```bash
# 1. 上传所有 10 份文档
for i in $(seq -w 1 10); do
  curl -X POST http://localhost:8080/v1/documents \
    -H "Content-Type: application/json" \
    -d "{\"id\":\"sop-$i\",\"html\":\"$(cat data/sop-$i.html | sed 's/"/\\"/g' | tr -d '\n')\"}"
done

# 2. 执行验证用例
curl "http://localhost:8080/v1/search?q=OOM"           # → sop-001
curl "http://localhost:8080/v1/search?q=故障"           # → 多个文档
curl "http://localhost:8080/v1/search?q=replication"    # → 空数组
curl "http://localhost:8080/v1/search?q=CDN"            # → sop-003, sop-010
curl "http://localhost:8080/v1/search?q=%26"            # → 含 & 的文档 (%26 = &)
```

---

## 12. 部署方案

### 12.1 本地开发环境

```bash
# 前置条件
- JDK 21+
- Maven 3.9+

# 启动
cd project-root
mvn spring-boot:run

# 访问
http://localhost:8080/v1  # Phase 1 搜索页
http://localhost:8080/v2  # Phase 2 语义搜索页
http://localhost:8080/v3  # Phase 3 Agent 对话页
```

### 12.2 配置管理

`application.yml`：

```yaml
server:
  port: 8080

spring:
  thymeleaf:
    prefix: classpath:/templates/
    suffix: .html
    cache: false  # 开发环境禁用缓存

moonshot:
  api-key: ${MOONSHOT_API_KEY:}
  base-url: https://api.moonshot.cn
  embedding-model: moonshot-v1-embedding
  chat-model: moonshot-v1-8k
  max-tokens: 2048
  temperature: 0.3  # 低温度以获得更确定性的回答

logging:
  level:
    com.oncall: DEBUG
```

### 12.3 打包

```bash
mvn clean package -DskipTests
java -jar target/oncall-assistant-1.0.0.jar
```

---

## 13. 风险评估与应对

| 风险 | 等级 | 影响 | 应对措施 |
|------|------|------|---------|
| Moonshot API 不可用 | 中 | Phase 2/3 无法工作 | Phase 1 不依赖外部 API，可独立演示。Phase 2 备选方案：使用本地 sentence-transformers 模型（但需切换 Python） |
| API Key 泄露 | 高 | 费用损失 | 使用环境变量，不在代码中硬编码。application.yml 中仅放占位符 |
| SOP 文档格式不一致 | 低 | HTML 解析失败 | 使用 Jsoup 的容错解析模式，兼容各种 HTML 结构 |
| 中文分词精度不足 | 低 | 搜索召回率下降 | HanLP 支持自定义词典，可补充运维术语如 OOM/QPS/K8s |
| Agent 幻觉（编造不存在的步骤） | 中 | 输出不可靠 | System Prompt 中强约束"基于 SOP 内容回答"，temperature 设 0.3 |
| LLM 响应超时 | 中 | 用户体验差 | 设置 30s 超时，前端显示"正在思考"状态，超时后提示重试 |

---

## 14. 项目排期

| 阶段 | 任务 | 预估耗时 | 产出 |
|------|------|---------|------|
| **准备** | 环境搭建、Maven 项目初始化、Moonshot API Key 申请 | 2h | 可运行的空项目 |
| **Phase 1** | Jsoup 解析 + HanLP 分词 + 倒排索引 + API + 前端页面 | 8h | 5 个验证用例全通过 |
| **Phase 2** | Embedding API 客户端 + VectorStore + 余弦相似度 + API + 前端页面 | 6h | 3 个验证用例全通过 |
| **Phase 3** | Moonshot Chat API + Function Calling + readFile 工具 + SSE 流式 + 对话界面 | 10h | 5 个验证用例全通过 |
| **收尾** | 测试、截图、Git 提交整理、打包提交 | 4h | 完整提交产物 |
| **总计** | | **30h** | |

> 48 小时内完成绰绰有余。建议优先保证 Phase 1 完整通过，Phase 2 其次，Phase 3 尽力而为。

---

## 15. 附录

### 15.1 Maven 依赖清单

```xml
<dependencies>
    <!-- Spring Boot -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-thymeleaf</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webflux</artifactId>  <!-- SSE 需要 -->
    </dependency>

    <!-- HTML 解析 -->
    <dependency>
        <groupId>org.jsoup</groupId>
        <artifactId>jsoup</artifactId>
        <version>1.18.1</version>
    </dependency>

    <!-- 中文分词 -->
    <dependency>
        <groupId>com.hankcs</groupId>
        <artifactId>hanlp</artifactId>
        <version>portable-1.8.4</version>
    </dependency>

    <!-- JSON -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>

    <!-- 测试 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### 15.2 Moonshot API 参考

| API | 端点 | 用途 |
|-----|------|------|
| Embedding | `POST https://api.moonshot.cn/v1/embeddings` | 文本转向量 |
| Chat Completion | `POST https://api.moonshot.cn/v1/chat/completions` | 对话 + Function Calling |
| 模型列表 | `GET https://api.moonshot.cn/v1/models` | 获取可用模型 |

### 15.3 参考资料

- [Moonshot API 文档](https://platform.moonshot.cn/docs)
- [Jsoup 官方文档](https://jsoup.org/)
- [HanLP GitHub](https://github.com/hankcs/HanLP)
- [Spring Boot 官方文档](https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/)
- [Server-Sent Events 规范](https://html.spec.whatwg.org/multipage/server-sent-events.html)

---

> **文档版本历史**
> | 版本 | 日期 | 变更 |
> |------|------|------|
> | v1.0 | 2026-05-14 | 初稿，完整设计文档 |
