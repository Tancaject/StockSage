<template>
  <div class="chat-shell" :class="{ 'sidebar-open': isMobileSidebarOpen }">
    <button
      v-if="isMobileSidebarOpen"
      class="mobile-scrim"
      type="button"
      aria-label="关闭会话列表"
      @click="closeMobileSidebar"
    ></button>

    <aside class="sidebar" :class="{ open: isMobileSidebarOpen }">
      <div class="sidebar-header">
        <button class="brand-lockup" type="button" @click="newConversation">
          <span class="brand-mark">
            <el-icon><TrendCharts /></el-icon>
          </span>
          <span class="brand-copy">
            <strong>StockSage</strong>
            <small>AI 投研工作台</small>
          </span>
        </button>
        <el-tooltip content="新对话" placement="bottom">
          <button class="round-icon-button" type="button" aria-label="新对话" @click="newConversation">
            <el-icon><Plus /></el-icon>
          </button>
        </el-tooltip>
      </div>

      <button class="new-chat-button" type="button" @click="newConversation">
        <el-icon><EditPen /></el-icon>
        <span>新对话</span>
      </button>

      <div class="sidebar-status-grid" aria-label="研究状态">
        <div>
          <span>路线</span>
          <strong>{{ routeCount }}</strong>
        </div>
        <div>
          <span>追踪</span>
          <strong>开启</strong>
        </div>
      </div>

      <div class="sidebar-section">
        <div class="section-title">
          <el-icon><ChatDotRound /></el-icon>
          <span>最近对话</span>
        </div>

        <div class="conversation-list" v-loading="isLoadingConversations">
          <div
            v-if="!isLoadingConversations && conversations.length === 0"
            class="empty-state sidebar-empty"
          >
            暂无历史对话
          </div>
          <div
            v-for="conv in conversations"
            :key="conv.id"
            class="conversation-row"
            :class="{ active: conv.id === currentConversationId }"
          >
            <button
              class="conversation-item"
              type="button"
              :disabled="isDeletingConversation(conv.id)"
              @click="switchConversation(conv.id)"
            >
              <span class="conversation-dot"></span>
              <span class="conversation-title">{{ conv.title || '新对话' }}</span>
            </button>
            <el-tooltip content="删除对话" placement="right">
              <button
                class="conversation-delete"
                type="button"
                :disabled="isDeletingConversation(conv.id)"
                :aria-label="`删除对话：${conv.title || '新对话'}`"
                @click.stop="confirmDeleteConversation(conv)"
              >
                <el-icon><Loading v-if="isDeletingConversation(conv.id)" /><DeleteIcon v-else /></el-icon>
              </button>
            </el-tooltip>
          </div>
        </div>
      </div>

      <div class="sidebar-footer">
        <div class="profile-chip">
          <span class="profile-avatar">{{ userInitial }}</span>
          <span class="profile-copy">
            <strong>{{ activeUserName }}</strong>
            <small>{{ activeUserLabel }}</small>
          </span>
        </div>
        <button class="footer-action" type="button" @click="handleLogout">
          <el-icon><SwitchButton /></el-icon>
          <span>登出</span>
        </button>
      </div>
    </aside>

    <main class="chat-main">
      <header class="chat-topbar">
        <div class="topbar-left">
          <button
            class="mobile-sidebar-button"
            type="button"
            aria-label="打开会话列表"
            @click="openMobileSidebar"
          >
            <el-icon><MenuIcon /></el-icon>
          </button>
          <div class="topbar-copy">
            <div class="model-name">{{ currentConversationTitle }}</div>
            <div class="model-subtitle">StockSage AI 投资助理</div>
          </div>
        </div>
        <div class="topbar-actions">
          <nav class="primary-nav" aria-label="主导航">
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
          <button
            class="utility-button"
            type="button"
            :disabled="messages.length === 0"
            @click="exportCurrentReport"
          >
            <el-icon><Download /></el-icon>
            <span>{{ reportLabels.conversationExport }}</span>
          </button>
        </div>
      </header>

      <section class="messages" ref="messagesRef" @scroll.passive="handleMessagesScroll">
        <div v-if="isLoadingMessages" class="loading-state">
          <span class="loading-dot"></span>
          <span>加载中...</span>
        </div>
        <template v-else>
          <div
            v-if="messages.length === 0 && !isStreaming"
            class="welcome-panel"
          >
            <div class="welcome-mark">
              <el-icon><DataAnalysis /></el-icon>
            </div>
            <div class="welcome-kicker">研究指挥台</div>
            <h1>今天想研究什么？</h1>
            <div class="research-signal-grid">
              <div
                v-for="signal in researchSignals"
                :key="signal.label"
                class="research-signal"
                :class="signal.tone"
              >
                <span>{{ signal.label }}</span>
                <strong>{{ signal.value }}</strong>
                <small>{{ signal.note }}</small>
              </div>
            </div>
            <div class="prompt-grid">
              <button
                v-for="prompt in quickPrompts"
                :key="prompt.text"
                class="prompt-card"
                :class="prompt.tone"
                type="button"
                :disabled="isLoadingMessages || isStreaming"
                @click="sendQuickPrompt(prompt.text)"
              >
                <span class="prompt-icon">
                  <el-icon><component :is="prompt.icon" /></el-icon>
                </span>
                <span class="prompt-content">
                  <span class="prompt-route">{{ prompt.route }}</span>
                  <span>{{ prompt.text }}</span>
                  <small>{{ prompt.meta }}</small>
                </span>
              </button>
            </div>
            <div class="source-strip">
              <span v-for="source in sourceBadges" :key="source">{{ source }}</span>
            </div>
          </div>

          <ChatMessage
            v-for="(msg, idx) in messages"
            :key="msg.id || idx"
            :message="msg"
            :show-retry="msg.role === 'assistant' && idx === messages.length - 1 && !isStreaming"
            :show-edit="msg.role === 'user' && idx === lastUserIndex && !isStreaming"
            @retry="handleRetry"
            @edit="handleEdit"
          />

          <div v-if="isStreaming" class="thinking-indicator">
            <span class="thinking-avatar">
              <el-icon><TrendCharts /></el-icon>
            </span>
            <span class="dot-animation">思考中</span>
          </div>
        </template>
      </section>

      <button
        v-if="showBackToBottom"
        class="back-to-bottom-button"
        type="button"
        aria-label="回到底部"
        title="回到底部"
        @click="jumpToBottom"
      >
        <el-icon><ArrowDown /></el-icon>
      </button>

      <ChatInput
        :disabled="isStreaming"
        :send-disabled="isLoadingMessages"
        :streaming="isStreaming"
        @send="handleSend"
        @stop="handleStop"
      />
    </main>
  </div>
