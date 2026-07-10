<template>
  <div class="workbench-shell">
    <aside class="workbench-rail">
      <router-link class="rail-brand" to="/workbench">
        <span class="brand-mark">
          <el-icon><TrendCharts /></el-icon>
        </span>
        <span>
          <strong>StockSage</strong>
          <small>AI 投研工作台</small>
        </span>
      </router-link>

      <nav class="primary-nav rail-switch" aria-label="主导航">
        <router-link
          v-for="item in primaryNavItems"
          :key="item.id"
          class="primary-nav-link"
          :class="{ active: item.active }"
          :to="item.to"
          :aria-current="item.active ? 'page' : undefined"
          :title="item.description"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </router-link>
      </nav>

      <nav class="rail-nav" aria-label="工作台分区">
        <button
          v-for="tab in tabs"
          :key="tab.id"
          class="rail-button"
          :class="{ active: activeTab === tab.id }"
          type="button"
          @click="activeTab = tab.id"
        >
          <el-icon><component :is="tab.icon" /></el-icon>
          <span>{{ tab.label }}</span>
        </button>
      </nav>

      <div class="rail-footer">
        <div class="rail-user">
          <span class="rail-user-avatar">{{ userInitial }}</span>
          <span>
            <strong>{{ activeUserName }}</strong>
            <small>{{ activeUserLabel }}</small>
          </span>
        </div>
        <button class="theme-toggle-btn" type="button" @click="handleLogout" title="登出">
          <el-icon><SwitchButton /></el-icon>
          <span>登出</span>
        </button>
        <button class="theme-toggle-btn" type="button" @click="toggleTheme" :title="isDark ? '切换到明亮模式' : '切换到暗黑模式'">
          <el-icon><component :is="isDark ? Sunny : Moon" /></el-icon>
          <span>{{ isDark ? '明亮模式' : '暗黑模式' }}</span>
        </button>
      </div>
    </aside>

    <main class="workbench-main">
      <!-- 全局顶部导航栏 -->
      <header class="workbench-topbar">
        <div class="topbar-left">
          <h1>{{ activeTabLabel }}</h1>
        </div>

        <!-- 搜索框移到最上面 -->
        <div v-if="activeTab === 'research'" class="topbar-search-container" @focusout="hideSuggestionsSoon">
          <form class="ticker-form" @submit.prevent="handleSearchSubmit">
            <input
              v-model="tickerDraft"
              aria-label="Ticker"
              autocomplete="off"
              placeholder="输入代码或公司名 (例如: NVDA, AAPL)"
              @focus="showSuggestions = true"
              @keydown.down.prevent="moveSuggestion(1)"
              @keydown.up.prevent="moveSuggestion(-1)"
              @keydown.esc="showSuggestions = false"
            />
            <button type="submit" aria-label="Search ticker">
              <el-icon><Search /></el-icon>
            </button>
          </form>
          <div v-if="suggestionsVisible" class="ticker-suggestions" role="listbox">
            <button
              v-for="(suggestion, index) in suggestions"
              :key="suggestion.ticker"
              class="ticker-suggestion-row"
              :class="{ active: index === activeSuggestionIndex }"
              type="button"
              role="option"
              :aria-selected="index === activeSuggestionIndex"
              @mouseenter="activeSuggestionIndex = index"
              @mousedown.prevent="chooseSuggestion(suggestion)"
            >
              <strong>{{ suggestion.ticker }}</strong>
              <span>{{ suggestion.name }}</span>
              <small>{{ suggestion.reason || suggestion.market }}</small>
            </button>
          </div>
        </div>
      </header>

      <WorkbenchRunPanel
        v-if="workbenchRun.visible"
        :run="workbenchRun"
        :controls="runPanelControls"
        :collapsed="isRunPanelCollapsed"
        @stop="stopWorkbenchRun"
        @dismiss="dismissWorkbenchRun"
        @toggle="toggleRunPanel"
      />

      <section v-if="activeTab === 'research'" class="workspace-grid terminal-three-column-grid">
        <!-- 左栏：观察列表 -->
        <WatchlistPanel
          v-model:items="watchlist"
          :selected="selectedTicker"
          @select="selectedTicker = $event"
          @add="handleWatchlistAdd"
          @remove="handleWatchlistRemove"
          class="terminal-left-column"
        />

        <!-- 中栏：主行情与智能透视选项卡 -->
        <div class="terminal-center-column">
          <StockCockpit
            :cockpit="cockpit"
            :loading="cockpitLoading"
            :in-watchlist="isTickerInWatchlist"
            @refresh="loadStockCockpit"
            @start-research="handleCockpitResearch"
            @toggle-watchlist="handleWatchlistToggle"
          />

          <!-- 智能透视面板 -->
          <div class="smart-lens-panel">
            <el-tabs v-model="activeLensTab" class="smart-lens-tabs">
              <el-tab-pane label="投研简报" name="brief">
                <LatestBrief
                  :ticker="selectedTicker"
                  :latest-report="cockpit.latestReport"
                  :report-versions="reportVersionRows"
                  :focus-options="focusOptions"
                  :focus-option-labels="focusOptionLabels"
                  :focus-areas="selectedFocusAreas"
                  @update:focus-areas="selectedFocusAreas = $event"
                  @open-report="handleOpenReport"
                  @start-research="sendResearchPrompt"
                />
              </el-tab-pane>
              <el-tab-pane label="关联图谱" name="topology">
                <div class="panel topology-tab-panel">
                  <RelationGraph v-if="selectedTicker" :ticker="selectedTicker" />
                </div>
              </el-tab-pane>
            </el-tabs>
          </div>
        </div>

        <!-- 右栏：市场新闻（替代原冗余的"分析执行舱"，复用 data-service 新闻管线） -->
        <NewsPanel :ticker="selectedTicker" class="terminal-right-column" />
      </section>

      <CompareDesk v-else-if="activeTab === 'compare'" @start-compare="handleCompare" />

      <PortfolioDesk v-else-if="activeTab === 'portfolio'" @start-diagnosis="handleDiagnosis" />

      <section v-else-if="activeTab === 'reports'" class="workspace-grid report-library-grid">
        <div class="panel report-history-panel">
          <div class="panel-header">
            <div>
              <h2>{{ reportTickerNormalized ? `${reportTickerNormalized} 报告库` : reportLabels.aiReportLibrary }}</h2>
            </div>
            <button class="quiet-button" type="button" :disabled="reportVersionsLoading" @click="loadReportVersions">
              <el-icon><Refresh /></el-icon>
              <span>{{ reportVersionsLoading ? '读取中' : '刷新' }}</span>
            </button>
          </div>

          <PanelError
            v-if="reportVersionsError"
            message="报告服务暂时不可用"
            :detail="reportVersionsError"
            @retry="loadReportVersions"
          />
          <PanelEmpty
            v-else-if="reportVersionRows.length === 0"
            title="还没有研究报告"
            hint="先对当前标的运行一次深度研究，生成的报告版本会出现在这里。"
          >
            <template #action>
              <button class="quiet-button" type="button" @click="activeTab = 'research'">去发起研究</button>
            </template>
          </PanelEmpty>
          <div v-else class="report-version-list">
            <button
              v-for="row in reportVersionRows"
              :key="row.id || `${row.ticker}-${row.reportVersion}`"
              class="report-version-row"
              type="button"
              @click="applyReportVersion(row)"
            >
              <span class="version-main">
                <strong>{{ row.versionLabel }}</strong>
                <small>{{ formatDate(row.timeLabel) }}</small>
              </span>
              <span class="version-recommendation">{{ row.recommendation || 'UNKNOWN' }}</span>
              <span class="version-hashes">
                <small>快照 {{ row.snapshotLabel }}</small>
                <small>上下文 {{ row.contextLabel }}</small>
              </span>
              <span class="version-model">{{ row.modelLabel }}</span>
              <span v-if="row.preview" class="version-preview">{{ row.preview }}</span>
            </button>
          </div>
        </div>
      </section>

      <section v-else class="workspace-grid ops-grid">
        <div class="panel">
          <div class="panel-header">
            <div>
              <h2>健康检查</h2>
            </div>
            <button class="primary-button" type="button" :disabled="healthLoading" @click="runHealthChecks">
              <el-icon><Monitor /></el-icon>
              <span>{{ healthLoading ? '检查中' : '运行' }}</span>
            </button>
          </div>

          <div class="score-strip">
            <div>
              <span>核心服务</span>
              <strong>{{ healthSummary.requiredReady }}/{{ healthSummary.requiredTotal }}</strong>
            </div>
            <div>
              <span>可选项</span>
              <strong>{{ healthSummary.optionalReady }}</strong>
            </div>
            <div>
              <span>状态</span>
              <strong :class="healthSummary.status">{{ healthSummary.status }}</strong>
            </div>
          </div>

          <div class="gate-list">
            <div
              v-for="check in healthChecks"
              :key="check.id"
              class="gate-row"
              :class="check.ok ? 'pass' : (check.required === false ? 'pending' : 'fail')"
            >
              <span>{{ check.label }}</span>
              <strong>{{ check.ok ? '就绪' : (check.required === false ? '待验证' : '阻塞') }}</strong>
              <small>{{ check.detail }}</small>
            </div>
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup>
import { computed, markRaw, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  ChatDotRound,
  DataAnalysis,
  Document,
  Monitor,
  Refresh,
  Switch,
  TrendCharts,
  Wallet,
  Sunny,
  Moon,
  Search,
  SwitchButton,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { fetchInvestmentReportVersions, fetchStockCockpit, runWorkbenchHealthChecks, searchWorkbenchStocks } from '../api/workbench.js'
import { addToWatchList, getUserProfile, removeFromWatchList } from '../api/chat.js'
import { getCurrentUser, logout } from '../api/auth.js'
import { useResearchRun } from '../lib/useResearchRun.js'
import WatchlistPanel from '../components/workbench/WatchlistPanel.vue'
import StockCockpit from '../components/workbench/StockCockpit.vue'
import LatestBrief from '../components/workbench/LatestBrief.vue'
import RelationGraph from '../components/workbench/RelationGraph.vue'
import NewsPanel from '../components/workbench/NewsPanel.vue'
import CompareDesk from '../components/workbench/CompareDesk.vue'
import PortfolioDesk from '../components/workbench/PortfolioDesk.vue'
import WorkbenchRunPanel from '../components/workbench/WorkbenchRunPanel.vue'
import PanelEmpty from '../components/common/PanelEmpty.vue'
import PanelError from '../components/common/PanelError.vue'
import { buildPrimaryNavItems, getReportSurfaceLabels } from '../lib/productUi.js'
import {
  buildComparisonPrompt,
  buildEventImpactPrompt,
  buildHealthChecks,
  buildPortfolioDiagnosisPrompt,
  buildResearchPrompt,
  buildWatchlistFromProfile,
  buildTickerSuggestions,
  markHealthCheck,
  normalizeCockpit,
  normalizeTicker,
  summarizeHealthChecks,
  summarizeReportVersions,
} from '../lib/workbench.js'

const router = useRouter()
const isDark = ref(false)
const currentUser = ref(null)
const activeUserName = computed(() => currentUser.value?.nickname || 'StockSage 用户')
const activeUserLabel = computed(() => currentUser.value?.email || '已登录')
const userInitial = computed(() => {
  const source = activeUserName.value || activeUserLabel.value || 'U'
  return source.trim().slice(0, 1).toUpperCase()
})

function initTheme() {
  // 主题类由 App.vue 统一挂载;这里只同步切换按钮的开关状态
  isDark.value = document.documentElement.classList.contains('dark')
}

function toggleTheme() {
  isDark.value = !isDark.value
  if (isDark.value) {
    document.documentElement.classList.add('dark')
    window.localStorage.setItem('stocksage.theme', 'dark')
  } else {
    document.documentElement.classList.remove('dark')
    window.localStorage.setItem('stocksage.theme', 'light')
  }
}

const WATCHLIST_KEY = 'stocksage.watchlist'

const { run: workbenchRun, controls: runPanelControls, start: startRun, stop: stopRun, dismiss: dismissRun, cleanup: cleanupRun } = useResearchRun()

const activeTab = ref('research')
const selectedTicker = ref('NVDA')
const watchlist = ref(loadWatchlist())
const focusOptions = ['valuation', 'growth', 'risks', 'recent filings', 'news', 'technical setup']
const focusOptionLabels = {
  valuation: '估值',
  growth: '增长',
  risks: '风险',
  'recent filings': '最新披露',
  news: '新闻',
  'technical setup': '技术形态',
}
const selectedFocusAreas = ref(['valuation', 'risks', 'recent filings'])
const reportTicker = ref('NVDA')
const healthLoading = ref(false)
const healthChecks = ref(buildHealthChecks({ backend: false, profile: false, rag: false }))
const reportVersions = ref([])
const reportVersionsLoading = ref(false)
const reportVersionsError = ref('')
const cockpitRaw = ref(null)
const cockpitLoading = ref(false)
const cockpitError = ref('')
const isRunPanelCollapsed = ref(false)
const activeLensTab = ref('brief')
let cockpitRequestId = 0

const marketIndexes = ref([
  { name: '上证指数', code: 'SSEC', value: '3,150.20', change: '+14.15', changePercent: '+0.45', points: [20, 25, 23, 28, 30, 27, 32, 35], isUp: true },
  { name: '深证成指', code: 'SZN', value: '10,210.15', change: '+32.45', changePercent: '+0.32', points: [15, 18, 16, 22, 20, 25, 24, 28], isUp: true },
  { name: '恒生指数', code: 'HSI', value: '18,450.60', change: '-107.80', changePercent: '-0.58', points: [40, 38, 35, 32, 36, 30, 28, 25], isUp: false },
  { name: '纳斯达克', code: 'IXIC', value: '16,850.30', change: '+192.15', changePercent: '+1.15', points: [10, 12, 15, 18, 20, 22, 24, 28], isUp: true },
  { name: '标普 500', code: 'SPX', value: '5,430.20', change: '+45.60', changePercent: '+0.85', points: [15, 16, 18, 17, 21, 20, 23, 25], isUp: true },
])

function buildSparkline(points) {
  if (!points || !points.length) return ''
  const width = 50
  const height = 18
  const maxVal = Math.max(...points)
  const minVal = Math.min(...points)
  const span = maxVal - minVal || 1
  return points.map((p, idx) => {
    const x = (idx / (points.length - 1)) * width
    const y = height - ((p - minVal) / span) * height
    return `${idx === 0 ? 'M' : 'L'} ${x.toFixed(1)} ${y.toFixed(1)}`
  }).join(' ')
}


const tabs = [
  { id: 'portfolio', label: '持仓', icon: markRaw(Wallet) },
  { id: 'research', label: '研究', icon: markRaw(DataAnalysis) },
  { id: 'compare', label: '比较', icon: markRaw(Switch) },
  { id: 'reports', label: '报告库', icon: markRaw(Document) },
  { id: 'ops', label: '运维', icon: markRaw(Monitor) },
]

const reportLabels = getReportSurfaceLabels()
const primaryNavItems = buildPrimaryNavItems('/workbench').map(withPrimaryNavIcon)
const activeTabLabel = computed(() => tabs.find(tab => tab.id === activeTab.value)?.label || '工作台')
const researchPrompt = computed(() => buildResearchPrompt(selectedTicker.value, selectedFocusAreas.value))
const healthSummary = computed(() => summarizeHealthChecks(healthChecks.value))
const cockpit = computed(() => normalizeCockpit(cockpitRaw.value || {
  ticker: selectedTicker.value,
  chartStatus: 'DEGRADED',
  chartMessage: cockpitError.value ? '行情数据暂时不可用，请稍后重试。' : '选择一个标的以加载 K 线数据。',
}))
const isTickerInWatchlist = computed(() => watchlist.value.some(item => item.ticker === selectedTicker.value))
const reportTickerNormalized = computed(() => normalizeTicker(reportTicker.value))
const reportVersionRows = computed(() => summarizeReportVersions(reportVersions.value))
// runPanelControls is provided by useResearchRun composable (bound above)

function withPrimaryNavIcon(item) {
  return {
    ...item,
    icon: item.id === 'chat' ? markRaw(ChatDotRound) : markRaw(DataAnalysis),
  }
}

watch(() => workbenchRun.value.status, status => {
  if (status === 'completed') {
    isRunPanelCollapsed.value = true
  }
})

watch(activeTab, tab => {
  if (tab === 'reports') {
    loadReportVersions()
  }
})

watch(selectedTicker, ticker => {
  reportTicker.value = ticker
  cockpitRaw.value = null
  reportVersions.value = []
  reportVersionsError.value = ''
  loadStockCockpit()
  loadReportVersions({ silent: true })
})

watch(watchlist, newVal => {
  try {
    window.localStorage.setItem(WATCHLIST_KEY, JSON.stringify(newVal))
  } catch (error) {
    console.error('Failed to save watchlist to localStorage', error)
  }
}, { deep: true })

onMounted(() => {
  initTheme()
  loadCurrentUser()
  runHealthChecks()
  loadProfileWatchlist()
  loadReportVersions()
  loadStockCockpit()
})

onUnmounted(() => {
  cleanupRun()
})

async function loadCurrentUser() {
  try {
    currentUser.value = await getCurrentUser()
  } catch {
    currentUser.value = null
  }
}

async function handleLogout() {
  try {
    await logout()
  } catch {
    // Session may already be gone; route to the login wall either way.
  } finally {
    currentUser.value = null
    await router.replace('/login')
  }
}

async function loadProfileWatchlist() {
  try {
    const profile = await getUserProfile()
    const rows = buildWatchlistFromProfile(profile)
    if (rows.length > 0) {
      // 载入本地缓存以恢复用户自定义的拖拽排序顺序
      let savedLocal = []
      try {
        savedLocal = JSON.parse(window.localStorage.getItem(WATCHLIST_KEY) || '[]')
      } catch (e) {
        // 忽略异常
      }

      if (Array.isArray(savedLocal) && savedLocal.length > 0) {
        const localOrderMap = new Map()
        savedLocal.forEach((item, index) => {
          if (item && item.ticker) {
            localOrderMap.set(item.ticker, index)
          }
        })

        rows.sort((a, b) => {
          const indexA = localOrderMap.has(a.ticker) ? localOrderMap.get(a.ticker) : Infinity
          const indexB = localOrderMap.has(b.ticker) ? localOrderMap.get(b.ticker) : Infinity
          return indexA - indexB
        })
      }

      watchlist.value = rows
      if (!rows.some(item => item.ticker === selectedTicker.value)) {
        selectedTicker.value = rows[0].ticker
      }
    }
  } catch (error) {
    console.warn('Failed to load profile watchlist, using local fallback', error)
  }
}

/** WatchlistPanel emit handlers */
async function handleWatchlistAdd(raw) {
  const ticker = normalizeTicker(raw)
  if (!ticker) return
  if (watchlist.value.some(item => item.ticker === ticker)) {
    selectedTicker.value = ticker
    return
  }
  const previous = watchlist.value
  watchlist.value = [
    ...watchlist.value,
    { ticker, status: '关注', lastAction: '' },
  ]
  selectedTicker.value = ticker
  try {
    // 持久化到后端 profile，否则刷新时 loadProfileWatchlist 会用服务端列表把它覆盖掉
    await addToWatchList(ticker)
  } catch (error) {
    console.warn('Failed to persist watchlist add', error)
    watchlist.value = previous
  }
}

async function handleWatchlistRemove(ticker) {
  const previous = watchlist.value
  watchlist.value = watchlist.value.filter(item => item.ticker !== ticker)
  try {
    await removeFromWatchList(ticker)
  } catch (err) {
    watchlist.value = previous
    ElMessage.error(`移除 ${ticker} 失败：${err.message}`)
  }
}

function handleWatchlistToggle(ticker) {
  if (watchlist.value.some(item => item.ticker === ticker)) {
    handleWatchlistRemove(ticker)
  } else {
    handleWatchlistAdd(ticker)
  }
}

/** StockCockpit start-research handler */
function handleCockpitResearch({ mode, ticker, eventText, eventSource }) {
  if (mode === 'deep') {
    sendResearchPrompt()
  } else if (mode === 'kline') {
    sendKLinePrompt()
  } else if (mode === 'risk') {
    sendRiskPrompt()
  } else if (mode === 'memo') {
    seedReportFromTicker()
  } else if (mode === 'event') {
    const t = normalizeTicker(ticker || selectedTicker.value)
    const prompt = buildEventImpactPrompt({ ticker: t, eventText, eventSource })
    startWorkbenchRun(prompt, {
      title: `${t} 事件影响分析`,
      ticker: t,
      action: '事件分析中',
    })
  }
}

/** LatestBrief open-report handler */
function handleOpenReport(versionId) {
  activeTab.value = 'reports'
  if (versionId) {
    const row = reportVersionRows.value.find(r => r.id === versionId)
    if (row) applyReportVersion(row)
  }
}

function sendResearchPrompt() {
  startWorkbenchRun(researchPrompt.value, {
    title: `${selectedTicker.value} 单股研究`,
    ticker: selectedTicker.value,
    action: '研究运行中',
  })
}

function sendKLinePrompt() {
  startWorkbenchRun(`Show ${selectedTicker.value} recent K-line trend, volume behavior, and technical risk levels.`, {
    title: `${selectedTicker.value} K 线复核`,
    ticker: selectedTicker.value,
    action: 'K 线复核中',
  })
}

function sendRiskPrompt() {
  startWorkbenchRun(`Find ${selectedTicker.value} filing-backed risk factors and separate direct SEC evidence from inference.`, {
    title: `${selectedTicker.value} 风险证据复核`,
    ticker: selectedTicker.value,
    action: '风险复核中',
  })
}

/** CompareDesk start-compare handler */
function handleCompare({ tickers, dimensions }) {
  const prompt = buildComparisonPrompt(tickers, dimensions)
  startWorkbenchRun(prompt, {
    title: `${tickers.join(' vs ') || '组合'} 对比研究`,
    ticker: tickers[0] || selectedTicker.value,
    action: '对比研究中',
  })
}

/** PortfolioDesk start-diagnosis handler */
function handleDiagnosis({ accountId }) {
  const prompt = buildPortfolioDiagnosisPrompt(accountId)
  startWorkbenchRun(prompt, {
    title: '只读持仓诊断',
    ticker: selectedTicker.value,
    action: '持仓诊断中',
  })
}

function seedReportFromTicker() {
  reportTicker.value = selectedTicker.value
  activeTab.value = 'reports'
  loadReportVersions()
}

function markTickerAction(ticker, action) {
  watchlist.value = watchlist.value.map(item => (
    item.ticker === ticker ? { ...item, status: '进行中', lastAction: action } : item
  ))
}

function startWorkbenchRun(prompt, { title, ticker, action }) {
  if (workbenchRun.value.status === 'running') {
    ElMessage.warning('已有研究正在运行，请先停止或等待完成')
    return
  }

  const symbol = normalizeTicker(ticker || selectedTicker.value)
  isRunPanelCollapsed.value = false

  if (symbol) {
    markTickerAction(symbol, action || '研究运行中')
  }

  startRun(prompt, {
    origin: 'workbench',
    title,
    ticker: symbol,
    onPoll: refreshWorkbenchData,
  })
}

function stopWorkbenchRun() {
  stopRun()
  refreshWorkbenchData()
}

function toggleRunPanel() {
  isRunPanelCollapsed.value = !isRunPanelCollapsed.value
}

function dismissWorkbenchRun() {
  dismissRun()
  isRunPanelCollapsed.value = false
}

function refreshWorkbenchData({ silent = false } = {}) {
  loadStockCockpit({ silent })
  loadReportVersions({ silent })
}

async function runHealthChecks() {
  healthLoading.value = true
  try {
    const result = await runWorkbenchHealthChecks()
    healthChecks.value = buildHealthChecks(result)
  } finally {
    healthLoading.value = false
  }
}

async function loadReportVersions({ silent = false } = {}) {
  if (!silent) {
    reportVersionsLoading.value = true
  }
  reportVersionsError.value = ''
  try {
    reportVersions.value = await fetchInvestmentReportVersions({
      ticker: reportTickerNormalized.value,
      limit: 20,
    })
  } catch (error) {
    reportVersionsError.value = error.message || '未知错误'
  } finally {
    if (!silent) {
      reportVersionsLoading.value = false
    }
  }
}

async function loadStockCockpit({ silent = false } = {}) {
  const ticker = selectedTicker.value
  if (!ticker) return
  const requestId = ++cockpitRequestId
  if (!silent) {
    cockpitLoading.value = true
  }
  cockpitError.value = ''
  try {
    const result = await fetchStockCockpit({ ticker, period: 'daily', days: 120 })
    if (requestId !== cockpitRequestId) return
    cockpitRaw.value = result
    healthChecks.value = markHealthCheck(healthChecks.value, 'cockpit', true, '驾驶舱接口已响应')
    const chartStatus = String(result?.chartStatus || '').toUpperCase()
    markTickerAction(ticker, chartStatus === 'READY'
      ? '驾驶舱已更新'
      : chartStatus === 'SAMPLE'
        ? '演示样本'
        : '数据降级')
  } catch (error) {
    if (requestId !== cockpitRequestId) return
    cockpitError.value = error.message || '未知错误'
    cockpitRaw.value = {
      ticker,
      chartStatus: 'DEGRADED',
      chartMessage: '行情数据暂时不可用，请稍后重试。',
      taskTimeline: [],
      evidencePreview: [],
    }
  } finally {
    if (requestId === cockpitRequestId && !silent) {
      cockpitLoading.value = false
    }
  }
}

function applyReportVersion(row) {
  reportTicker.value = row.ticker || reportTicker.value
}

function formatMetric(value) {
  const number = Number(value)
  if (!Number.isFinite(number)) return '--'
  if (Math.abs(number) <= 1) return number.toFixed(3)
  return number.toFixed(1)
}

function formatDate(value) {
  if (!value) return '--'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return date.toLocaleString()
}

function loadWatchlist() {
  try {
    const saved = JSON.parse(window.localStorage.getItem(WATCHLIST_KEY) || '[]')
    if (Array.isArray(saved) && saved.length > 0) return saved
  } catch {
    // Ignore malformed local storage and fall back to a stable demo list.
  }
  return ['NVDA', 'AAPL', 'MSFT', 'AMZN', 'META'].map(ticker => ({
    ticker,
    status: '',
    lastAction: '',
  }))
}

// 搜索框移到最上面，相关状态与逻辑
const tickerDraft = ref('')
const showSuggestions = ref(false)
const activeSuggestionIndex = ref(0)
const searchCandidates = ref([])

const suggestions = computed(() => buildTickerSuggestions(tickerDraft.value, watchlist.value, 6, searchCandidates.value))
const suggestionsVisible = computed(() => showSuggestions.value && suggestions.value.length > 0)

let searchTimer = 0
let searchSequence = 0

watch(tickerDraft, value => {
  activeSuggestionIndex.value = 0
  showSuggestions.value = true
  searchCandidates.value = []
  if (searchTimer) {
    window.clearTimeout(searchTimer)
  }

  const query = value.trim()
  if (!query) {
    searchSequence += 1
    return
  }

  const sequence = ++searchSequence
  searchTimer = window.setTimeout(async () => {
    try {
      const results = await searchWorkbenchStocks({ query, limit: 8 })
      if (sequence === searchSequence) {
        searchCandidates.value = results
      }
    } catch {
      if (sequence === searchSequence) {
        searchCandidates.value = []
      }
    }
  }, 180)
})

function handleSearchSubmit() {
  const selected = suggestionsVisible.value ? suggestions.value[activeSuggestionIndex.value] : null
  const raw = selected?.ticker || tickerDraft.value.trim()
  if (!raw) return
  handleSearchSelect(raw)
  tickerDraft.value = ''
  showSuggestions.value = false
}

function chooseSuggestion(suggestion) {
  if (!suggestion?.ticker) return
  handleSearchSelect(suggestion.ticker)
  tickerDraft.value = ''
  showSuggestions.value = false
}

function moveSuggestion(step) {
  if (!suggestions.value.length) return
  showSuggestions.value = true
  const next = activeSuggestionIndex.value + step
  activeSuggestionIndex.value = (next + suggestions.value.length) % suggestions.value.length
}

function hideSuggestionsSoon() {
  window.setTimeout(() => {
    showSuggestions.value = false
  }, 120)
}

function handleSearchSelect(raw) {
  const ticker = normalizeTicker(raw)
  if (!ticker) return
  selectedTicker.value = ticker
}
</script>

<!-- 工作台样式刻意不加 scoped：研究台/对比/持仓/运行面板已拆为子组件，
     这些面板类名（panel/watch-row/action-strip 等）需要穿透到子组件内部。
     类名均为 workbench 专属，不影响聊天页。 -->
<style>
.workbench-shell {
  height: 100vh;
  min-height: 0;
  display: grid;
  grid-template-columns: 248px minmax(0, 1fr);
  background: var(--app-bg);
  color: var(--text-primary);
  transition: background-color 0.15s ease, color 0.15s ease;
}

.workbench-rail {
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 20px 16px;
  border-right: 1px solid var(--border-soft);
  background: var(--surface);
  transition: background-color 0.15s ease, border-color 0.15s ease;
}

.rail-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px;
  border-radius: 10px;
  color: inherit;
  text-decoration: none;
}

