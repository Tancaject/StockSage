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

        <!-- 右栏：后台研究任务与市场新闻 -->
        <div class="terminal-right-column terminal-right-stack">
          <section v-if="cockpit.taskTimeline.length" class="panel task-monitor-panel">
            <div class="panel-header">
              <div>
                <h2>研究任务进度</h2>
              </div>
            </div>
            <div class="task-list">
              <div v-for="task in cockpit.taskTimeline" :key="task.id" class="task-row">
                <span class="status-pill" :class="task.tone">{{ task.statusLabel }}</span>
                <strong>{{ task.stageLabel }}</strong>
                <small>{{ task.attempts }} 次尝试 · {{ formatDate(task.timeLabel) }}</small>
                <p v-if="task.resultKindLabel">{{ task.resultKindLabel }}</p>
                <p v-if="task.errorMessage">{{ task.errorMessage }}</p>
                <p v-if="task.deadLettered" class="task-dlq-hint">{{ task.recoveryHint }}</p>
              </div>
            </div>
          </section>
          <NewsPanel :ticker="selectedTicker" />
        </div>
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

          <div v-if="reportVersionsLoading" class="report-library-state" role="status">
            正在读取报告历史…
          </div>
          <PanelError
            v-else-if="reportVersionsError"
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
              :class="{ selected: String(selectedReportId) === String(row.id) }"
              type="button"
              :aria-pressed="String(selectedReportId) === String(row.id)"
              @click="applyReportVersion(row)"
            >
              <span class="version-main">
                <strong>{{ row.versionLabel }}</strong>
                <small>{{ formatDate(row.timeLabel) }}</small>
              </span>
              <span class="version-statuses">
                <span class="version-recommendation">{{ row.recommendation || 'UNKNOWN' }}</span>
                <span class="version-review-status" :class="reviewStatusClass(row.reviewStatus)">
                  {{ row.reviewStatusLabel }}
                </span>
              </span>
              <span class="version-hashes">
                <small>快照 {{ row.snapshotLabel }}</small>
                <small>上下文 {{ row.contextLabel }}</small>
              </span>
              <span class="version-model">{{ row.modelLabel }}</span>
              <span v-if="row.preview" class="version-preview">{{ row.preview }}</span>
            </button>
          </div>
        </div>

        <ReportReviewPanel
          :detail="reportDetail"
          :loading="reportDetailLoading"
          :error="reportDetailError"
          :saving="reportReviewSaving"
          :save-error="reportReviewError"
          @retry="reloadSelectedReport"
          @submit-review="handleReportReview"
        />
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
import {
  fetchInvestmentReportDetail,
  fetchInvestmentReportVersions,
  fetchStockCockpit,
  runWorkbenchHealthChecks,
  searchWorkbenchStocks,
  updateInvestmentReportReview,
} from '../api/workbench.js'
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
import ReportReviewPanel from '../components/workbench/ReportReviewPanel.vue'
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
  normalizeInvestmentReportDetail,
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
const selectedReportId = ref(null)
const reportDetail = ref(null)
const reportDetailLoading = ref(false)
const reportDetailError = ref('')
const reportReviewSaving = ref(false)
const reportReviewError = ref('')
const cockpitRaw = ref(null)
const cockpitLoading = ref(false)
const cockpitError = ref('')
const isRunPanelCollapsed = ref(false)
const activeLensTab = ref('brief')
let cockpitRequestId = 0
let reportVersionsRequestId = 0
let reportDetailRequestId = 0

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
const isTickerInWatchlist = computed(() => Boolean(
  watchlist.value.find(item => item.ticker === selectedTicker.value)?.isWatched,
))
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
  if (tab === 'reports' && !reportVersionsLoading.value) {
    loadReportVersions()
  }
})

