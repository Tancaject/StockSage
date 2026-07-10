# 工作台 UI 精修设计(方向 C:Emerald Refined)

日期:2026-07-03
状态:方向已经用户在视觉稿中确认(浏览器伴侣选 C);细则待用户审阅
范围:stocksage-frontend 视觉与 UI 层;不改业务逻辑、不换品牌色、不动三栏信息架构
关联:`2026-07-03-frontend-maturity-design.md`(数据诚实化,另一条线,不在本篇范围)

## 0. 方向定义

保留现有绿色品牌与浅色卡片体系,用四板斧把"AI 模板感/玩具感"修掉:**密度、数字排版、色彩纪律、动效收敛**,外加状态视觉(骨架/空态/错误态)。参照视觉稿 `.superpowers/brainstorm/1660-1783081006/content/visual-direction.html` 中的 C 卡片。

## 1. 设计 token 修订(App.vue,一处改全局生效)

- 圆角:`--radius-lg` 16→10px,`--radius-md` 10→8px,新增 `--radius-sm: 6px`;999px 药丸只保留"对话/工作台"主导航切换器,其余 badge/pill 一律 `--radius-sm`。
- 阴影:卡片只保留 1px 边框 + 极浅 `--shadow-soft`;删除 hover 时切换到 `--shadow-command` 的行为(token 保留给弹层)。
- 数字:新增工具类 `.num { font-variant-numeric: tabular-nums }`,所有价格、涨跌、指标、成交量、时间戳单元格必须挂此类,保证小数点对齐、刷新不跳动。
- 涨跌色与品牌色分离:新增 `--up: #089981` / `--down: #f23645`(暗色 `#16c784` / `#ea3943`),涨跌语义一律用 `--up/--down`;`--accent` 绿只用于品牌、主按钮、选中态、焦点环。现有 `--positive/--negative` 逐步迁移到语义正确的一侧。
- 暗黑模式层次修复:提高 surface 与 border 对比(`--border-soft` 从与卡片同色的 #1f2937 调亮一档,如 #273244;具体值以截图目测定稿)。
- 移除 `--radial-glow` 背景渐变(模板感来源之一),底色用纯色。

## 2. 排版节奏

- 字号阶梯收敛为 **11 / 12 / 12.5 / 13 / 15 / 17 / 20**;禁用 10px、22px、26px、42px。
- 顶栏:高度 76→52px,`h1` 22→17px;面板 `h2` 17→15px。
- kicker(大写小标)统一 11px / 600 / letter-spacing .06em,只用于面板小节标题;取消 850 字重的滥用(现有多处 font-weight: 850)。
- 图表区 42px 巨字占位废除(见 §6 状态视觉)。

## 3. 密度

- **观察列表行**(WatchlistPanel):52→36px;两列网格:左列 ticker(13px/600)+ 公司简称(11px muted),右列现价 + 涨跌%(`.num`,涨跌用 `--up/--down`)。移除 status pill 与 lastAction 工作流文案。价格数据依赖批量行情接口(数据线 P2);接口就绪前显示 "—" 占位,不编数。
- 面板 padding 20→14px;`workspace-grid` gap 16→12px、padding 20→14px。
- 按钮高度 38→32px(rail-button 保持 38 便于点击,操作区按钮 32);action-strip 五按钮改为紧凑 32px 按钮组一行放下。
- 三栏宽度微调:左 232px、右 312px、中间自适应(现值基础上收左放右)。
- K 线面板内边距与 range 切换按钮同步紧凑化(不动图表绘制逻辑)。

## 4. 色彩纪律

- 涨跌一律 `--up/--down`(绿涨红跌,美股惯例;预留后续"红涨绿跌"设置项的 token 交换实现,本期不做设置界面)。
- "已自选"星标:去掉橙色特例,选中态 = accent 绿实心星 + `--accent-soft` 背景;hover 移除变红的行为(移除操作放行内 × 即可)。
- 状态 pill 收敛为三种视觉:中性(muted 底)、成功(accent-soft 底)、警示(warning-soft 底),同一形状 `--radius-sm`、11px;"降级/READY/SAMPLE" 等映射到这三种。
- 渐变一律清除:primary-button 的 `linear-gradient` 改纯色 `--accent`,hover 用 `--accent-dark`。

## 5. 动效纪律

- 删除:primary-button hover 上浮 translateY + 阴影加深;panel hover 阴影加深;watch-row hover 上浮;watch-row 选中 `flash-active` 发光动画;brief-action-button hover 上浮。
- 保留:颜色/边框/背景 0.15s ease 过渡;观察列表 FLIP 拖拽排序动画;el-tabs 自带过渡。
- 全局过渡时长统一 0.15s(现在混用 0.2/0.25/0.3/0.4)。

## 6. 状态视觉(三个可复用组件,纯前端)

- `PanelSkeleton`:灰条脉冲骨架,用于驾驶舱图表(替代 42px 巨字)、新闻、报告库加载。
- `PanelEmpty`:线性图标 + 一句话 + 可选动作按钮(如报告库空态:"还没有研究报告 → [发起研究]")。
- `PanelError`:一句人话 + [重试] 按钮 + "技术详情"折叠(HTTP 码只允许出现在折叠里);新闻栏红字 "HTTP 500" 之类全部替换。
- 文案口径:用户可见文本不出现"驾驶舱/兜底/降级/样例"等内部词;tab 名"AI 投研分析简报"→"投研简报","个股关联拓扑图谱"→"关联图谱"。

## 7. 布局修缮

- 顶栏 52px:分区标题(17px)靠左,搜索框右对齐(宽 320px,含下拉建议);移除多余空白。
- 持仓页/比较页:表单卡与说明卡合并为一张卡(说明信息作为卡片 footer 的 meta 行),容器 max-width 760px 居中,消除"两张小卡漂在整屏空白"的观感。
- 报告库:空态用 `PanelEmpty`;版本列表行紧凑化(padding 与 §3 一致)。
- 运维页:沿用同一套 token 自然继承,不单独设计。

## 8. 跨页一致性(ChatView)

- 主题初始化(dark class 读写 localStorage)上移 App.vue,对话页与工作台共享暗黑模式。
- 品牌副标题统一为"AI 投研工作台"(替换"研究驾驶舱"和"Research terminal")。
- 对话页的按钮/输入框继承 token 修订(圆角、动效、焦点环),不动其布局与交互。

## 9. 验收标准

- Playwright 明/暗双主题截图逐分区核验(研究/持仓/比较/报告库/运维/对话),亲眼比对,不以 build 绿为准。
- 硬指标:观察列表在 1080p 下可见 ≥12 行;全局无渐变按钮、无发光/上浮动效;所有数字单元格 tabular 对齐;暗黑模式卡片与背景边界清晰可辨;界面无裸露 HTTP 码(UI 文案层)。
- 既有前端测试(workbench/chart/researchUi 等 .test.mjs)保持绿;文案改动同步受影响断言。

## 10. 不做什么

- 不换品牌色、不换字体家族(只加 tabular-nums 特性)、不引入组件库或 UI 框架。
- 不动三栏信息架构、路由、业务逻辑、K 线绘制内核。
- 不做移动端适配、红涨绿跌设置界面、国际化。
- 数据接口改造(真实基本面、批量行情、真持仓)属于另一条线(见关联 spec),本篇只为其预留 UI 位置。

## 11. 实施切分建议(供 writing-plans 参考)

1. Token 与全局(App.vue + 动效清理)——一次提交可见全局变化;
2. 观察列表 + 顶栏 + 驾驶舱密度;
3. 三个状态组件 + 各面板接入 + 文案口径;
4. 持仓/比较/报告库布局修缮 + ChatView 一致性。
每步后跑截图验收再进下一步。
