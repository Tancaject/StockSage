import test from 'node:test'
import assert from 'node:assert/strict'

import { createInitialRun, applyChunk, resolveStatus, DEFAULT_RESEARCH_ERROR, CHUNK_TYPE_ERROR } from './researchRun.js'

// ── createInitialRun ─────────────────────────────────────────────────────────

test('createInitialRun returns a run in idle state with empty fields', () => {
  const run = createInitialRun()

  assert.equal(run.status, 'idle')
  assert.equal(run.visible, false)
  assert.equal(run.title, '')
  assert.equal(run.ticker, '')
  assert.equal(run.answer, '')
  assert.deepEqual(run.timeline, [])
  assert.equal(run.modelTier, '')
  assert.equal(run.modelName, '')
  assert.equal(run.traceId, '')
  assert.equal(run.conversationId, null)
  assert.equal(run.error, '')
})

// ── applyChunk — answer ──────────────────────────────────────────────────────

test('applyChunk accumulates answer content across multiple answer chunks', () => {
  const run = createInitialRun()
  const after1 = applyChunk(run, { type: 'answer', content: 'Hello' })
  const after2 = applyChunk(after1, { type: 'answer', content: ' world' })

  assert.equal(after2.answer, 'Hello world')
})

test('applyChunk handles an answer chunk with no content without crashing', () => {
  const run = createInitialRun()
  const result = applyChunk(run, { type: 'answer' })

  assert.equal(result.answer, '')
})

// ── applyChunk — meta ────────────────────────────────────────────────────────

test('applyChunk backfills modelTier, modelName, traceId, and conversationId from a meta chunk', () => {
  const run = createInitialRun()
  const result = applyChunk(run, {
    type: 'meta',
    modelTier: 'pro',
    modelName: 'claude-opus',
    traceId: 'trace-001',
    conversationId: 42,
  })

  assert.equal(result.modelTier, 'pro')
  assert.equal(result.modelName, 'claude-opus')
  assert.equal(result.traceId, 'trace-001')
  assert.equal(result.conversationId, 42)
})

test('applyChunk only updates meta fields that are present in the chunk', () => {
  const run = { ...createInitialRun(), modelTier: 'standard', traceId: 'old-trace' }
  const result = applyChunk(run, { type: 'meta', modelTier: 'pro' })

  assert.equal(result.modelTier, 'pro')
  assert.equal(result.traceId, 'old-trace')
})

// ── applyChunk — timeline (observation / action / thought) ───────────────────

test('applyChunk pushes an observation chunk into the timeline', () => {
  const run = createInitialRun()
  const result = applyChunk(run, {
    type: 'observation',
    sectionLabel: 'RAG result',
    content: 'Found 3 relevant filings.',
  })

  assert.equal(result.timeline.length, 1)
  assert.equal(result.timeline[0].kind, 'observation')
  assert.equal(result.timeline[0].label, 'RAG result')
  assert.equal(result.timeline[0].detail, 'Found 3 relevant filings.')
})

test('applyChunk pushes an action chunk into the timeline', () => {
  const run = createInitialRun()
  const result = applyChunk(run, {
    type: 'action',
    sectionLabel: 'Tool call',
    content: 'getStockKLine(NVDA)',
  })

  assert.equal(result.timeline.length, 1)
  assert.equal(result.timeline[0].kind, 'action')
})

test('applyChunk pushes a thought chunk into the timeline', () => {
  const run = createInitialRun()
  const result = applyChunk(run, {
    type: 'thought',
    sectionLabel: 'Plan',
    content: 'Need to retrieve SEC filings.',
  })

  assert.equal(result.timeline.length, 1)
  assert.equal(result.timeline[0].kind, 'thought')
  assert.equal(result.timeline[0].label, 'Plan')
})

test('applyChunk pushes a structured route decision into the timeline', () => {
  const run = createInitialRun()
  const result = applyChunk(run, {
    type: 'route_decision',
    content: '意图理解：查询最新新闻\n选择路由：NEWS',
    metadata: { route: 'NEWS', source: 'ROUTING_LLM' },
  })

  assert.equal(result.timeline.length, 1)
  assert.equal(result.timeline[0].kind, 'route_decision')
  assert.equal(result.timeline[0].detail, '意图理解：查询最新新闻\n选择路由：NEWS')
})

