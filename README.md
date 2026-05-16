# On-Call Assistant

一个基于 Java 21 + Spring Boot 3.4 的 On-Call 助手项目，按题目要求拆分为三个独立阶段：

- `v1`：关键词搜索
- `v2`：语义搜索
- `v3`：Agent 对话与 `readFile` 工具链展示

当前仓库已经包含后端接口、页面模板、SOP 启动导入、SOP 模块化索引生成，以及针对三个阶段的测试用例。

## 当前状态

- 已实现 `GET /`、`/v1`、`/v2`、`/v3` 页面入口
- 已实现 `POST /v1/documents`、`GET /v1/search`
- 已实现 `GET /v2/search`
- 已实现 `GET /v3/chat`、`POST /v3/chat/stream`、`POST /v3/chat`
- 已实现 `data/` 目录 HTML 启动导入
- 已实现 `data/sop_index.json` 与 `data/sop-xxx/*.md` 模块索引生成
- 已实现本地 fallback 模式：无外部 AI Key 时，`v2` 与 `v3` 仍可工作
- 本地验证通过：`mvn -q test`

## 技术栈

- Java 21
- Spring Boot 3.4
- Spring MVC + WebFlux
- Thymeleaf
- Jsoup
- HanLP
- Maven

## 项目结构

```text
.
├── data/                         # 原始 SOP HTML + 生成后的 sop_index / 模块文件
├── src/main/java/com/oncall/
│   ├── common/                   # 配置、异常、工具类、首页控制器
│   ├── phase1/                   # 关键词搜索
│   ├── phase2/                   # 语义搜索
│   └── phase3/                   # Agent、SOP 索引、readFile 工具
├── src/main/resources/
│   ├── application.yml
│   ├── static/css/style.css
│   └── templates/                # index / v1 / v2 / v3 页面
├── src/test/java/com/oncall/     # 各阶段测试
├── DESIGN_DOC.md
├── DELIVERY_CHECKLIST.md
├── EXAM_REQUIREMENTS.md
└── pom.xml
```

## 启动方式

### 1. 环境要求

- JDK 21+
- Maven 3.9+

### 2. 本地启动

```bash
mvn spring-boot:run
```

默认端口：`http://localhost:8080`

### 3. 页面入口

- `http://localhost:8080/`
- `http://localhost:8080/v1`
- `http://localhost:8080/v2`
- `http://localhost:8080/v3`

## 配置说明

### 基础配置

`src/main/resources/application.yml` 中默认开启：

- 启动时自动扫描 `data/*.html`
- Phase 2 启动向量预热
- Phase 3 启动 SOP 索引预热
- 定时同步 `data/` 目录中的 HTML 变化

### 关键环境变量

```bash
AI_PROVIDER=moonshot
AI_BASE_URL=https://api.moonshot.cn/v1
MOONSHOT_API_KEY=

AI_EMBEDDING_PROVIDER=aliyun
AI_EMBEDDING_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
AI_EMBEDDING_API_KEY=
AI_EMBEDDING_MODEL=text-embedding-v3

AI_CHAT_MODEL=kimi-k2.6
AI_EMBEDDINGS_ENABLED=true
AI_TIMEOUT_SECONDS=60
AI_MAX_TOOL_ROUNDS=4
```

说明：

- 不提供 `MOONSHOT_API_KEY` 时，`v3` 会切换到本地 fallback Agent
- 不提供 embedding key 或关闭 `AI_EMBEDDINGS_ENABLED` 时，`v2` 会切换到本地语义排序

## 数据加载与索引生成

### 启动导入

应用启动后会读取 `data/*.html`，并通过 `DocumentService` 导入到内存仓库与倒排索引中。

### 生成的 Phase 3 辅助文件

系统会根据原始 HTML 自动生成：

- `data/sop_index.json`
- `data/sop-001/index.json`
- `data/sop-001/01_duties.md`
- `data/sop-001/02_metrics.md`
- `data/sop-001/03_troubleshooting.index.md`
- `data/sop-001/03_xx_*.md`
- `data/sop-001/04_escalation.md`
- `data/sop-001/05_forbidden.md`
- `data/sop-001/06_commands.md`

这些文件用于让 `v3` 的 Agent 以更稳定的结构读取 SOP，而不是每次都直接处理整份 HTML。

## 功能说明

### Phase 1：关键词搜索

- 路由：`/v1`
- 接口：
  - `POST /v1/documents`
  - `GET /v1/search?q=...`
- 实现方式：
  - Jsoup 解析 HTML
  - 排除 `script/style`
  - HanLP 分词
  - 内存倒排索引 + TF/IDF 类似打分

示例请求：

```http
POST /v1/documents
Content-Type: application/json

{
  "id": "sop-001",
  "html": "<html>...</html>"
}
```

```http
GET /v1/search?q=OOM
```

### Phase 2：语义搜索

- 路由：`/v2`
- 接口：
  - `GET /v2/search?q=...`
- 实现方式：
  - 优先走 embedding + chunk cosine similarity
  - 同时混合文本 BM25 风格信号
  - 无 embedding 能力时走本地 fallback 语义排序

### Phase 3：Agent

- 路由：`/v3`
- 接口：
  - `GET /v3/chat?message=...`
  - `POST /v3/chat/stream`
  - `POST /v3/chat`
- 能力：
  - 展示 `thinking`、`tool_call`、`tool_result`、`message`、`done` 事件
  - 工具只有一个：`readFile`
  - 优先读取 `sop_index.json` 与模块化 SOP 文件
  - 支持基于历史上下文的 follow-up 路由
  - 具备路径穿越、通配符、非法文件名限制

`POST /v3/chat` 请求示例：

```json
{
  "message": "服务 OOM 了怎么办？",
  "history": []
}
```

响应结构：

```json
{
  "answer": "......",
  "toolCalls": [
    {
      "tool": "readFile",
      "args": {
        "filename": "sop-001/03_02_oom.md"
      },
      "success": true,
      "length": 246,
      "excerpt": "......"
    }
  ],
  "totalTokens": 312
}
```

## 测试与验证

### 全量测试

```bash
mvn -q test
```

### 常用验证命令

```bash
mvn -q -DskipTests compile
mvn -q -Dtest=Phase1OfficialAcceptanceTest test
mvn -q -Dtest=Phase2OfficialAcceptanceTest test
mvn -q -Dtest=Phase3ControllerTest test
```

### 当前已覆盖的验证方向

- Phase 1 官方查询行为
- Phase 2 官方语义排序
- Phase 3 路由、trace、fallback、API tool calling
- `readFile` 文件名安全限制

## 已知实现边界

- 数据存储当前是内存级，适合面试题和演示，不适合生产持久化
- Phase 2/3 对外部 AI 的调用依赖环境变量配置
- `data/sop-xxx/*` 为运行期生成产物，提交前可一并保留，便于直接演示

## 交付建议

交付前请同时检查：

- 文档是否齐全：`README.md`、`DESIGN_DOC.md`、`DELIVERY_CHECKLIST.md`
- 是否补充 `PROMPTS.md`
- 是否准备页面截图、接口截图、Agent trace 截图
- 是否附带简历与最终提交说明