watch(selectedTicker, ticker => {
  reportVersionsRequestId += 1
  reportDetailRequestId += 1
  reportTicker.value = ticker
  cockpitRaw.value = null
  reportVersions.value = []
  reportVersionsError.value = ''
  selectedReportId.value = null
  reportDetail.value = null
  reportDetailLoading.value = false
  reportDetailError.value = ''
  reportReviewError.value = ''
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
  const existing = watchlist.value.find(item => item.ticker === ticker)
  if (existing?.isWatched) {
    selectedTicker.value = ticker
    return
  }
  const previous = watchlist.value
  watchlist.value = existing
    ? watchlist.value.map(item => item.ticker === ticker
      ? {
          ...item,
          isWatched: true,
          isSample: false,
          status: item.isHeld ? '持仓/关注' : '关注',
        }
      : item)
    : [
        ...watchlist.value,
        { ticker, status: '关注', isHeld: false, isWatched: true, isSample: false, lastAction: '' },
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
  const existing = watchlist.value.find(item => item.ticker === ticker)
  if (!existing?.isWatched) return
  const previous = watchlist.value
  watchlist.value = existing.isHeld
    ? watchlist.value.map(item => item.ticker === ticker
      ? { ...item, isWatched: false, status: '持仓' }
      : item)
    : watchlist.value.filter(item => item.ticker !== ticker)
  try {
    await removeFromWatchList(ticker)
  } catch (err) {
    watchlist.value = previous
    ElMessage.error(`移除 ${ticker} 失败：${err.message}`)
  }
}

function handleWatchlistToggle(ticker) {
  if (watchlist.value.find(item => item.ticker === ticker)?.isWatched) {
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
async function handleOpenReport(versionId) {
  activeTab.value = 'reports'
  if (versionId) {
    const row = reportVersionRows.value.find(r => String(r.id) === String(versionId))
    if (row) {
      await applyReportVersion(row)
    } else {
      await loadReportVersions({ preferredReportId: versionId })
    }
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

async function loadReportVersions({ silent = false, preferredReportId = null } = {}) {
  const requestId = ++reportVersionsRequestId
  const requestedTicker = reportTickerNormalized.value
  let targetRow = null
  if (!silent) {
    reportVersionsLoading.value = true
  }
  reportVersionsError.value = ''
  try {
    const rows = await fetchInvestmentReportVersions({
      ticker: requestedTicker,
      limit: 20,
    })
    if (
      requestId !== reportVersionsRequestId
      || requestedTicker !== reportTickerNormalized.value
    ) return
    reportVersions.value = Array.isArray(rows) ? rows : []

    if (activeTab.value === 'reports') {
      const targetId = preferredReportId ?? selectedReportId.value
      targetRow = targetId === null || targetId === undefined
        ? reportVersions.value[0]
        : reportVersions.value.find(row => String(row?.id) === String(targetId))
          || reportVersions.value[0]

      if (!targetRow) {
        reportDetailRequestId += 1
        selectedReportId.value = null
        reportDetail.value = null
        reportDetailLoading.value = false
        reportDetailError.value = ''
      }
    }
  } catch (error) {
    if (requestId !== reportVersionsRequestId) return
    reportVersionsError.value = error.message || '未知错误'
  } finally {
    if (requestId === reportVersionsRequestId) {
      reportVersionsLoading.value = false
    }
  }

  if (
    requestId === reportVersionsRequestId
    &&
    targetRow
    && (
      String(selectedReportId.value) !== String(targetRow.id)
      || (!reportDetail.value && !reportDetailLoading.value)
    )
  ) {
    await applyReportVersion(targetRow)
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

async function applyReportVersion(row) {
  const reportId = row?.id
  if (reportId === null || reportId === undefined || String(reportId).trim() === '') return

  const requestId = ++reportDetailRequestId
  selectedReportId.value = reportId
  reportTicker.value = row.ticker || reportTicker.value
  reportDetail.value = null
  reportDetailLoading.value = true
  reportDetailError.value = ''
  reportReviewError.value = ''

  try {
    const payload = await fetchInvestmentReportDetail(reportId)
    if (requestId !== reportDetailRequestId || String(selectedReportId.value) !== String(reportId)) return
    const normalized = normalizeInvestmentReportDetail(payload)
    if (normalized.summary.id === null || normalized.summary.id === undefined) {
      normalized.summary.id = reportId
    }
    reportDetail.value = normalized
  } catch (error) {
    if (requestId !== reportDetailRequestId || String(selectedReportId.value) !== String(reportId)) return
    reportDetailError.value = error.message || '未知错误'
  } finally {
    if (requestId === reportDetailRequestId && String(selectedReportId.value) === String(reportId)) {
      reportDetailLoading.value = false
    }
  }
}

function reloadSelectedReport() {
  if (selectedReportId.value === null || selectedReportId.value === undefined) return
  const row = reportVersionRows.value.find(
    item => String(item.id) === String(selectedReportId.value),
  )
  applyReportVersion(row || {
    id: selectedReportId.value,
    ticker: reportTickerNormalized.value,
  })
}

async function handleReportReview({ status, comment, expectedLockVersion }) {
  const reportId = selectedReportId.value
  if (reportId === null || reportId === undefined || reportReviewSaving.value) return

  reportReviewSaving.value = true
  reportReviewError.value = ''
  try {
    const payload = await updateInvestmentReportReview(reportId, {
      status,
      comment,
      expectedLockVersion,
    })
    const normalized = normalizeInvestmentReportDetail(payload)
    if (normalized.summary.id === null || normalized.summary.id === undefined) {
      normalized.summary.id = reportId
    }

    reportVersionsRequestId += 1
    reportVersionsLoading.value = false
    reportVersions.value = reportVersions.value.map(row => (
      String(row?.id) === String(reportId)
        ? { ...row, ...normalized.summary }
        : row
    ))
    if (String(selectedReportId.value) === String(reportId)) {
      reportDetail.value = normalized
    }
    ElMessage.success(`审核状态已更新为${normalized.summary.reviewStatusLabel}`)
  } catch (error) {
    if (String(selectedReportId.value) === String(reportId)) {
      reportReviewError.value = error.message || '审核更新失败'
    }
  } finally {
    reportReviewSaving.value = false
  }
}

function reviewStatusClass(status) {
  return String(status || 'unknown').trim().toLowerCase().replaceAll('_', '-')
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

.task-list {
  display: grid;
  gap: 8px;
}

.task-row {
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

.cockpit-brief-panel {
  position: sticky;
  top: 18px;
}

.empty-copy {
  margin: 0;
}

.compare-grid,
.portfolio-grid {
  grid-template-columns: minmax(320px, 760px);
  justify-content: center;
}

.report-library-grid {
  grid-template-columns: minmax(300px, 380px) minmax(0, 1fr);
  align-items: start;
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

.report-version-row.selected {
  border-color: var(--accent);
  background: var(--accent-soft);
  box-shadow: inset 3px 0 0 var(--accent);
}

.report-library-state {
  min-height: 120px;
  display: grid;
  place-items: center;
  padding: 18px;
  border: 1px dashed var(--border-strong);
  border-radius: 8px;
  background: var(--surface-raised);
  color: var(--text-muted);
  font-size: 12.5px;
  font-weight: 600;
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

.version-statuses {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 5px;
}

.version-review-status {
  min-height: 26px;
  display: inline-flex;
  align-items: center;
  padding: 0 9px;
  border-radius: 999px;
  background: var(--surface-raised);
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 700;
}

.version-review-status.in-review {
  background: var(--accent-soft);
  color: var(--accent);
}

.version-review-status.approved {
  background: rgba(37, 160, 105, 0.14);
  color: var(--positive);
}

.version-review-status.needs-research {
  background: rgba(217, 142, 35, 0.14);
  color: #b36d0d;
}

.version-review-status.rejected {
  background: rgba(207, 70, 70, 0.13);
  color: var(--danger);
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

.terminal-right-stack {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.terminal-right-stack :deep(.news-panel) {
  flex: 1 0 280px;
}

.task-monitor-panel {
  flex: 0 0 auto;
  padding: 14px;
}

.task-monitor-panel .panel-header {
  margin-bottom: 10px;
}

.task-dlq-hint {
  padding: 8px 10px;
  border-radius: 7px;
  background: var(--negative-soft);
  font-weight: 650;
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

  .version-statuses {
    justify-content: flex-start;
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