</template>

<script setup>
/**
 * 主对话页逻辑。
 *
 * 数据流：
 *   用户输入 → handleSend() → streamChat() 发起 SSE 请求
 *     → 后端流式推送数据块 → onChunk() 逐块更新消息列表
 *     → Vue 响应式更新页面节点 → 用户看到"打字机效果"
 *
 * 状态管理：
 *   - messages: 当前会话的消息列表（响应式数组）
 *   - isStreaming: 是否正在接收流式回复（控制输入框禁用状态）
 *   - conversations: 左侧持久化会话列表
 */
import { computed, markRaw, ref, nextTick, onBeforeUnmount, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, ChatDotRound, Coin, DataAnalysis, Delete as DeleteIcon, Download, EditPen, Loading, Menu as MenuIcon, Notebook, Plus, Search, SwitchButton, TrendCharts } from '@element-plus/icons-vue'
import ChatMessage from '../components/ChatMessage.vue'
import ChatInput from '../components/ChatInput.vue'
import { deleteConversation, getConversationMessages, listConversations, streamChat } from '../api/chat.js'
import { getCurrentUser, logout } from '../api/auth.js'
import { getActiveTask, openTaskEvents } from '../api/researchTasks.js'
import { buildPrimaryNavItems, getReportSurfaceLabels } from '../lib/productUi.js'
import { isTaskTerminal, latestEntryId } from '../lib/sse-cursor.mjs'
import { buildReportMarkdown, normalizeTicker } from '../lib/workbench.js'

const router = useRouter()
const currentUser = ref(null)
const activeUserName = computed(() => currentUser.value?.nickname || 'StockSage 用户')
const activeUserLabel = computed(() => currentUser.value?.email || '已登录')
const userInitial = computed(() => {
  const source = activeUserName.value || activeUserLabel.value || 'U'
  return source.trim().slice(0, 1).toUpperCase()
})
const reportLabels = getReportSurfaceLabels()
const primaryNavItems = buildPrimaryNavItems('/').map(withPrimaryNavIcon)
const conversations = ref([])
const currentConversationId = ref(null)  // null 表示新对话，后端会自动创建
const messages = ref([])
const isStreaming = ref(false)
const isLoadingConversations = ref(false)
const isLoadingMessages = ref(false)
const deletingConversationIds = ref(new Set())
const isMobileSidebarOpen = ref(false)
const messagesRef = ref(null)  // 消息容器节点引用，用于自动滚动
const isNearBottom = ref(true)
const shouldFollowOutput = ref(true)
let abortController = null     // 当前 SSE 请求的 AbortController
let taskEventController = null
let taskReconnectTimer = null
let activeTask = null
let activeTaskConversationId = null
let activeTaskLookup = null
let activeTaskDiscoveryAttempted = false
let lastStreamEntryId = ''
let lastTaskEntryId = ''
let taskStreamTerminal = false
const reasoningChunkTypes = new Set(['thought', 'action', 'observation'])
const BOTTOM_LOCK_DISTANCE = 120
const TASK_RECONNECT_DELAY_MS = 1_200

function withPrimaryNavIcon(item) {
  return {
    ...item,
    icon: item.id === 'chat' ? markRaw(ChatDotRound) : markRaw(DataAnalysis),
  }
}

// 快捷提示同时作为后端路由的活示例：DIRECT、MARKET、FUNDAMENTALS/RAG 和 DEEP 多智能体研究。
const quickPrompts = [
  { icon: markRaw(Coin), route: '直答', meta: '基础概念', tone: 'tone-direct', text: '什么是市盈率？' },
  { icon: markRaw(TrendCharts), route: '行情', meta: '行情走势', tone: 'tone-market', text: 'NVDA 最近 K 线走势如何？' },
  { icon: markRaw(Search), route: '财报', meta: '10-K 风险', tone: 'tone-filing', text: '苹果最新 10-K 的风险因素有哪些？' },
  { icon: markRaw(Notebook), route: '深度', meta: '长期判断', tone: 'tone-deep', text: '苹果现在值不值得长期投资？' },
]

const researchSignals = [
  { label: '行情', value: '行情', note: '价格与趋势', tone: 'positive' },
  { label: '财报', value: '财报', note: '风险与披露', tone: 'info' },
  { label: 'RAG', value: '知识库', note: '研报与术语', tone: 'warning' },
  { label: '追踪', value: '可追踪', note: '工具调用记录', tone: 'neutral' },
]

const sourceBadges = [
  '行情数据',
  'SEC 文件',
  '本地知识库',
  '链路记录',
]

const routeCount = computed(() => new Set(quickPrompts.map(prompt => prompt.route)).size)

const currentConversationTitle = computed(() => {
  const current = conversations.value.find(c => c.id === currentConversationId.value)
  return current?.title || '新对话'
})

const lastUserIndex = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    if (messages.value[i].role === 'user') return i
  }
  return -1
})

const showBackToBottom = computed(() => messages.value.length > 0 && !isNearBottom.value)

onMounted(async () => {
  await loadCurrentUser()
  await loadConversations()
})

