/**
 * 研究运行状态机 —— 纯函数，无 window / Vue 依赖。
 *
 * 所有函数遵循不可变原则：入参对象从不被修改，每次返回新对象。
 * 这使得状态可以在 node --test 中直接测试，也便于 Vue composable 包装。
 */

/** error chunk 的默认错误文案（无 content 时兜底）。 */
export const DEFAULT_RESEARCH_ERROR = '研究执行失败'

/** error chunk 的 type 字面量常量，供 applyChunk 和 useResearchRun 共用。 */
export const CHUNK_TYPE_ERROR = 'error'

/**
 * 创建初始运行态对象。
 *
 * @returns {{ visible: boolean, status: string, title: string, ticker: string,
 *             answer: string, timeline: Array, modelTier: string, modelName: string,
 *             traceId: string, conversationId: null|number, error: string }}
 */
export function createInitialRun() {
  return {
    visible: false,
    status: 'idle',
    title: '',
    ticker: '',
    answer: '',
    timeline: [],
    modelTier: '',
    modelName: '',
    traceId: '',
    conversationId: null,
    error: '',
  }
}

/**
 * 将单个 SSE chunk 应用到运行态，返回新的运行态对象。
 *
 * 支持的 chunk 类型：
 * - 'meta' / 'model'  → 回填 conversationId / modelTier / modelName / traceId
 * - 'answer'          → 累加 answer
 * - 'task-final'      → 用最终报告替换受理消息并结束运行
 * - 'observation' / 'action' / 'thought'  → 追加 timeline 条目
 * - 'error'           → 置 error 字段
 * - 其他类型          → 原样返回（无副作用）
 *
 * @param {object} run    当前运行态（不可变，不会被修改）
 * @param {object} chunk  SSE chunk 对象
 * @returns {object}      新的运行态对象
 */
export function applyChunk(run, chunk = {}) {
  const type = String(chunk.type || '')

  // meta / model：回填元数据字段（只覆盖 chunk 上实际存在的字段）
  if (type === 'meta' || type === 'model') {
    const patch = {}
    if (chunk.conversationId !== undefined) patch.conversationId = chunk.conversationId
    if (chunk.modelTier !== undefined)      patch.modelTier = chunk.modelTier
    if (chunk.modelName !== undefined)      patch.modelName = chunk.modelName
    // model chunk 的模型名可能放在 content 字段
    if (type === 'model' && chunk.content !== undefined && chunk.modelName === undefined) {
      patch.modelName = chunk.content
    }
    if (chunk.traceId !== undefined)        patch.traceId = chunk.traceId
    return { ...run, ...patch }
  }

  // answer：累加内容
  if (type === 'answer') {
    return { ...run, answer: run.answer + (chunk.content || '') }
  }

  if (type === 'task-final') {
    if (run.status === 'failed' || run.status === 'stopped') return run
    return resolveStatus({ ...run, answer: String(chunk.content || '').trim() || run.answer }, 'done')
  }

  // observation / action / thought：追加 timeline 条目
  if (type === 'route_decision' || type === 'observation' || type === 'action' || type === 'thought') {
    const entry = {
      kind: type,
      label: chunk.sectionLabel || chunk.section || '',
      detail: chunk.content || '',
      metadata: chunk.metadata || null,
      durationMs: Number(chunk.durationMs || chunk.metadata?.durationMs || 0),
    }
    return { ...run, timeline: [...run.timeline, entry] }
  }

  // error：置 error 字段
  if (type === CHUNK_TYPE_ERROR) {
    return { ...run, error: chunk.content || DEFAULT_RESEARCH_ERROR }
  }

  // 其他类型（如 'chart'、心跳等）：不做处理
  return run
}

/**
 * 根据终止事件计算新 status，保留既有规则：
 * 已经是 failed / stopped 时，done 事件不能将其覆盖为 completed。
 *
 * @param {object} run              当前运行态
 * @param {'done'|'error'|'stop'} event  终止事件
 * @returns {object}                带有新 status 的运行态对象
 */
export function resolveStatus(run, event) {
  if (event === 'done') {
    if (run.status === 'failed' || run.status === 'stopped') return run
    return { ...run, status: 'completed' }
  }
  if (event === 'error') {
    return { ...run, status: 'failed' }
  }
  if (event === 'stop') {
    return { ...run, status: 'stopped' }
  }
  return run
}