.brand-mark {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--text-primary);
  color: var(--surface);
  box-shadow: 0 4px 10px rgba(0, 0, 0, 0.1);
  transition: all 0.15s ease;
}

.rail-brand strong,
.rail-brand small {
  display: block;
}

.rail-brand strong {
  font-size: 16px;
  letter-spacing: -0.02em;
}

.rail-brand small {
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 500;
}

.primary-nav.rail-switch {
  display: flex;
  margin-top: 20px;
}

.rail-switch .primary-nav-link {
  flex: 1;
}

.rail-nav {
  display: grid;
  gap: 6px;
  margin-top: 18px;
}

.rail-button,
.quiet-button,
.primary-button,
.action-strip button,
.file-button {
  min-height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  font-weight: 600;
  font-size: 12.5px;
  letter-spacing: -0.01em;
  cursor: pointer;
  text-decoration: none;
  transition: all 0.15s ease;
}

.rail-button {
  min-height: 38px;
  justify-content: flex-start;
  padding: 0 12px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
}

.rail-button.active,
.rail-button:hover {
  background: var(--surface-raised);
  color: var(--text-primary);
  border-color: var(--border-soft);
}

.rail-footer {
  margin-top: auto;
  padding-top: 16px;
  border-top: 1px solid var(--border-soft);
}