onBeforeUnmount(() => {
  abortController?.abort()
  abortController = null
  cancelTaskWatch()
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

/** 新建对话：清空消息，conversationId 置 null 让后端创建 */
function newConversation() {
  if (isStreaming.value) handleStop()
  else cancelTaskWatch()
  currentConversationId.value = null
  messages.value = []
  resetScrollLock()
  closeMobileSidebar()
}

function openMobileSidebar() {
  isMobileSidebarOpen.value = true
}

function closeMobileSidebar() {
  isMobileSidebarOpen.value = false
}

/** 切换对话 */
async function switchConversation(id, { stopStream = true } = {}) {
  if (currentConversationId.value === id && messages.value.length > 0) {
    closeMobileSidebar()
    return
  }
  if (stopStream && isStreaming.value) handleStop()
  else if (stopStream) cancelTaskWatch()

  currentConversationId.value = id
  closeMobileSidebar()
  isLoadingMessages.value = true
  try {
    const history = await getConversationMessages(id)
    messages.value = history.map(toChatMessage)
    await resumeActiveTask(id)
    scrollToBottom({ force: true })
  } catch (err) {
    messages.value = [{ role: 'assistant', content: err.message }]
    ElMessage.error(`加载消息失败：${err.message}`)
    scrollToBottom({ force: true })
  } finally {
    isLoadingMessages.value = false
  }
}

/** 从后端加载会话列表，可在首次进入页面时自动打开最近一条 */
async function loadConversations({ selectLatest = false } = {}) {
  isLoadingConversations.value = true
  try {
    const list = await listConversations()
    conversations.value = list
    if (selectLatest && list.length > 0 && currentConversationId.value === null) {
      await switchConversation(list[0].id, { stopStream: false })
    }
  } catch (err) {
    console.error('Failed to load conversations', err)
  } finally {
    isLoadingConversations.value = false
  }
}

function toChatMessage(message) {
  return {
    id: message.id,
    role: message.role,
    content: message.content,
    images: message.images || [],
    createdAt: message.createdAt,
    traceId: message.traceId,
    modelTier: message.modelTier,
    modelName: message.modelName,
    reasoning: message.reasoning || [],
    charts: message.charts || [],
    task: message.task || null,
    isTaskFinal: message.isTaskFinal === true,
  }
}

function upsertConversation(conversationId, fallbackTitle) {
  const existing = conversations.value.find(c => c.id === conversationId)
  const title = existing?.title || fallbackTitle
  conversations.value = [
    { ...existing, id: conversationId, title },
    ...conversations.value.filter(c => c.id !== conversationId),
  ]
}

function titleFromMessage(text) {
  const title = text?.trim() || '图片分析'
  return title.length > 20 ? title.substring(0, 20) + '...' : title
}

function normalizeActiveTask(task) {
  if (!task) return null
  const taskId = task.taskId ?? task.id
  if (taskId === null || taskId === undefined) return null
  return {
    ...task,
    taskId,
    status: String(task.status || 'PENDING').toUpperCase(),
    stage: String(task.stage || 'CREATED').toUpperCase(),
  }
}

function findTaskAssistant(taskId) {
  for (let index = messages.value.length - 1; index >= 0; index--) {
    const message = messages.value[index]
    if (message.role === 'assistant' && message.task?.taskId === taskId) return message
  }
  return null
}

function attachTaskToAssistant(task, connection = 'live') {
  const normalized = normalizeActiveTask(task)
  if (!normalized) return null
  const assistantMessage = findTaskAssistant(normalized.taskId) || getOrCreateStreamingAssistant(null)
  assistantMessage.task = {
    ...(assistantMessage.task || {}),
    ...normalized,
    connection,
  }
  return assistantMessage
}

function clearTaskConnection() {
  taskEventController?.abort()
  taskEventController = null
  if (taskReconnectTimer !== null) {
    window.clearTimeout(taskReconnectTimer)
    taskReconnectTimer = null
  }
}

function cancelTaskWatch() {
  clearTaskConnection()
  activeTask = null
  activeTaskConversationId = null
  activeTaskLookup = null
  activeTaskDiscoveryAttempted = false
  lastStreamEntryId = ''
  lastTaskEntryId = ''
  taskStreamTerminal = false
}

async function resumeActiveTask(conversationId, { connect = true } = {}) {
  if (conversationId === null || conversationId === undefined) return null
  try {
    const task = normalizeActiveTask(await getActiveTask(conversationId))
    if (currentConversationId.value !== conversationId || !task) return null

    if (activeTask?.taskId !== task.taskId) {
      lastTaskEntryId = ''
      taskStreamTerminal = false
    }
    activeTask = task
    activeTaskConversationId = conversationId
    lastTaskEntryId = latestEntryId(lastTaskEntryId, lastStreamEntryId)
    attachTaskToAssistant(task)
    isStreaming.value = true
    if (connect) connectTaskEventStream(task)
    return task
  } catch (error) {
    console.warn('Failed to inspect active research task', error)
    return null
  }
}

function discoverActiveTask() {
  const conversationId = currentConversationId.value
  if (activeTask || activeTaskLookup || activeTaskDiscoveryAttempted || conversationId === null) return
  activeTaskDiscoveryAttempted = true
  activeTaskLookup = resumeActiveTask(conversationId, { connect: false })
    .finally(() => {
      activeTaskLookup = null
    })
}

function connectTaskEventStream(task, { reconnecting = false } = {}) {
  const normalized = normalizeActiveTask(task)
  const conversationId = activeTaskConversationId
  if (!normalized || conversationId === null || currentConversationId.value !== conversationId) return

  clearTaskConnection()
  const taskMessage = attachTaskToAssistant(normalized, reconnecting ? 'reconnecting' : 'live')
  // POST 对话流未必携带 Redis entry id。首次改走任务端点时用全量回放重建，
  // 避免把已经看到的逐 token section 再追加一遍。
  if (!lastTaskEntryId && taskMessage?.reasoning?.length) {
    taskMessage.reasoning = []
  }
  isStreaming.value = true

  taskEventController = openTaskEvents(
    normalized.taskId,
    lastTaskEntryId || null,
    chunk => {
      if (currentConversationId.value !== conversationId) return
      if (chunk.entryId) {
        const nextCursor = latestEntryId(lastTaskEntryId, chunk.entryId)
        if (lastTaskEntryId && nextCursor === lastTaskEntryId) return
        lastTaskEntryId = nextCursor
      }

      applyIncomingChunk(chunk, { taskReplay: true })
      if (isTaskTerminal(chunk)) {
        taskStreamTerminal = true
      }
    },
    () => {
      taskEventController = null
      if (taskStreamTerminal) {
        finishTaskWatch()
      } else {
        scheduleTaskReconnect(normalized)
      }
    },
    () => {
      taskEventController = null
      scheduleTaskReconnect(normalized)
    },
  )
}

function scheduleTaskReconnect(task) {
  const conversationId = activeTaskConversationId
  if (conversationId === null || currentConversationId.value !== conversationId || taskStreamTerminal) return
  attachTaskToAssistant(task, 'reconnecting')
  if (taskReconnectTimer !== null) window.clearTimeout(taskReconnectTimer)
  taskReconnectTimer = window.setTimeout(() => {
    taskReconnectTimer = null
    void recoverTaskEventStream(conversationId)
  }, TASK_RECONNECT_DELAY_MS)
}

async function recoverTaskEventStream(conversationId) {
  if (currentConversationId.value !== conversationId || taskStreamTerminal) return
  try {
    const task = normalizeActiveTask(await getActiveTask(conversationId))
    if (currentConversationId.value !== conversationId) return
    if (task) {
      activeTask = task
      attachTaskToAssistant(task, 'reconnecting')
      connectTaskEventStream(task, { reconnecting: true })
      return
    }

    finishTaskWatch()
    await refreshConversationFromHistory(conversationId)
  } catch {
    scheduleTaskReconnect(activeTask)
  }
}

async function refreshConversationFromHistory(conversationId) {
  try {
    const history = await getConversationMessages(conversationId)
    if (currentConversationId.value !== conversationId) return
    messages.value = history.map(toChatMessage)
    scrollToBottom()
  } catch (error) {
    console.warn('Failed to refresh completed research task history', error)
  }
}

function finishTaskWatch() {
  clearTaskConnection()
  const taskMessage = activeTask ? findTaskAssistant(activeTask.taskId) : null
  if (taskMessage?.task) taskMessage.task.connection = 'complete'
  activeTask = null
  activeTaskConversationId = null
  isStreaming.value = false
  loadConversations()
}

function appendTaskFinalChunk(chunk) {
  taskStreamTerminal = true
  const taskMessage = activeTask ? findTaskAssistant(activeTask.taskId) : null
  if (taskMessage?.task) {
    taskMessage.task.status = 'SUCCEEDED'
    taskMessage.task.stage = 'COMPLETE'
    taskMessage.task.connection = 'complete'
  }

  const content = String(chunk.content || '').trim()
  const lastMessage = messages.value[messages.value.length - 1]
  if (content && !(lastMessage?.isTaskFinal && lastMessage.content === content)) {
    messages.value.push({
      role: 'assistant',
      content,
      traceId: chunk.traceId || taskMessage?.traceId || null,
      modelTier: taskMessage?.modelTier || null,
      modelName: taskMessage?.modelName || null,
      reasoning: [],
      charts: [],
      isTaskFinal: true,
    })
  }
  scrollToBottom()
}

function applyTaskErrorChunk(chunk, taskReplay) {
  const errorText = chunk.content || '服务暂时出错，请稍后重试。'
  if (taskReplay || activeTask) {
    taskStreamTerminal = true
    const taskMessage = activeTask
      ? (findTaskAssistant(activeTask.taskId) || attachTaskToAssistant(activeTask))
      : getOrCreateStreamingAssistant(chunk.traceId)
    taskMessage.task = {
      ...(taskMessage.task || {}),
      status: 'FAILED',
      stage: 'FAILED',
      connection: 'complete',
      errorMessage: errorText,
    }
  } else {
    getOrCreateStreamingAssistant(chunk.traceId).content = errorText
  }
  ElMessage.error(errorText)
  scrollToBottom()
}

function applyIncomingChunk(chunk, { taskReplay = false, fallbackTitle = '' } = {}) {
  if (chunk.conversationId && currentConversationId.value !== chunk.conversationId) {
    currentConversationId.value = chunk.conversationId
    upsertConversation(chunk.conversationId, fallbackTitle || '研究任务')
  }
  if (!taskReplay && chunk.entryId) {
    lastStreamEntryId = latestEntryId(lastStreamEntryId, chunk.entryId)
  }

  if (chunk.type === 'model') {
    applyModelChunk(chunk)
    return
  }
  if (reasoningChunkTypes.has(chunk.type)) {
    appendReasoningChunk(chunk)
    return
  }
  if (chunk.type === 'chart') {
    appendChartChunk(chunk)
    return
  }
  if (chunk.type === 'task-final') {
    appendTaskFinalChunk(chunk)
    return
  }
  if (chunk.type === 'error') {
    applyTaskErrorChunk(chunk, taskReplay)
    return
  }
  if (chunk.type === 'answer' && !taskReplay) {
    const assistantMessage = getOrCreateStreamingAssistant(chunk.traceId)
    assistantMessage.content += chunk.content || ''
    scrollToBottom()
    discoverActiveTask()
  }
}

function sendQuickPrompt(text) {
  handleSend(text)
}

function getOrCreateStreamingAssistant(traceId) {
  // 本轮回答只用一条助手消息累积所有回答令牌。
  // 推理数据块可能早于回答数据块到达，因此收到第一个 thought/action 时也会创建消息外壳。
  let assistantMessage = messages.value[messages.value.length - 1]
  if (assistantMessage?.role !== 'assistant') {
    assistantMessage = {
      role: 'assistant',
      content: '',
      traceId,
      modelTier: null,
      modelName: null,
      reasoning: [],
      charts: [],
    }
    messages.value.push(assistantMessage)
    return assistantMessage
  }

  if (traceId && !assistantMessage.traceId) {
    assistantMessage.traceId = traceId
  }
  if (!assistantMessage.reasoning) {
    assistantMessage.reasoning = []
  }
  if (!assistantMessage.charts) {
    assistantMessage.charts = []
  }
  return assistantMessage
}

function applyModelChunk(chunk) {
  const assistantMessage = getOrCreateStreamingAssistant(chunk.traceId)
  assistantMessage.modelTier = chunk.modelTier || assistantMessage.modelTier
  assistantMessage.modelName = chunk.modelName || chunk.content || assistantMessage.modelName
  scrollToBottom()
}

function appendReasoningChunk(chunk) {
  const assistantMessage = getOrCreateStreamingAssistant(chunk.traceId)
  // 带 section 的流式块（多空辩论逐 token）聚合到同一条推理项，
  // 否则 token 级流式会瞬间堆出成百上千条碎片。按 section 查找而非只看末尾，
  // 因为 Bull/Bear 并行时两侧 token 会在网络上交错到达。
  if (chunk.section) {
    const existing = assistantMessage.reasoning.find(item => item.section === chunk.section)
    if (existing) {
      existing.content += chunk.content || ''
      scrollToBottom()
      return
    }
  }
  assistantMessage.reasoning.push({
    type: chunk.type,
    section: chunk.section || null,
    label: chunk.sectionLabel || null,
    content: chunk.content || '',
  })
  scrollToBottom()
}

function appendChartChunk(chunk) {
  const chart = parseChartPayload(chunk.content)
  if (!chart) return

  const assistantMessage = getOrCreateStreamingAssistant(chunk.traceId)
  const signature = chartSignature(chart)
  if (assistantMessage.charts.some(item => item.signature === signature)) {
    return
  }
  assistantMessage.charts.push({
    ...chart,
    bar: chart.bar || chart.period || '',
    indicators: chart.indicators || ['MA5', 'MA20', 'VOL_MA5'],
    generatedAt: chart.generatedAt || new Date().toISOString(),
    signature,
  })
  scrollToBottom()
}

function parseChartPayload(content) {
  try {
    const chart = typeof content === 'string' ? JSON.parse(content) : content
    if (chart?.chartType !== 'candlestick' || !Array.isArray(chart.points) || chart.points.length === 0) {
      return null
    }
    return chart
  } catch {
    return null
  }
}

function chartSignature(chart) {
  const lastPoint = chart.points[chart.points.length - 1] || {}
  return [
    chart.chartType,
    chart.sourceTool,
    chart.symbol,
    chart.period,
    chart.points.length,
    lastPoint.date,
    lastPoint.close,
  ].join(':')
}

function isDeletingConversation(id) {
  return deletingConversationIds.value.has(id)
}

function setConversationDeleting(id, deleting) {
  const next = new Set(deletingConversationIds.value)
  if (deleting) {
    next.add(id)
  } else {
    next.delete(id)
  }
  deletingConversationIds.value = next
}

async function confirmDeleteConversation(conversation) {
  const title = conversation.title || '新对话'
  try {
    await ElMessageBox.confirm(
      `删除后将无法恢复「${title}」及其中的消息。`,
      '删除对话？',
      {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning',
        confirmButtonClass: 'danger-confirm-button',
      }
    )
  } catch {
    return
  }

  await handleDeleteConversation(conversation.id)
}

async function handleDeleteConversation(conversationId) {
  if (isDeletingConversation(conversationId)) return

  setConversationDeleting(conversationId, true)
  try {
    if (currentConversationId.value === conversationId && isStreaming.value) {
      handleStop()
    }

    await deleteConversation(conversationId)
    conversations.value = conversations.value.filter(c => c.id !== conversationId)

    if (currentConversationId.value === conversationId) {
      currentConversationId.value = null
      messages.value = []
      resetScrollLock()
    }

    ElMessage.success('对话已删除')
  } catch (err) {
    ElMessage.error(`删除失败：${err.message}`)
    await loadConversations()
  } finally {
    setConversationDeleting(conversationId, false)
  }
}

function isScrolledNearBottom(el) {
  return el.scrollHeight - el.scrollTop - el.clientHeight <= BOTTOM_LOCK_DISTANCE
}

function resetScrollLock() {
  isNearBottom.value = true
  shouldFollowOutput.value = true
}

function handleMessagesScroll() {
  if (!messagesRef.value) return
  const nearBottom = isScrolledNearBottom(messagesRef.value)
  isNearBottom.value = nearBottom
  // 用户向上滚动后，停止自动跟随流式输出，直到用户明确跳回底部。
  shouldFollowOutput.value = nearBottom
}

/** 自动滚动到消息列表底部；用户向上滚动后，流式数据块不再抢回滚动位置。 */
function scrollToBottom({ force = false } = {}) {
  nextTick(() => {
    const el = messagesRef.value
    if (!el) return

    if (!force && !shouldFollowOutput.value) {
      isNearBottom.value = isScrolledNearBottom(el)
      return
    }

    el.scrollTop = el.scrollHeight
    resetScrollLock()
  })
}

function jumpToBottom() {
  shouldFollowOutput.value = true
  scrollToBottom({ force: true })
}

/**
 * 发送消息的核心逻辑。
 *
 * 流式接收的关键技巧：
 * - 第一个回答数据块到来时，创建一条助手消息
 * - 后续数据块到来时，不断把内容拼接到同一条消息上
 * - Vue 的响应式系统自动检测到内容变化，触发页面更新
 * - 视觉效果就是"AI 在逐字打出回答"
 */
function handleSend(payload, options = {}) {
  if (isLoadingMessages.value) return

  const outgoing = normalizeOutgoingMessage(payload, options)
  if (!outgoing.text && outgoing.images.length === 0) return

  const text = outgoing.text || '请分析这张图片。'
  const images = outgoing.images

  cancelTaskWatch()

  // 先把用户消息加入列表
  messages.value.push({ role: 'user', content: text, images })
  scrollToBottom({ force: true })

  isStreaming.value = true

  abortController = streamChat(
    {
      conversationId: currentConversationId.value,
      message: text,
      images: images.map(({ name, mediaType, dataUrl }) => ({ name, mediaType, dataUrl })),
      replaceLastTurn: options.replaceLastTurn === true,
    },
    {
      onChunk(chunk) {
        applyIncomingChunk(chunk, { fallbackTitle: titleFromMessage(text) })
      },
      onDone() {
        void handleOriginalStreamDone()
      },
      onError(err) {
        void handleOriginalStreamError(err)
      },
    }
  )
}

async function handleOriginalStreamDone() {
  abortController = null
  if (taskStreamTerminal) {
    finishTaskWatch()
    return
  }
  if (activeTask) {
    connectTaskEventStream(activeTask)
    return
  }

  const conversationId = currentConversationId.value
  const task = await resumeActiveTask(conversationId)
  if (!task) {
    isStreaming.value = false
    loadConversations()
  }
}

async function handleOriginalStreamError(error) {
  abortController = null
  if (taskStreamTerminal) {
    finishTaskWatch()
    return
  }

  const conversationId = currentConversationId.value
  const task = activeTask || await resumeActiveTask(conversationId, { connect: false })
  if (task) {
    connectTaskEventStream(task, { reconnecting: true })
    return
  }

  isStreaming.value = false
  const errorText = error?.message || '网络连接失败，请检查网络后重试。'
  getOrCreateStreamingAssistant(null).content = errorText
  ElMessage.error(errorText)
  loadConversations()
}

function normalizeOutgoingMessage(payload, options = {}) {
  const source = typeof payload === 'string' ? { text: payload, images: [] } : (payload || {})
  const optionImages = options.images || []
  return {
    text: String(source.text || '').trim(),
    images: [...(source.images || []), ...optionImages]
      .filter(image => image?.dataUrl && image?.mediaType)
      .map(image => ({
        name: image.name || 'uploaded-image',
        mediaType: image.mediaType,
        dataUrl: image.dataUrl,
      })),
  }
}

/** 重新生成最后一条 AI 回复 */
function handleRetry() {
  // 找到最后一条用户消息的文本
  let userText = ''
  let userImages = []
  for (let i = messages.value.length - 1; i >= 0; i--) {
    if (messages.value[i].role === 'user') {
      userText = messages.value[i].content
      userImages = messages.value[i].images || []
      break
    }
  }
  if (!userText && userImages.length === 0) return

  // 移除最后一条助手消息
  if (messages.value.length > 0 && messages.value[messages.value.length - 1].role === 'assistant') {
    messages.value.pop()
  }
  // 移除最后一条用户消息（handleSend 会重新添加）
  if (messages.value.length > 0 && messages.value[messages.value.length - 1].role === 'user') {
    messages.value.pop()
  }

  handleSend(userText, { replaceLastTurn: true, images: userImages })
}

/** 编辑最后一条用户消息并重新发送 */
function handleEdit(newText) {
  const userImages = lastUserIndex.value >= 0 ? (messages.value[lastUserIndex.value].images || []) : []

  // 移除最后一条助手消息（如果有）
  if (messages.value.length > 0 && messages.value[messages.value.length - 1].role === 'assistant') {
    messages.value.pop()
  }
  // 移除最后一条用户消息（handleSend 会重新添加）
  if (messages.value.length > 0 && messages.value[messages.value.length - 1].role === 'user') {
    messages.value.pop()
  }

  handleSend(newText, { replaceLastTurn: true, images: userImages })
}

/** 停止当前流式生成 */
function handleStop() {
  let stopped = false
  if (abortController) {
    abortController.abort()
    abortController = null
    stopped = true
  }
  if (taskEventController || taskReconnectTimer !== null || activeTask) {
    const taskMessage = activeTask ? findTaskAssistant(activeTask.taskId) : null
    if (taskMessage?.task) taskMessage.task.connection = 'paused'
    cancelTaskWatch()
    stopped = true
  }
  if (stopped || isStreaming.value) {
    isStreaming.value = false
    loadConversations()
  }
}

function exportCurrentReport() {
  const ticker = detectReportTicker()
  const markdown = buildReportMarkdown({
    ticker,
    title: currentConversationTitle.value,
    stance: 'review',
    messages: messages.value,
  })
  downloadText(`${ticker || 'stocksage'}-research-report.md`, markdown)
}

function detectReportTicker() {
  const joined = messages.value.map(message => message.content || '').join(' ')
  const ignored = new Set(['AI', 'API', 'CEO', 'CFO', 'EPS', 'JSON', 'LLM', 'PDF', 'RAG', 'SEC', 'SSE', 'USD'])
  const candidates = joined.match(/\b[A-Z]{1,5}\b/g) || []
  const ticker = candidates.find(candidate => !ignored.has(candidate))
  return normalizeTicker(ticker || selectedTickerFromConversationTitle())
}

function selectedTickerFromConversationTitle() {
  const match = currentConversationTitle.value.match(/\b[A-Za-z]{1,5}\b/)
  return match?.[0] || 'stocksage'
}

function downloadText(filename, content) {
  const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
}
</script>

<style scoped>
.chat-shell {
  height: 100vh;
  display: grid;
  grid-template-columns: 292px minmax(0, 1fr);
  position: relative;
  background: var(--app-bg);
  color: var(--text-primary);
}

.mobile-scrim {
  display: none;
}

.sidebar {
  min-height: 0;
  background: var(--panel-bg);
  border-right: 1px solid var(--border-soft);
  display: flex;
  flex-direction: column;
  padding: 14px 12px;
}

.sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 2px 2px 12px;
}

.brand-lockup {
  min-width: 0;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  border: 0;
  background: transparent;
  color: var(--text-primary);
  cursor: pointer;
  padding: 6px 7px 6px 6px;
  border-radius: 10px;
  transition: background 0.16s ease, transform 0.16s ease;
}

.brand-lockup:hover {
  background: var(--panel-hover);
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
}

.brand-mark .el-icon {
  font-size: 18px;
}

.brand-copy {
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.2;
}

.brand-copy strong {
  font-size: 15px;
  font-weight: 760;
}

.brand-copy small {
  margin-top: 2px;
  color: var(--text-muted);
  font-size: 12px;
}

.round-icon-button {
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  transition: background 0.16s ease, color 0.16s ease;
}

.round-icon-button:hover {
  background: var(--surface);
  color: var(--text-primary);
}

.new-chat-button {
  width: 100%;
  min-height: 42px;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 0 13px;
  border: 1px solid var(--border-soft);
  border-radius: 10px;
  background: var(--surface);
  color: var(--text-primary);
  cursor: pointer;
  font-size: 14px;
  font-weight: 620;
  box-shadow: var(--shadow-soft);
  transition: background 0.16s ease, border-color 0.16s ease, transform 0.16s ease;
}

.new-chat-button:hover {
  border-color: var(--border-strong);
  background: var(--surface);
}

.sidebar-status-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin: 12px 0 2px;
}

