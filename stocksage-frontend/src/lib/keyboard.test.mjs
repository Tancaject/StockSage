import test from 'node:test'
import assert from 'node:assert/strict'

import { shouldSubmitEnter } from './keyboard.js'

test('shouldSubmitEnter ignores IME composition confirmation', () => {
  assert.equal(shouldSubmitEnter({ isComposing: true }), false)
})

test('shouldSubmitEnter accepts a plain Enter key event', () => {
  assert.equal(shouldSubmitEnter({ isComposing: false }), true)
})
