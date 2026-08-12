# 前端产品化改造设计(去玩具感)

> [!WARNING]
> 历史设计快照。状态组件、数据诚实化和 Workbench 产品化方向已进入当前实现，不再作为待审方案。

日期:2026-07-03
状态:待用户审阅
范围:stocksage-frontend 全部页面,以工作台为主;配套的少量 backend / data-service 接口改动

## 1. 问题定义

用户目标:前端要像成熟产品,不像玩具,尤其是工作台。经全量代码审读 + Playwright 实际渲染截图(明/暗、五个分区、对话页),"玩具感"归因为四类,按严重度排序:

1. **假数据冒充真数据(最致命)**
   - `lib/workbench.js getFinancialsSnapshot`:NVDA/AAPL 等写死 PE/PB/市值;名单外股票用 ticker 哈希现编 PE/PB/换手率/市值。
   - `normalizeCockpit`:成交额 = `价格×成交量×0.95`(编造);`getSentimentScore` 掺 ticker 哈希抖动。
   - 后端不可用时价格显示 $0.00,但旁边仍挂"PE 72.5 / 市值 3.12T"。
   - `WorkbenchView.marketIndexes`:写死的上证/恒指等假行情(现为死代码)。
   - 与项目"证据可溯源、反幻觉"的核心卖点直接矛盾,是面试场景下的一票否决项。
2. **内部术语与原始错误漏进界面**:"样例/本地兜底"、"驾驶舱数据暂不可用:HTTP 500"、新闻栏裸露红字"HTTP 500"、图表区巨大"NVDA"占位字。
3. **半成品分区**:持仓/比较=一张小表单漂在整屏空白;报告库空态一条虚线;观察列表行无价格无涨跌,却显示"驾驶舱已更新"这类工作流状态。
4. **两页两个产品**:工作台切暗黑后对话页仍是亮的(ChatView 不读主题);副标题"研究驾驶舱" vs "Research terminal";文案风格混杂。

公平性说明:截图时后端未启动,空态被放大;但 1、2、4 与后端状态无关。

## 2. 已核实的技术前提

- 界面骨架健康:App.vue 有完整设计 token 体系(明/暗),组件已拆分,无需重做视觉体系。
- **真数据管道已存在,只是没接**:
  - 美股:`IbkrReadOnlyService.getRealtimeQuote` 走 IBKR Web API snapshot(现取字段 31/84/86/87);同一 snapshot 接口支持基本面字段(市值/PE/EPS 等,实施时以 IBKR Web API 字段表核实字段号),增量成本≈把字段号加进请求列表。
  - 港股:data-service `akshare_service.get_hk_stock_info`。
  - A股:data-service `/financial`(akshare/baostock 财务指标)。
  - 持仓:`IbkrReadOnlyService.getPositions(accountId)` 已存在,只差 REST 透出+前端表格。
  - 新闻/搜索/关系图/报告版本均已是真数据。

## 3. 方案比较

- **方案A 止血抛光(纯前端,~1天)**:删除一切编造数据只显示可推导值;降级态/文案/主题一致性修复。快,但行情条变薄、持仓比较仍是半成品。
- **方案B 真数据化+抛光(前后端,~2-3天,推荐)**:A 的全部 + 接通已有真数据管道(基本面、观察列表行情、真持仓表)。视觉密度不缩水,数据全真,改动都是"接线"而非"新建"。
- **方案C 全面改版(1-2周,不推荐)**:B + 信息架构重排/组件库统一/国际化。对校招 portfolio 收益递减。

**选定:方案B**,按 P0→P3 分四批交付,每批可独立验收,用户可随时砍尾。

## 4. 设计细节(方案B)

### 原则(三条铁律)
1. **界面上不出现任何编造的数字**:拿不到的字段不显示,而不是编一个。
2. **每种状态都有设计**:loading=骨架屏,empty=引导文案,error=人话+重试,禁止裸露 HTTP 码与内部术语。
3. **一套产品语言**:两个页面同主题、同品牌副标题、同文案口径。

### P0 数据诚实化(纯前端)
- 删除 `getFinancialsSnapshot` 整个函数(含哈希编造分支)与 `catalog`;`quote` 仅保留 K 线可推导字段:现价/涨跌/涨跌幅/成交量。
- 删除成交额 `×0.95` 编造(有真实 amount 字段时才显示成交额);`getSentimentScore` 去掉哈希抖动,无报告时不显示情绪分。
- 行情条按字段有无自适应布局;完全无数据时整条隐藏,不显示 $0.00。
- 删除死代码:`marketIndexes`、`buildSparkline`、`formatMetric`(WorkbenchView)。
- 观察列表:去掉"样例/本地兜底/驾驶舱已更新"等工作流文案;status pill 暂时移除(P2 用真行情替代该视觉位)。

