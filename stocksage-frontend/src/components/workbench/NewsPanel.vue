<template>
  <aside class="panel news-panel">
    <div class="panel-header">
      <div>
        <h2>{{ ticker }} 近期动态</h2>
      </div>
      <button class="quiet-button" type="button" :disabled="loading" @click="load()">
        <el-icon><Refresh /></el-icon>
        <span>{{ loading ? '读取中' : '刷新' }}</span>
      </button>
    </div>

    <PanelSkeleton v-if="loading" :lines="6" min-height="160px" />
    <PanelError
      v-else-if="error"
      message="新闻服务暂时不可用"
      :detail="error"
      min-height="160px"
      @retry="load"
    />
    <PanelEmpty
      v-else-if="isEmpty"
      title="暂无近期新闻"
      :hint="`${ticker} 最近 7 天没有可展示的新闻。`"
      min-height="160px"
    />

    <ul v-else class="news-list">
      <li v-for="(item, idx) in items" :key="item.url || idx" class="news-item">
        <a class="news-title" :href="item.url || undefined" target="_blank" rel="noopener noreferrer">
          {{ item.title }}
        </a>
        <p v-if="item.snippet" class="news-snippet">{{ item.snippet }}</p>
        <div class="news-meta">
          <span v-if="item.source" class="news-source">{{ item.source }}</span>
          <span v-if="item.date" class="news-date">· {{ item.date }}</span>
        </div>
      </li>
    </ul>
  </aside>
</template>

<script setup>
/**
 * NewsPanel — 工作台市场新闻面板
 *
 * 复用既有的 data-service 新闻管线（Tavily 检索，已带缓存），组件自取自渲染。
 * props:
 *   ticker {String} 当前标的，组件自取 /api/workbench/stocks/{ticker}/news
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { fetchStockNews } from '../../api/workbench.js'
import PanelSkeleton from '../common/PanelSkeleton.vue'
import PanelEmpty from '../common/PanelEmpty.vue'
import PanelError from '../common/PanelError.vue'

const props = defineProps({
  ticker: { type: String, required: true },
})

const loading = ref(false)
const error = ref('')
const data = ref(null)
let newsRequestId = 0

const items = computed(() => data.value?.items || [])
const isEmpty = computed(() => !data.value || data.value.empty)

async function load() {
  const requestId = ++newsRequestId
  const symbol = String(props.ticker || '').trim()
  const isCurrent = () => requestId === newsRequestId && symbol === String(props.ticker || '').trim()
  data.value = null
  error.value = ''
  loading.value = Boolean(symbol)
  if (!symbol) return
  try {
    const result = await fetchStockNews(symbol, 7)
    if (isCurrent()) {
      data.value = result
      error.value = result.error || ''
    }
  } catch (err) {
    if (!isCurrent()) return
    error.value = err.message || '读取新闻失败'
    data.value = null
  } finally {
    if (isCurrent()) loading.value = false
  }
}

onMounted(load)
watch(() => props.ticker, load)
onBeforeUnmount(() => { newsRequestId += 1 })
</script>

<style scoped>
.news-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.news-list {
  list-style: none;
  margin: 0;
  padding: 2px 0 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow-y: auto;
}

.news-item {
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border-soft);
}

.news-item:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.news-title {
  display: block;
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.5;
  text-decoration: none;
}

.news-title:hover {
  color: var(--accent);
  text-decoration: underline;
}

.news-snippet {
  margin: 6px 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.news-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  color: var(--text-muted);
  font-size: 11.5px;
}

</style>