.rail-user {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 9px;
  margin-bottom: 10px;
  padding: 8px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface);
}

.rail-user-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  background: var(--accent-soft);
  color: var(--accent-dark);
  font-size: 12px;
  font-weight: 800;
}

.rail-user span:last-child {
  min-width: 0;
  display: grid;
  gap: 2px;
}

.rail-user strong,
.rail-user small {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rail-user strong {
  color: var(--text-primary);
  font-size: 12px;
}

.rail-user small {
  color: var(--text-muted);
  font-size: 11px;
}

.theme-toggle-btn {
  width: 100%;
  min-height: 38px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface);
  color: var(--text-secondary);
  font-weight: 600;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.theme-toggle-btn:hover {
  background: var(--surface-raised);
  color: var(--text-primary);
  border-color: var(--border-strong);
}

.theme-toggle-btn + .theme-toggle-btn {
  margin-top: 8px;
}

.workbench-main {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.workbench-topbar {
  position: relative;
  z-index: 100;
  min-height: 52px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 8px 20px;
  border-bottom: 1px solid var(--border-soft);
  background: var(--surface);
  backdrop-filter: blur(12px);
  transition: background-color 0.15s ease, border-color 0.15s ease;
}

.eyebrow,
.panel-kicker {
  margin: 0;
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.workbench-topbar h1,
.panel h2 {
  margin: 3px 0 0;
  line-height: 1.2;
  letter-spacing: -0.02em;
}

.workbench-topbar h1 {
  font-size: 17px;
  font-weight: 700;
}

.panel h2 {
  font-size: 15px;
  font-weight: 600;
}

.quiet-button,
.primary-button,
.file-button {
  padding: 0 12px;
}

.quiet-button {
  background: var(--surface);
  color: var(--text-secondary);
  border-color: var(--border-soft);
}

.quiet-button:hover:not(:disabled) {
  background: var(--surface-raised);
  color: var(--text-primary);
  border-color: var(--border-strong);
}

.primary-button,
.file-button {
  background: var(--accent);
  color: #fff;
}

.primary-button:hover:not(:disabled),
.file-button:hover {
  background: var(--accent-dark);
}

.primary-button:disabled {
  cursor: not-allowed;
  opacity: 0.6;
  transform: none;
  box-shadow: none;
}

.workspace-grid {
  min-height: 0;
  flex: 1;
  display: grid;
  gap: 12px;
  padding: 14px;
  overflow: auto;
}

.research-cockpit-grid {
  grid-template-columns: 280px minmax(520px, 1fr) minmax(300px, 360px);
  align-items: start;
}

.cockpit-main {
  display: grid;
  gap: 16px;
  min-width: 0;
}

.chart-cockpit-panel {
  padding: 16px;
}

.chart-cockpit-panel .kline-panel {
  margin: 0;
  box-shadow: none;
  border: none;
}

.chart-status-strip {
  display: flex;
  align-items: center;
  gap: 9px;
  min-height: 34px;
  margin: -4px 0 12px;
  padding: 6px 10px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface-raised);
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 500;
}

.chart-status-strip small {
  margin-left: auto;
  color: var(--text-muted);
  font-weight: 500;
}

.status-pill {
  min-height: 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 8px;
  border: 1px solid var(--border-soft);
  border-radius: 999px;
  background: var(--surface-raised);
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  white-space: nowrap;
}

.status-pill.ready,
.status-pill.positive {
  border-color: transparent;
  background: var(--positive-soft);
  color: var(--positive);
}

.status-pill.degraded,
.status-pill.danger {
  border-color: transparent;
  background: var(--negative-soft);
  color: var(--negative);
}

.status-pill.sample {
  border-color: transparent;
  background: var(--warning-soft);
  color: var(--warning);
}

.status-pill.running {
  border-color: transparent;
  background: var(--info-soft);
  color: var(--info);
}

.status-pill.pending {
  border-color: transparent;
  background: var(--surface-raised);
  color: var(--text-muted);
}

.cockpit-actions {
  margin-top: 12px;
}

.cockpit-lower-grid {
  display: grid;
  grid-template-columns: minmax(0, 0.95fr) minmax(0, 1.05fr);
  gap: 14px;
}

.task-list,
.evidence-list {
  display: grid;
  gap: 8px;
}

.task-row,
.evidence-row,
.report-brief-card {
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface);
}

.task-row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 7px 9px;
  align-items: center;
  padding: 10px;
}