.sidebar-status-grid div {
  min-width: 0;
  padding: 9px 10px;
  border: 1px solid var(--border-soft);
  border-radius: 10px;
  background: var(--surface);
}

.sidebar-status-grid span {
  display: block;
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 760;
  text-transform: uppercase;
}

.sidebar-status-grid strong {
  display: block;
  margin-top: 3px;
  color: var(--text-primary);
  font-size: 15px;
  font-weight: 780;
}

.sidebar-section {
  min-height: 0;
  display: flex;
  flex: 1;
  flex-direction: column;
  padding-top: 16px;
}

.section-title {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 0 8px 8px;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 700;
}

.conversation-list {
  flex: 1;
  overflow-y: auto;
  padding: 2px 0 12px;
}

.conversation-row {
  width: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 32px;
  align-items: center;
  gap: 4px;
  border-radius: 10px;
  margin-bottom: 3px;
  transition: background 0.16s ease, color 0.16s ease;
}

.conversation-item {
  min-width: 0;
  width: 100%;
  min-height: 40px;
  display: grid;
  grid-template-columns: 8px minmax(0, 1fr);
  align-items: center;
  gap: 10px;
  padding: 0 10px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  cursor: pointer;
  font-size: 14px;
  color: var(--text-secondary);
  text-align: left;
  transition: color 0.16s ease;
}

