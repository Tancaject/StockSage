import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'

function componentFunction(path, name) {
  const source = fs.readFileSync(new URL(path, import.meta.url), 'utf8')
  return source.match(new RegExp(`(?:async )?function ${name}\\([^]*?\\n\\}`))[0]
}

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function chatHarness() {
  const requests = []
  const state = {
    currentConversationId: { value: null }, messages: { value: [] },
    isStreaming: { value: false }, isLoadingMessages: { value: false }, errors: [],
  }
  const create = new Function('state', 'getConversationMessages', `
    const { currentConversationId, messages, isStreaming, isLoadingMessages } = state
    let messagesRequestId = 0
    const handleStop = () => {}, cancelTaskWatch = () => {}, resetScrollLock = () => {}
    const closeMobileSidebar = () => {}, scrollToBottom = () => {}
    const toChatMessage = value => value, resumeActiveTask = async () => null
    const ElMessage = { error: message => state.errors.push(message) }
    ${componentFunction('../views/ChatView.vue', 'switchConversation')}
    ${componentFunction('../views/ChatView.vue', 'newConversation')}
    return { switchConversation, newConversation }
  `)
  return { state, requests, ...create(state, id => {
    const request = { id, ...deferred() }
    requests.push(request)
    return request.promise
  }) }
}

test('conversation responses, errors and finally belong to their request generation', async () => {
  const h = chatHarness()
  const old = h.switchConversation(1)
  const current = h.switchConversation(2)
  h.requests[0].reject(new Error('old failure'))
  await old
  assert.equal(h.state.isLoadingMessages.value, true)
  assert.deepEqual(h.state.errors, [])
  h.requests[1].resolve([{ content: 'conversation 2' }])
  await current
  assert.deepEqual(h.state.messages.value, [{ content: 'conversation 2' }])

  const first = h.switchConversation(1)
  const middle = h.switchConversation(2)
  const last = h.switchConversation(1)
  h.requests[4].resolve([{ content: 'latest conversation 1' }])
  await last
  h.requests[2].resolve([{ content: 'obsolete conversation 1' }])
  h.requests[3].resolve([{ content: 'obsolete conversation 2' }])
  await Promise.all([first, middle])
  assert.deepEqual(h.state.messages.value, [{ content: 'latest conversation 1' }])

  const pending = h.switchConversation(2)
  h.newConversation()
  h.requests[5].resolve([{ content: 'old history' }])
  await pending
  assert.equal(h.state.currentConversationId.value, null)
  assert.deepEqual(h.state.messages.value, [])
  assert.equal(h.state.isLoadingMessages.value, false)
})

test('news responses and failures cannot replace a newer ticker or clear its loading state', async () => {
  const requests = []
  const state = { props: { ticker: 'AAPL' }, data: { value: null }, error: { value: '' }, loading: { value: false } }
  const load = new Function('state', 'fetchStockNews', `
    const { props, data, error, loading } = state
    let newsRequestId = 0
    ${componentFunction('../components/workbench/NewsPanel.vue', 'load')}
    return load
  `)(state, () => { const request = deferred(); requests.push(request); return request.promise })
  const old = load()
  state.props.ticker = 'NVDA'
  const current = load()
  requests[0].reject(new Error('old failure'))
  await old
  assert.equal(state.loading.value, true)
  assert.equal(state.error.value, '')
  requests[1].resolve({ items: ['NVDA'] })
  await current
  assert.deepEqual(state.data.value.items, ['NVDA'])
  const stale = load()
  state.props.ticker = 'MSFT'
  const latest = load()
  requests[3].resolve({ items: ['MSFT'] })
  await latest
  requests[2].resolve({ items: ['NVDA stale'] })
  await stale
  assert.deepEqual(state.data.value.items, ['MSFT'])
  const blanked = load()
  state.props.ticker = ''
  await load()
  requests[4].resolve({ items: ['MSFT stale'] })
  await blanked
  assert.equal(state.data.value, null)
  assert.equal(state.loading.value, false)
})