.task-row strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-row small,
.task-row p,
.evidence-row small,
.evidence-row em,
.empty-copy {
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.45;
}

.task-row p {
  grid-column: 1 / -1;
  margin: 0;
  color: var(--danger);
}

.evidence-row {
  display: grid;
  gap: 6px;
  padding: 10px;
}

.evidence-row span,
.focus-caption,
.provenance-grid span {
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
}

.evidence-row strong {
  color: var(--text-primary);
  font-size: 13px;
  line-height: 1.42;
}

.evidence-row em {
  font-style: normal;
  font-weight: 700;
}

.cockpit-brief-panel {
  position: sticky;
  top: 18px;
}

.report-brief-card {
  display: grid;
  gap: 10px;
  padding: 12px;
  background: var(--surface);
}

.brief-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.brief-title-row strong {
  font-size: 15px;
}

.brief-title-row span {
  min-height: 26px;
  display: inline-flex;
  align-items: center;
  padding: 0 8px;
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--positive);
  font-size: 11px;
  font-weight: 600;
}

.report-brief-card p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.5;
}

.provenance-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 7px;
}

.provenance-grid div {
  min-width: 0;
  padding: 8px;
  border: 1px solid rgba(203, 215, 209, 0.84);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.72);
}

.provenance-grid strong {
  display: block;
  margin-top: 5px;
  overflow-wrap: anywhere;
  color: var(--text-primary);
  font-size: 12px;
}