.conversation-row:hover,
.conversation-row.active,
.conversation-row:focus-within {
  background: var(--surface);
}

.conversation-row:hover .conversation-item,
.conversation-row.active .conversation-item,
.conversation-row:focus-within .conversation-item {
  color: var(--text-primary);
}

.conversation-row.active {
  background: var(--surface);
  box-shadow: var(--shadow-soft);
}

.conversation-item:disabled {
  cursor: wait;
  opacity: 0.72;
}

.conversation-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--border-strong);
}

.conversation-row.active .conversation-dot {
  background: var(--accent);
}

.conversation-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conversation-delete {
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  opacity: 0;
  transform: translateX(2px);
  transition: opacity 0.16s ease, transform 0.16s ease, background 0.16s ease, color 0.16s ease;
}

.conversation-row:hover .conversation-delete,
.conversation-row:focus-within .conversation-delete {
  opacity: 1;
  transform: translateX(0);
}

.conversation-delete:hover:not(:disabled) {
  background: var(--negative-soft);
  color: var(--danger);
}

.conversation-delete:disabled {
  cursor: wait;
  opacity: 0.75;
}

.conversation-delete:disabled .el-icon {
  animation: spin 0.9s linear infinite;
}

.conversation-delete .el-icon {
  font-size: 15px;
}