### P1 真基本面(backend + data-service)
- `IbkrReadOnlyService`:snapshot 请求增加基本面字段号(市值/PE/EPS 等,以官方字段表为准),解析进 quote payload。
- `WorkbenchCockpitService`:cockpit 响应增加 `fundamentals` 节点(市场分流:美股 IBKR;港股 akshare info;A股 data-service /financial);任何来源失败→该节点为空,不阻塞 K 线。
- 前端行情条渲染 `fundamentals` 中实际存在的字段,缺失即不渲染。

### P2 信息密度与半成品分区
- **观察列表行**:显示真实最新收盘价+涨跌%(新增轻量批量接口 `GET /api/workbench/quotes?tickers=`,美股走 IBKR snapshot 批量 conid,港/A 走 data-service;失败时行内只显示代码,不编数)。
- **持仓页做实**:新增 `GET /api/workbench/portfolio/positions`(透传 getPositions,只读);页面主体=真实持仓表(标的/数量/成本/现值/盈亏),"持仓诊断"作为表格上方主操作;IBKR 未连接→引导态说明数据源与连接方法。
- **比较页**:表单收窄为顶部条,下方为结果承载区(渲染最近一次对比运行的报告,复用 run panel 的产出);无结果时展示对比维度说明引导。
- **报告库**:空态=图标+一句引导+"发起研究"按钮;有数据时版本卡片网格。

### P3 产品一致性与降级态
- 主题初始化上移 App.vue(两个页面共享暗黑模式);统一品牌副标题(定"AI 投研工作台"口径,中文为准)。
- 统一三个可复用状态组件:`PanelSkeleton` / `PanelEmpty`(图标+标题+一句引导+可选动作)/ `PanelError`(人话标题+重试按钮+"技术详情"折叠里才允许出现 HTTP 码)。工作台所有面板(驾驶舱/新闻/报告库/关系图)接入。
- 错误文案映射:HTTP 5xx→"服务暂时不可用,请稍后重试";超时→"数据源响应超时";404/空→empty 态。
- 文案口径修订:"个股关联拓扑图谱"→"关联图谱";用户可见文案不出现"驾驶舱/兜底/降级"等内部词(状态徽标"降级"→"部分数据");图表占位大字"NVDA"→骨架屏。
- 运维分区退出一级导航:侧栏底部改为系统状态点(绿/黄/红)+文字,点击进入健康检查页(路由保留)。
- 动效收敛:移除 watch-row flash 发光动画、panel hover 抬升阴影加剧,保留 FLIP 拖拽排序与 tab 过渡。

### 错误处理与数据流
- cockpit / news / quotes / positions 全部遵循:请求失败→PanelError(可重试);空数据→PanelEmpty;加载→PanelSkeleton。
- fundamentals/quotes 属增强数据:失败静默降级(不弹 toast,不阻塞主内容)。

### 测试与验收
- 单测:normalizeCockpit 不再产出编造字段(给无 chart payload 断言 quote 为 null/仅真实字段);quotes/positions 接口 happy path + 失败降级;既有 8 个测试保持绿。
- 视觉验收(Playwright 截图,明+暗):后端关闭→全部面板呈现设计过的降级态,无 HTTP 码、无 $0.00、无假 PE;后端开启→行情条/观察列表/持仓表为真数据。
- 构建绿 ≠ 通过,截图亲眼核验后才算完成(用户既有铁律)。

## 5. 不做什么(YAGNI)
- 不做视觉重设计/换色/换字体(现有 token 体系保留)。
- 不做移动端适配、国际化、组件库迁移。
- 不动 ChatView 交互逻辑(只接主题与副标题口径)。
- 不做观察列表实时推送(批量快照按需拉取即可)。

## 6. 开放问题(不阻塞 P0)
- IBKR snapshot 基本面字段的可用性受账户行情订阅影响,实施 P1 时需实测;拿不到就按铁律 1 不显示,港/A 股通道不受影响。
- 持仓页在无 IBKR 网关时的演示策略:当前设计为引导态(不放假持仓);若用户希望面试断网可演示,可另议"标注为演示数据"的样本模式。