.dossier-card.compact {
  margin-top: 12px;
}

.dossier-card.compact .dossier-hero strong {
  font-size: 20px;
}

.dossier-card.compact .dossier-metrics {
  grid-template-columns: 1fr;
}

.focus-caption {
  display: block;
  margin: 14px 0 7px;
}

.cockpit-prompt {
  margin-top: 12px;
  max-height: 190px;
  overflow: auto;
}

.empty-copy {
  margin: 0;
}

.compare-grid,
.event-grid,
.portfolio-grid {
  grid-template-columns: minmax(320px, 760px);
  justify-content: center;
}

.report-library-grid {
  grid-template-columns: minmax(480px, 960px);
}

.ops-grid {
  grid-template-columns: minmax(480px, 760px);
}

.panel {
  min-width: 0;
  align-self: start;
  padding: 14px;
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-soft);
  transition: background-color 0.15s ease, border-color 0.15s ease;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.ticker-form {
  display: grid;
  grid-template-columns: 96px 38px;
  gap: 6px;
}

.ticker-form input,
.field input,
.field select,
.field textarea {
  width: 100%;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface-raised);
  color: var(--text-primary);
  font-size: 13px;
  transition: all 0.15s ease;
}

.ticker-form input {
  height: 38px;
  padding: 0 12px;
  text-transform: uppercase;
}