.conversation-delete .el-icon :deep(svg) {
  display: block;
}

.sidebar-footer {
  padding-top: 10px;
  border-top: 1px solid var(--border-soft);
}

.profile-chip {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px;
  border-radius: 12px;
}

.profile-chip:hover {
  background: var(--panel-hover);
}

.profile-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--accent-soft);
  color: var(--accent-dark);
  font-size: 13px;
  font-weight: 760;
}

.profile-copy {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}

.profile-copy strong {
  font-size: 13px;
}

.profile-copy small {
  color: var(--text-muted);
  font-size: 12px;
}

.footer-action {
  width: 100%;
  min-height: 34px;
  margin-top: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface);
  color: var(--text-secondary);
  cursor: pointer;
  font-size: 12px;
  font-weight: 800;
}

.footer-action:hover {
  background: var(--surface-raised);
  color: var(--text-primary);
  border-color: var(--border-strong);
}

.empty-state,
.loading-state {
  color: var(--text-muted);
  font-size: 14px;
  text-align: center;
}

.sidebar-empty {
  padding: 34px 8px;
}

.chat-main {
  min-width: 0;
  min-height: 0;
  position: relative;
  display: flex;
  flex-direction: column;
  background: var(--surface);
}

.chat-topbar {
  min-height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 24px;
  border-bottom: 1px solid var(--border-soft);
  background: var(--surface);
}

