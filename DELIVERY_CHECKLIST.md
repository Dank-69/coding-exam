# 48小时交付清单（可执行版）

## T-48h ~ T-36h：Phase 1 完成并可测

1. 初始化 Spring Boot 工程（Java 21 + Maven）。
2. 实现 `POST /v1/documents`、`GET /v1/search`、`GET /v1`。
3. HTML 解析必须排除 `script/style`，并保留 `&`。
4. 完成 5 条官方查询自测并保存截图。

完成标准：
1. `OOM -> sop-001`。
2. `replication -> []`。
3. `CDN -> sop-003, sop-010`。
4. `& -> 命中包含 & 的正文文档`。

## T-36h ~ T-24h：Phase 2 完成并可回归

1. 实现 `GET /v2/search`、`GET /v2`。
2. 接入 embedding + cosine 相似度排序。
3. 完成 3 条语义查询验证。
4. 对 Phase 1 做一次回归，确保不退化。

完成标准：
1. `服务器挂了`：`sop-001`、`sop-004` 靠前。
2. `黑客攻击`：`sop-005` 靠前。
3. `机器学习模型出问题`：`sop-008` 靠前。

## T-24h ~ T-12h：Phase 3 可对话可追踪

1. 实现 `GET /v3` 页面。
2. 实现 `GET /v3/chat`（SSE）或兼容的 `POST /v3/chat`。
3. Agent 工具仅 `readFile(filename)`。
4. 展示 `tool_call`、`tool_result` 事件。

完成标准：
1. 5 条场景问题均能调用正确 SOP。
2. 禁止列目录、禁止通配符、禁止路径穿越。
3. 只展示工具调用轨迹，不展示模型内部思维。

## T-12h ~ T-0h：提交包装与质量闸门

1. 补齐 `README` 启动步骤、接口样例、验证命令。
2. 补齐 `PROMPTS.md`（按阶段归档关键 prompt）。
3. 整理 `screenshots/`（接口结果、页面、Agent 过程）。
4. 自测一次全流程并录入“已通过项”。

最终提交前检查：
1. 项目可在全新环境按 README 跑起来。
2. 不含泄露密钥（API Key 仅环境变量）。
3. ZIP 内含：代码、简历、prompt、文档、截图。