.ticker-form button {
  border: 0;
  border-radius: 8px;
  background: var(--accent);
  color: #fff;
  cursor: pointer;
  box-shadow: 0 2px 8px var(--accent-soft);
  transition: all 0.15s ease;
}

.ticker-form button:hover {
  transform: scale(1.05);
  box-shadow: 0 4px 12px var(--accent-soft);
}

.ticker-form button:active {
  transform: scale(1);
}

.dossier-card {
  margin: 0 0 14px;
  padding: 14px;
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-md);
  background: var(--surface-raised);
  transition: background-color 0.15s ease, border-color 0.15s ease;
}

.dossier-hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--border-soft);
}

.dossier-hero span,
.dossier-metric span,
.dossier-actions span {
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 700;
  text-transform: uppercase;
}

.dossier-hero strong {
  color: var(--text-primary);
  font-size: 20px;
  line-height: 1;
}

.dossier-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-top: 10px;
}

.dossier-metric {
  min-width: 0;
  padding: 9px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.78);
}

.dossier-metric strong {
  display: block;
  margin-top: 6px;
  overflow: hidden;
  color: var(--text-primary);
  font-size: 13px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dossier-metric.positive strong {
  color: var(--positive);
}

.dossier-metric.danger strong {
  color: var(--danger);
}

.dossier-metric.info strong {
  color: var(--info);
}

.dossier-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.dossier-actions span {
  min-height: 24px;
  display: inline-flex;
  align-items: center;
  padding: 0 8px;
  border: 1px solid var(--border-soft);
  border-radius: 999px;
  background: var(--surface-raised);
  color: var(--text-secondary);
  text-transform: none;
}

.focus-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.check-chip {
  min-height: 34px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 0 12px;
  border: 1px solid var(--border-soft);
  border-radius: 999px;
  background: var(--surface);
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s ease;
}

.check-chip:hover {
  border-color: var(--border-strong);
  background: var(--surface-raised);
}

.check-chip:has(input:checked) {
  background: var(--accent-soft);
  border-color: var(--accent);
  color: var(--text-primary);
}

.prompt-preview,
.prompt-panel pre {
  margin: 14px 0 0;
  padding: 14px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface-raised);
  color: var(--text-primary);
  font-family: Consolas, "Liberation Mono", monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.settings-summary {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}

.settings-summary.flat {
  margin-top: 12px;
}

.summary-row {
  min-width: 0;
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr);
  gap: 10px;
  align-items: start;
  padding: 9px 12px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface);
}