.topbar-left,
.topbar-actions {
  min-width: 0;
  display: flex;
  align-items: center;
}

.topbar-left {
  gap: 12px;
}

.topbar-copy {
  min-width: 0;
}

.topbar-actions {
  justify-content: flex-end;
  gap: 8px;
}

.mobile-sidebar-button {
  display: none;
  width: 36px;
  height: 36px;
  border: 1px solid var(--border-soft);
  border-radius: 10px;
  align-items: center;
  justify-content: center;
  background: var(--surface);
  color: var(--text-secondary);
  cursor: pointer;
}

.model-name {
  max-width: 55vw;
  overflow: hidden;
  color: var(--text-primary);
  font-size: 15px;
  font-weight: 720;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-subtitle {
  margin-top: 2px;
  color: var(--text-muted);
  font-size: 12px;
}

.utility-button {
  min-height: 31px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 0 10px;
  border: 1px solid var(--border-soft);
  border-radius: 999px;
  background: var(--surface);
  color: var(--text-secondary);
  cursor: pointer;
  font-size: 12px;
  font-weight: 700;
  text-decoration: none;
  white-space: nowrap;
}

.utility-button:hover:not(:disabled) {
  background: var(--surface-raised);
  color: var(--text-primary);
}

.utility-button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.messages {
  min-height: 0;
  flex: 1;
  overflow-y: auto;
  padding: 36px 24px 18px;
  scroll-behavior: smooth;
}

.welcome-panel {
  width: min(var(--content-width), 100%);
  min-height: 62vh;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.welcome-mark {
  width: 46px;
  height: 46px;
  border-radius: 12px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--accent);
  color: #ffffff;
  box-shadow: 0 6px 16px rgba(15, 125, 99, 0.18);
}

.welcome-mark .el-icon {
  font-size: 23px;
}

.welcome-kicker {
  margin-top: 16px;
  color: var(--accent-dark);
  font-size: 12px;
  font-weight: 800;
  text-transform: uppercase;
}

.welcome-panel h1 {
  margin: 7px 0 18px;
  color: var(--text-primary);
  font-size: 34px;
  font-weight: 800;
  line-height: 1.2;
  letter-spacing: 0;
}

.research-signal-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin: 0 0 14px;
}

