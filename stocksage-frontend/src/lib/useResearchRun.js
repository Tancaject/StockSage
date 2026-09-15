/**
 * useResearchRun —— 研究运行 composable（副作用层）。
 *
 * 职责：持有响应式运行态、封装 SSE 流式对话、管理轮询定时器、
 * 以及暴露供模板绑定的控制器计算属性。
 *
 * 纯逻辑全部委托给 src/lib/researchRun.js（无 Vue / window 依赖，可独立测试）。
 */

import { ref, computed } from 'vue'
import { createInitialRun, applyChunk, resolveStatus, CHUNK_TYPE_ERROR } from './researchRun.js'
import { getRunPanelControls } from './productUi.js'
import { streamChat } from '../api/chat.js'

const RUN_POLL_INTERVAL_MS = 5000

export function useResearchRun() {
  // 响应式运行态
  const run = ref(createInitialRun())

  // 内部可变状态（不需要响应式）
  let abortController = null
  let pollTimer = null

  // ── 计算属性 ─────────────────────────────────────────────────────────────

  const controls = computed(() => getRunPanelControls(run.value.status))

  // ── 轮询管理 ─────────────────────────────────────────────────────────────

  function stopPolling() {
    if (pollTimer !== null) {
      window.clearInterval(pollTimer)
      pollTimer = null
    }
  }

  function startPolling(onPoll) {
    stopPolling()
    if (typeof onPoll !== 'function') return
    pollTimer = window.setInterval(() => {
      onPoll({ silent: true })
    }, RUN_POLL_INTERVAL_MS)
  }

  // ── abort 管理 ────────────────────────────────────────────────────────────

  function abortStream() {
    if (abortController) {
      abortController.abort()
      abortController = null
    }
  }

  // ── 公开 API ──────────────────────────────────────────────────────────────

  /**
   * 发起一次研究运行。
   *
   * @param {string} prompt  发往后端的研究提示词
   * @param {{ origin?: string, title?: string, ticker?: string, onPoll?: Function }} options
   *   - origin   对话来源标识，默认 'workbench'
   *   - title    运行标题（显示在面板头部）
   *   - ticker   标的代码
   *   - onPoll   每隔 RUN_POLL_INTERVAL_MS 调用一次的刷新回调
   */
  function start(prompt, { origin = 'workbench', title = '', ticker = '', onPoll } = {}) {
    // 如果已有流在跑，静默拒绝（调用方应自行检查 status 并提示）
    if (run.value.status === 'running') return

    // 停掉上一次的轮询，防止重叠
    stopPolling()
    abortStream()

    // 重置为新运行态
    run.value = {
      ...createInitialRun(),
      visible: true,
      status: 'running',
      title,
      ticker,
    }

    // 启动轮询
    startPolling(onPoll)

    // 发起 SSE 流
    abortController = streamChat(
      {
        conversationId: null,
        message: prompt,
        images: [],
        origin,
        title,
      },
      {
        onChunk(chunk) {
          run.value = applyChunk(run.value, chunk)

          // error chunk 还需要同步到终止状态
          if (chunk.type === CHUNK_TYPE_ERROR) {
            _finish('error')
          } else if (chunk.type === 'task-final') {
            _finish('done')
          }
        },
        onDone() {
          _finish('done')
          // 完成后再刷新一次
          if (typeof onPoll === 'function') onPoll()
        },
        onError(err) {
          run.value = { ...run.value, error: err?.message || '流式响应失败' }
          _finish('error')
        },
      }
    )
  }

  /**
   * 停止当前运行（用户主动点击停止）。
   */
  function stop() {
    abortStream()
    run.value = resolveStatus(run.value, 'stop')
    stopPolling()
  }

  /**
   * 关闭运行面板（仅在 completed / failed / stopped 时有效）。
   */
  function dismiss() {
    if (!controls.value.canDismiss) return
    run.value = { ...run.value, visible: false }
  }

  /**
   * 卸载清理：停轮询 + abort。
   * 在 onUnmounted 中调用。
   */
  function cleanup() {
    stopPolling()
    abortStream()
  }

  // ── 内部 ──────────────────────────────────────────────────────────────────

  function _finish(event) {
    run.value = resolveStatus(run.value, event)
    abortController = null
    stopPolling()
  }

  return {
    run,
    controls,
    start,
    stop,
    dismiss,
    cleanup,
  }
}