.summary-row span {
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 700;
}

.summary-row strong {
  min-width: 0;
  color: var(--text-primary);
  font-size: 13px;
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.report-stack {
  min-width: 0;
  display: grid;
  gap: 14px;
  align-self: start;
}

.report-version-list {
  display: grid;
  gap: 8px;
}

.report-version-row {
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(120px, 1fr) auto;
  gap: 7px 10px;
  align-items: center;
  width: 100%;
  padding: 12px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface);
  color: var(--text-primary);
  text-align: left;
  cursor: pointer;
  transition: all 0.15s ease;
}

.report-version-row:hover {
  border-color: var(--accent);
}

.version-main {
  min-width: 0;
  display: grid;
  gap: 2px;
}

.version-main strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.version-main small,
.version-hashes small,
.version-model,
.version-preview {
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 500;
}

.version-recommendation {
  min-height: 26px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 10px;
  border: none;
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 11px;
  font-weight: 700;
}

.version-hashes,
.version-model,
.version-preview {
  grid-column: 1 / -1;
}

.version-hashes {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 12px;
}

.version-preview {
  overflow: hidden;
  line-height: 1.45;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.report-empty {
  padding: 12px;
  border: 1px dashed var(--border-strong);
  border-radius: 8px;
  background: var(--surface-raised);
  color: var(--text-muted);
  font-size: 13px;
  font-weight: 600;
}

.action-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
}

.action-strip button {
  padding: 0 12px;
  background: var(--surface);
  color: var(--text-secondary);
  border-color: var(--border-soft);
}

.action-strip button:hover {
  background: var(--surface-raised);
  color: var(--text-primary);
  border-color: var(--border-strong);
}

.field {
  display: grid;
  gap: 6px;
  margin-top: 12px;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 700;
  text-transform: uppercase;
}

.field input,
.field select {
  height: 40px;
  padding: 0 11px;
}

.field textarea {
  padding: 11px;
  resize: vertical;
  text-transform: none;
}

.report-preview pre {
  max-height: 620px;
  overflow: auto;
}

.file-button {
  position: relative;
  overflow: hidden;
}

.file-button input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}

.score-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.score-strip div {
  min-height: 70px;
  padding: 11px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface);
}

.score-strip span,
.gate-row small {
  display: block;
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 700;
  text-transform: uppercase;
}

.score-strip strong {
  display: block;
  margin-top: 8px;
  font-size: 20px;
}

.score-strip strong.pass,
.score-strip strong.fail,
.gate-row.pass strong,
.gate-row.fail strong {
  text-transform: uppercase;
}

.score-strip strong.pass,
.gate-row.pass strong {
  color: var(--positive);
}

.score-strip strong.fail,
.gate-row.fail strong {
  color: var(--negative);
}

.gate-row.pending strong {
  color: var(--text-muted);
  text-transform: uppercase;
}

.gate-list,
.case-list {
  display: grid;
  gap: 7px;
  margin-top: 14px;
}

.metric-section {
  margin-top: 16px;
}

.gate-list.compact {
  margin-top: 8px;
}

.insight-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 12px 0 0;
}

.insight-grid span,
.read-only-banner {
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface);
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 600;
}

.insight-grid span {
  min-height: 32px;
  display: inline-flex;
  align-items: center;
  padding: 0 10px;
}

.read-only-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  padding: 10px 11px;
  background: var(--accent-soft);
}

.gate-row,
.case-row {
  min-height: 44px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
  padding: 9px 12px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface);
  transition: background-color 0.15s ease, border-color 0.15s ease;
}

.gate-row small {
  grid-column: 1 / -1;
  text-transform: none;
}

.gate-row.missing {
  opacity: 0.72;
}

.case-row {
  grid-template-columns: minmax(0, 1fr) auto auto;
}

.case-row p,
.context-list {
  grid-column: 1 / -1;
  margin: 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.45;
}

.context-list {
  padding-left: 18px;
}

.case-row strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.case-row span,
.case-row small {
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 700;
}

/* ==========================================================================
   全球大盘指数 Banner (Market Ribbon)
   ========================================================================== */
.market-ribbon-container {
  width: 100%;
  overflow-x: auto;
  background: var(--surface-raised);
  border-bottom: 1px solid var(--border-soft);
  padding: 8px 16px;
  display: flex;
  align-items: center;
  scrollbar-width: none; /* Hide scrollbar for Firefox */
}

.market-ribbon-container::-webkit-scrollbar {
  display: none; /* Hide scrollbar for Chrome/Safari */
}

.market-ribbon {
  display: flex;
  gap: 12px;
  min-width: max-content;
}