.research-signal {
  min-width: 0;
  padding: 11px 12px;
  border: 1px solid var(--border-soft);
  border-radius: 10px;
  background: var(--surface-raised);
  box-shadow: var(--shadow-soft);
}

.research-signal span,
.research-signal small {
  display: block;
  overflow: hidden;
  color: var(--text-muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.research-signal span {
  font-size: 10px;
  font-weight: 800;
  text-transform: uppercase;
}

.research-signal strong {
  display: block;
  margin-top: 4px;
  color: var(--text-primary);
  font-size: 15px;
  font-weight: 800;
}

.research-signal small {
  margin-top: 2px;
  font-size: 11px;
}

.research-signal.positive {
  border-color: rgba(8, 127, 91, 0.24);
}

.research-signal.info {
  border-color: rgba(36, 95, 157, 0.24);
}

.research-signal.warning {
  border-color: rgba(154, 101, 0, 0.24);
}

.prompt-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.prompt-card {
  min-height: 72px;
  display: grid;
  grid-template-columns: 30px minmax(0, 1fr);
  align-items: center;
  gap: 12px;
  padding: 13px 14px;
  border: 1px solid var(--border-soft);
  border-radius: 10px;
  background: var(--surface);
  color: var(--text-primary);
  cursor: pointer;
  font-size: 14px;
  line-height: 1.42;
  text-align: left;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.prompt-content {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.prompt-route {
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 760;
  letter-spacing: 0;
  text-transform: uppercase;
}

.prompt-content small {
  color: var(--text-muted);
  font-size: 11px;
}

.prompt-card:hover:not(:disabled) {
  border-color: var(--border-strong);
  background: var(--surface-raised);
}

.prompt-card:disabled {
  cursor: not-allowed;
  opacity: 0.62;
}

.prompt-icon {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--accent-soft);
  color: var(--accent-dark);
}

.prompt-card.tone-market .prompt-icon {
  background: var(--warning-soft);
  color: var(--warning);
}

.prompt-card.tone-filing .prompt-icon {
  background: var(--info-soft);
  color: var(--info);
}

.prompt-card.tone-deep .prompt-icon {
  background: rgba(122, 90, 248, 0.14);
  color: #7c6bd8;
}

.source-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 14px;
}

.source-strip span {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 0 9px;
  border: 1px solid var(--border-soft);
  border-radius: 999px;
  background: var(--surface-raised);
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 700;
}

.loading-state {
  min-height: 58vh;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
}

.loading-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--accent);
  animation: pulse 1.2s ease-in-out infinite;
}

.thinking-indicator {
  width: min(var(--content-width), 100%);
  margin: 0 auto 24px;
  display: flex;
  align-items: center;
  gap: 16px;
  color: var(--text-muted);
  font-size: 14px;
}

.thinking-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--accent);
  color: #ffffff;
}

.back-to-bottom-button {
  position: absolute;
  right: 34px;
  bottom: 104px;
  z-index: 8;
  width: 38px;
  height: 38px;
  border: 1px solid var(--border-soft);
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--text-primary);
  color: var(--surface);
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.22);
  cursor: pointer;
  transition: background 0.15s ease;
  animation: bottom-button-in 0.18s ease both;
}

.back-to-bottom-button:hover {
  background: var(--accent-dark);
  color: #ffffff;
}

.back-to-bottom-button .el-icon {
  font-size: 18px;
}

.dot-animation::after {
  content: '...';
  animation: dots 1.5s steps(3, end) infinite;
}

@keyframes dots {
  0%   { content: '.'; }
  33%  { content: '..'; }
  66%  { content: '...'; }
}

@keyframes pulse {
  0%, 100% {
    transform: scale(0.88);
    opacity: 0.45;
  }
  50% {
    transform: scale(1);
    opacity: 1;
  }
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes bottom-button-in {
  from {
    opacity: 0;
    transform: translateY(8px) scale(0.94);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

:global(.danger-confirm-button) {
  --el-button-bg-color: var(--danger);
  --el-button-border-color: var(--danger);
  --el-button-hover-bg-color: #b93629;
  --el-button-hover-border-color: #b93629;
}

@media (max-width: 900px) {
  .chat-shell {
    grid-template-columns: 1fr;
  }

  .mobile-scrim {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 19;
    border: 0;
    background: rgba(0, 0, 0, 0.35);
    backdrop-filter: blur(2px);
  }

  .sidebar {
    position: fixed;
    inset: 0 auto 0 0;
    z-index: 20;
    width: min(84vw, 320px);
    transform: translateX(-102%);
    visibility: hidden;
    pointer-events: none;
    transition: transform 0.22s ease;
  }

  .sidebar.open {
    transform: translateX(0);
    visibility: visible;
    pointer-events: auto;
  }

  .mobile-sidebar-button {
    display: inline-flex;
  }
}

@media (max-width: 720px) {
  .chat-topbar {
    min-height: 58px;
    padding: 10px 14px;
  }

  .model-name {
    max-width: 54vw;
  }

  .messages {
    padding: 24px 14px 12px;
  }

  .back-to-bottom-button {
    right: 18px;
    bottom: 92px;
  }

  .welcome-panel {
    min-height: 58vh;
  }

  .welcome-panel h1 {
    font-size: 27px;
  }

  .research-signal-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .prompt-grid {
    grid-template-columns: 1fr;
  }

  .prompt-card {
    min-height: 56px;
  }

  .utility-button span {
    display: none;
  }

  .source-strip {
    gap: 6px;
  }
}
</style>
