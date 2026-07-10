# 工作台重构设计：自选标的研究追踪台

- 日期：2026-06-11
- 状态：待评审
- 范围：仅前端工作台（`stocksage-frontend`）的信息架构、组件与结果流重构；后端基本不动（origin 改动已先行落地）。

## 1. 背景与动机

当前工作台（`WorkbenchView.vue`，1970 行单文件）有 6 个平级 tab：研究 / 对比 / 事件 / 持仓 / 备忘录 / 评估。产品诊断发现四层问题：

1. **定位失焦**：6 个 tab 本质都是“填表单 → 拼 prompt → `streamChat` 跑 → 看结果”。聊天页也能问研究、出 K 线、触发深度研究，工作台的增量价值只剩“把 prompt 模板化”，太薄，撑不起独立的复杂界面。它没有“非用工作台不可”的理由。
2. **信息架构空心**：用左侧 watchlist（选标的）当组织核心，但 6 个 tab 里只有研究真正依赖选中标的；对比是多标的、持仓是账户级、评估与标的无关。组织轴心名存实亡，6 个 tab 粒度根本不统一。
3. **受众错配**：评估（导入 RAG eval JSON、看门禁指标）是开发者工具，混进了用户研究产品。
4. **结果四处散落**：一次研究的产出散在运行面板（临时）、驾驶舱任务轨迹/brief（持久）、报告库、手动备忘录四个位置。

**重构目标**：把工作台从“6 个 prompt 表单的大杂烩”收敛为**以自选标的为轴心的研究追踪台**——主场景是“单标的深度研究 → 自动沉淀报告 → 持续追踪”，这正是聊天做不到的跨会话标的资产沉淀，是工作台真正的存在理由。

## 2. 目标与非目标

**目标**
- 信息架构收敛为「1 主（研究台）+ 2 次（对比、持仓诊断）」，以选中标的为统一轴心。
- 评估拆成独立 `/eval` 工程页，移出工作台用户界面。
- 拆分 1970 行巨石为 layout + 区域组件 + 运行状态机 composable，每个文件单一职责、可独立测试。
- 运行面板全局化：三个区共用一个运行面板，统一“正在执行”的结果出口。
- 结果归档二分：深度研究自动沉淀为报告版本；事件/对比/持仓为一次性分析，留运行面板。
- 顺手清理上轮 review 的残留：prompt 原文暴露、中英混杂、英文错误文案。

**非目标**
- 不重写后端研究链路；不改 cockpit / reports / chat-stream 接口契约。
- 不引入新的状态管理库（Pinia 等），继续用 composable + 局部 ref。
- 不做移动端像素级精修（仅保持 `getWorkbenchResponsiveMode` 已有的断点不退化）。
- 不动聊天页核心；聊天↔工作台的“加入自选 / 一键深问”双向联动列为后续增强，不在本次范围。

## 3. 设计

### 3.1 信息架构

```
顶部导航:  [研究台]   对比   持仓诊断              ⚙ 检查
┌──────────┬───────────────────────────┬──────────────┐
│ 自选标的  │   选中标的 研究驾驶舱        │  最新结论     │
│ ● NVDA   │   K 线 / 状态 / 上次研究     │  报告版本     │
│   AAPL   │   发起研究：深度研究│事件解读  │  报告库 →     │
│   + 添加  │   运行轨迹 / 历史报告版本    │              │
└──────────┴───────────────────────────┴──────────────┘
```

- **研究台（主，默认）**：左=自选标的（真实画像来源），中=选中标的驾驶舱，右=最新结论/报告库。深度研究、事件解读为驾驶舱内的**研究模式**，不再是独立 tab。
- **对比（次级入口）**：多标的并排研究，独立区，粒度=多标的。
- **持仓诊断（次级入口）**：IBKR 账户级只读诊断，独立区，粒度=账户。
- **评估**：拆为独立 `/eval` 路由，不在工作台导航。
- **手动 markdown 备忘录**：移除，结果统一进报告库。

### 3.2 组件划分

```
WorkbenchLayout.vue              壳：顶部导航 + 全局运行面板 + 区域出口
├── ResearchDesk.vue             研究台（主）
│   ├── WatchlistPanel.vue       自选标的：画像加载 + 增删 + 选中
│   ├── StockCockpit.vue         驾驶舱：K线 + 状态 + 轨迹 + 报告版本 + 发起研究
│   └── LatestBrief.vue          最新结论 + 报告库入口
├── CompareDesk.vue              对比（次）
├── PortfolioDesk.vue            持仓诊断（次）
└── WorkbenchRunPanel.vue        全局运行面板（三区共用）

lib/researchRun.js  (新)         运行状态机 composable
lib/workbench.js    (复用)       cockpit/timeline/evidence 纯函数
lib/productUi.js    (复用)       导航/响应式/运行面板控制
EvalDesk.vue (新, 独立路由 /eval) 评估页（从工作台移出）
```

组件接口（签名级，不贴实现）：