test('applyChunk accumulates multiple timeline entries in order', () => {
  let run = createInitialRun()
  run = applyChunk(run, { type: 'thought', sectionLabel: 'Step 1', content: 'Planning' })
  run = applyChunk(run, { type: 'action', sectionLabel: 'Step 2', content: 'Calling tool' })
  run = applyChunk(run, { type: 'observation', sectionLabel: 'Step 3', content: 'Got data' })

  assert.equal(run.timeline.length, 3)
  assert.equal(run.timeline[0].kind, 'thought')
  assert.equal(run.timeline[1].kind, 'action')
  assert.equal(run.timeline[2].kind, 'observation')
})

// ── applyChunk — error ───────────────────────────────────────────────────────

test('applyChunk sets error field from an error chunk', () => {
  const run = createInitialRun()
  const result = applyChunk(run, { type: 'error', content: '研究执行失败' })

  assert.equal(result.error, '研究执行失败')
})

test('applyChunk uses DEFAULT_RESEARCH_ERROR when error chunk has no content', () => {
  const run = createInitialRun()
  const result = applyChunk(run, { type: CHUNK_TYPE_ERROR })

  assert.equal(result.error, DEFAULT_RESEARCH_ERROR)
})

test('applyChunk uses content as modelName fallback for model chunk without modelName', () => {
  const run = createInitialRun()
  const result = applyChunk(run, { type: 'model', content: 'claude-opus' })

  assert.equal(result.modelName, 'claude-opus')
})

// ── applyChunk — immutability ────────────────────────────────────────────────

test('applyChunk does not mutate the input run object', () => {
  const run = createInitialRun()
  const originalAnswer = run.answer
  const originalTimeline = run.timeline

  applyChunk(run, { type: 'answer', content: 'Some answer text' })
  applyChunk(run, { type: 'observation', sectionLabel: 'x', content: 'y' })
  applyChunk(run, { type: 'error', content: 'fail' })

  assert.equal(run.answer, originalAnswer)
  assert.equal(run.timeline, originalTimeline)
  assert.equal(run.timeline.length, 0)
  assert.equal(run.error, '')
})

test('applyChunk does not mutate the timeline array of the input run', () => {
  const run = createInitialRun()
  const after1 = applyChunk(run, { type: 'thought', sectionLabel: 's1', content: 'c1' })

  // Mutating after1.timeline should not affect run
  applyChunk(after1, { type: 'action', sectionLabel: 's2', content: 'c2' })

  assert.equal(run.timeline.length, 0)
  assert.equal(after1.timeline.length, 1)
})

// ── resolveStatus ────────────────────────────────────────────────────────────

test('resolveStatus transitions running to completed on done event', () => {
  const run = { ...createInitialRun(), status: 'running' }
  const result = resolveStatus(run, 'done')

  assert.equal(result.status, 'completed')
})

test('resolveStatus transitions running to failed on error event', () => {
  const run = { ...createInitialRun(), status: 'running' }
  const result = resolveStatus(run, 'error')

  assert.equal(result.status, 'failed')
})

test('resolveStatus transitions running to stopped on stop event', () => {
  const run = { ...createInitialRun(), status: 'running' }
  const result = resolveStatus(run, 'stop')

  assert.equal(result.status, 'stopped')
})

test('resolveStatus does not overwrite failed status with completed on done event', () => {
  const run = { ...createInitialRun(), status: 'failed' }
  const result = resolveStatus(run, 'done')

  assert.equal(result.status, 'failed')
})

test('resolveStatus does not overwrite stopped status with completed on done event', () => {
  const run = { ...createInitialRun(), status: 'stopped' }
  const result = resolveStatus(run, 'done')

  assert.equal(result.status, 'stopped')
})

test('resolveStatus returns a new object and does not mutate the input', () => {
  const run = { ...createInitialRun(), status: 'running' }
  const result = resolveStatus(run, 'done')

  assert.equal(run.status, 'running')
  assert.equal(result.status, 'completed')
  assert.notEqual(result, run)
})
