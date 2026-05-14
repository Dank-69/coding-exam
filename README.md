# On-Call Assistant（项目一）交付说明

本仓库用于完成 Moonshot AI 编程挑战「项目一：On-Call 助手」。

## 当前目标

1. 在 48 小时内交付可运行作品（`/v1`、`/v2`、`/v3`）。
2. 保证题目给出的验证用例可复现、可截图、可解释。
3. 产出可打包提交的完整材料（代码、简历、prompt、说明文档）。

## 项目范围

1. `Phase 1`：关键词搜索引擎（必须稳定通过）。
2. `Phase 2`：语义搜索（按相关性排序）。
3. `Phase 3`：On-Call Agent（展示工具调用轨迹）。

题目原文已存档在 [EXAM_REQUIREMENTS.md](/D:/codingtest/EXAM_REQUIREMENTS.md)。

## 交付物清单（最终 ZIP）

1. 源码工程（可直接启动运行）。
2. [README.md](/D:/codingtest/README.md)（启动步骤、接口说明、验证步骤）。
3. [DESIGN_DOC.md](/D:/codingtest/DESIGN_DOC.md)（设计说明）。
4. `RESUME.pdf`（简历）。
5. `PROMPTS.md`（关键 prompt 记录）。
6. `screenshots/`（运行截图、验证截图）。

## 验收标准（最低）

1. `Phase 1` 五条官方查询全部符合预期。
2. `Phase 2` 三条语义查询结果排序符合预期。
3. `Phase 3` 能回答五类问题，并展示 `tool_call/tool_result`。
4. 项目可一条命令启动，接口可复测。

## 开发顺序建议

1. 先完成 `Phase 1` 并锁定接口和数据结构。
2. 再接入 `Phase 2` 向量检索，保留降级策略。
3. 最后完成 `Phase 3` Agent 编排和 SSE 展示。

## 下一步

编码前请先按 [DELIVERY_CHECKLIST.md](/D:/codingtest/DELIVERY_CHECKLIST.md) 逐项确认。