- **WatchlistPanel** — props: `items`, `selected`；emit: `select(ticker)` / `add(ticker)` / `remove(ticker)`。只负责展示与交互。
- **StockCockpit** — props: `cockpit`（normalizeCockpit 产物）, `loading`；emit: `refresh` / `start-research({ mode, ticker, eventText? })`。`mode ∈ {deep, event}`。
- **LatestBrief** — props: `latestReport`, `reportVersions`；emit: `open-report(versionId)`。
- **WorkbenchRunPanel** — props: `run`（researchRun 暴露的运行状态）；emit: `stop` / `dismiss` / `toggle`。
- **researchRun（composable）** — 暴露 `run`（响应式状态：visible/status/title/ticker/answer/timeline/error/conversationId）、方法 `start(prompt, { origin:'workbench', title, ticker })` / `stop()` / `dismiss()` / `toggle()`；内部封装 `streamChat`、轮询启停、abort、卸载清理。

### 3.3 数据流

```
getUserProfile → buildWatchlistFromProfile → WatchlistPanel
  └ 选中 ticker → fetchStockCockpit(ticker) → normalizeCockpit → StockCockpit
       └ 发起研究(mode) → buildResearchPrompt / buildEventPrompt
            → researchRun.start(prompt,{origin:'workbench',title,ticker})
                 → streamChat → WorkbenchRunPanel(流式)
                      └ onDone → refreshWorkbenchData(拉 cockpit + 报告版本)
                                  （轮询期间持续更新运行轨迹）
```

对比 / 持仓在各自区域构造 prompt，同样调用 `researchRun.start` → 同一个全局运行面板。

### 3.4 关键决策

1. **运行面板全局化**：从各区提到 `WorkbenchLayout`，研究台/对比/持仓共用同一个 `researchRun` 实例与运行面板，把“产出散在 4 处”收成 1 处。
2. **结果归档二分**：深度研究完成 → 后端已持久化 investment report 版本（cockpit `latestReport` / 报告库即此来源）→ 前端刷新自动更新驾驶舱与报告库；事件/对比/持仓 → 一次性分析，留运行面板（可手动导出），不强行变成报告版本。
3. **巨石拆分**：1970 行单文件按区域与职责拆为 layout + 区域组件 + composable，复用现有纯函数，主要工作是搬迁与重组而非重写。

### 3.5 后端边界

- 复用现有接口：`/workbench/.../cockpit`、`/reports/investment`、`/chat/stream`（`origin=workbench` 已加）、`/user/me/profile`。
- 深度研究 → 报告持久化是已有能力（cockpit 已返回 `latestReport` / `taskTimeline`）。
- 评估拆 `/eval` 为纯前端路由调整。
- 预计后端零改动或仅微调；本次重活在前端。

## 4. 迁移路径

每步保持工作台可用、可独立验证、可随时停在完整状态。该表即实现计划骨架。

| 步骤 | 改什么 | 验证点 |
|---|---|---|
| 0（已完成） | origin：工作台会话不污染聊天 | 已做 |
| 1. 抽运行状态机 | `startWorkbenchRun`/轮询/abort/runState → `lib/researchRun.js`，WorkbenchView 改调它 | 运行面板行为不变 + 状态机单测（状态流转、轮询启停、卸载清理） |
| 2. 拆 eval 到 `/eval` | 新增 `EvalDesk.vue` + 路由，搬走 eval tab，导航去掉 eval | `/eval` 能导入 JSON 看指标；工作台不再有 eval |
| 3. 拆研究台组件 | research 区拆 `WatchlistPanel`/`StockCockpit`/`LatestBrief`，复用纯函数 | 选标的、看驾驶舱、发起研究功能不变 |
| 4. 事件解读收进驾驶舱 | event 发起收进 StockCockpit 研究模式（深度/事件），事件文本内联输入；去掉 event tab | 驾驶舱选“事件解读”发起，结果进运行面板 |
| 5. 对比/持仓改次级入口 | 抽 `CompareDesk`/`PortfolioDesk`，调用全局 `researchRun`；去掉 prompt `<pre>` 暴露 | 对比/持仓独立页发起，进同一运行面板 |
| 6. 整合外壳 + 清理 | `WorkbenchLayout` 承载统一导航 + 全局运行面板；砍手动备忘录；统一中英文案与错误提示 | 三区共用运行面板、导航统一、界面全中文 |

## 5. 测试

- `lib/researchRun.js`：状态机单测（idle→running→completed/failed/stopped、轮询启停、abort、卸载清理）。这是巨石里最值得抽出来可测的核心逻辑。
- `lib/workbench.js` / `productUi.js`：已有纯函数测试随接口微调维护。
- 组件层：以现有前端测试约定（`node --test src/lib/*.test.mjs`）覆盖纯逻辑；Vue 组件交互以手动验证表（迁移路径“验证点”）为准。
- 不引入重型组件测试框架（YAGNI）。

## 6. 风险与残留

- **巨石拆分回归风险**：1970 行里隐含状态耦合，拆分时行为可能漂移。缓解：步骤 1 先抽可测的运行状态机，组件拆分逐区进行、每步对照“验证点”手动回归。
- **深度研究→报告持久化假设**：依赖后端深度研究链路确实写 investment report。实现步骤 3/4 前先核实该链路（cockpit `latestReport` 实测有值即确认）；若某模式不持久化，按 3.4 归档二分留运行面板即可，不阻断。
- **后续增强（不在本次范围）**：聊天↔工作台双向联动（聊出价值的标的加入自选、工作台标的一键深问跳聊天带上下文）、移动端精修。