.market-index-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 12px;
  background: var(--surface);
  border: 1px solid var(--border-soft);
  border-radius: 6px;
  box-shadow: var(--shadow-soft);
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}

.market-index-card.up {
  color: var(--positive);
}

.market-index-card.down {
  color: var(--negative);
}

.index-meta {
  display: flex;
  flex-direction: column;
  line-height: 1.1;
}

.index-name {
  font-size: 11px;
  font-weight: 700;
  color: var(--text-primary);
}

.index-code {
  font-size: 9px;
  color: var(--text-muted);
}

.index-data {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  line-height: 1.1;
}

.index-val {
  font-size: 13px;
  font-weight: 700;
  color: var(--text-primary);
}

.index-pct {
  font-size: 10px;
  font-weight: 700;
}

.sparkline-svg {
  opacity: 0.85;
  margin-left: 4px;
}

/* ==========================================================================
   彭博终端风格三栏式宽屏布局 (Bloomberg Terminal Style)
   ========================================================================== */
.terminal-three-column-grid {
  display: grid;
  grid-template-columns: 232px minmax(0, 1fr) 312px;
  gap: 12px;
  padding: 12px;
  height: calc(100vh - 52px);
  overflow: hidden;
}

.terminal-left-column {
  height: 100%;
  overflow-y: auto;
  align-self: stretch;
}

.terminal-center-column {
  height: 100%;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-self: stretch;
}

.terminal-right-column {
  height: 100%;
  overflow-y: auto;
  align-self: stretch;
}

/* ==========================================================================
   智能透视面板 (Smart Lens Tabs)
   ========================================================================== */
.smart-lens-panel {
  background: var(--surface);
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-soft);
  padding: 16px;
  margin-bottom: 16px;
}

.smart-lens-tabs :deep(.el-tabs__header) {
  margin: 0 0 16px 0;
}

.smart-lens-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background-color: var(--border-soft);
}

.smart-lens-tabs :deep(.el-tabs__item) {
  font-size: 13px;
  font-weight: 700;
  color: var(--text-secondary);
  height: 38px;
  line-height: 38px;
  padding: 0 16px;
}

.smart-lens-tabs :deep(.el-tabs__item.is-active) {
  color: var(--accent);
}

.smart-lens-tabs :deep(.el-tabs__active-bar) {
  background-color: var(--accent);
  height: 2px;
}

.topology-tab-panel {
  border: 0;
  box-shadow: none;
  padding: 0;
  background: transparent;
}

@media (max-width: 1080px) {
  .workbench-shell {
    grid-template-columns: 1fr;
    grid-template-rows: auto minmax(0, 1fr);
  }

  .workbench-rail {
    min-height: auto;
    padding: 10px 12px;
    border-right: 0;
    border-bottom: 1px solid var(--border-soft);
  }

  .rail-brand {
    padding: 4px 8px 8px;
  }

  .rail-nav {
    display: flex;
    margin-top: 8px;
    overflow-x: auto;
    padding-bottom: 2px;
  }

  .rail-button {
    flex: 0 0 auto;
  }

  .terminal-three-column-grid,
  .compare-grid,
  .event-grid,
  .portfolio-grid,
  .report-library-grid,
  .ops-grid {
    grid-template-columns: 1fr;
    height: auto;
    overflow: visible;
  }

  .terminal-left-column,
  .terminal-center-column,
  .terminal-right-column {
    height: auto;
    overflow: visible;
  }

  .cockpit-brief-panel {
    position: static;
  }

  .cockpit-lower-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .workbench-topbar {
    align-items: flex-start;
    flex-direction: column;
    padding: 14px;
  }

  .workspace-grid {
    gap: 10px;
    padding: 10px;
  }

  .score-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .watch-row,
  .case-row {
    grid-template-columns: 1fr;
  }

  .summary-row {
    grid-template-columns: 1fr;
  }

  .panel {
    padding: 12px;
  }

  .panel-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .run-actions,
  .run-meta,
  .action-strip {
    width: 100%;
  }

  .run-answer pre {
    max-height: 220px;
  }

  .chart-status-strip {
    align-items: flex-start;
    flex-direction: column;
  }

  .chart-status-strip small {
    margin-left: 0;
  }

  .ticker-form {
    width: 100%;
    grid-template-columns: minmax(0, 1fr) 38px;
  }

  .watch-row,
  .task-row,
  .report-version-row {
    grid-template-columns: 1fr;
  }
}

/* 顶部搜索框与联想下拉菜单样式 */
.topbar-search-container {
  position: relative;
  width: 100%;
  max-width: 320px;
  margin-left: auto;
}

.topbar-search-container .ticker-form {
  display: flex;
  align-items: center;
  background: var(--surface-raised);
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-md);
  padding: 2px 3px 2px 12px;
  transition: all 0.15s ease;
  box-shadow: var(--shadow-sm);
}

.topbar-search-container .ticker-form:focus-within {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft), var(--shadow-soft);
  background: var(--surface);
}

.topbar-search-container .ticker-form input {
  flex: 1;
  height: 30px;
  border: none !important;
  background: transparent !important;
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 500;
  text-transform: uppercase;
  outline: none !important;
  padding: 0;
  box-shadow: none !important;
}

.topbar-search-container .ticker-form button {
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: 8px;
  background: var(--accent);
  color: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s ease;
  box-shadow: none;
}

.topbar-search-container .ticker-form button:hover {
  background: var(--accent-dark);
}

.ticker-suggestions {
  position: absolute;
  z-index: 99;
  top: calc(100% + 6px);
  right: 0;
  left: 0;
  display: grid;
  gap: 4px;
  padding: 6px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface);
  box-shadow: var(--shadow-command);
  backdrop-filter: blur(12px);
  transition: background-color 0.15s ease, border-color 0.15s ease;
}

.ticker-suggestion-row {
  min-width: 0;
  min-height: 42px;
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  padding: 0 9px;
  border: 1px solid transparent;
  border-radius: 7px;
  background: transparent;
  color: var(--text-secondary);
  text-align: left;
  cursor: pointer;
  transition: all 0.15s ease;
}

.ticker-suggestion-row.active,
.ticker-suggestion-row:hover {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--text-primary);
}

.ticker-suggestion-row strong,
.ticker-suggestion-row span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ticker-suggestion-row strong {
  color: var(--text-primary);
}

.ticker-suggestion-row small {
  min-height: 22px;
  display: inline-flex;
  align-items: center;
  padding: 0 7px;
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 11px;
  font-weight: 600;
}
</style>
