import assert from 'node:assert/strict'
import test from 'node:test'

import { isTaskTerminal, latestEntryId } from './sse-cursor.mjs'

test('latestEntryId keeps the newest Redis stream entry id', () => {
  assert.equal(latestEntryId('', '1720000000000-0'), '1720000000000-0')
  assert.equal(latestEntryId('1720000000000-9', ''), '1720000000000-9')
  assert.equal(latestEntryId('1720000000000-9', '1720000000001-0'), '1720000000001-0')
  assert.equal(latestEntryId('1720000000000-9', '1720000000000-10'), '1720000000000-10')
  assert.equal(latestEntryId('1720000000001-0', '1720000000000-99'), '1720000000001-0')
  assert.equal(latestEntryId('1720000000000-9', '1720000000000-9'), '1720000000000-9')
})

test('latestEntryId ignores malformed cursor values when a valid cursor exists', () => {
  assert.equal(latestEntryId('1720000000000-1', 'not-an-entry-id'), '1720000000000-1')
  assert.equal(latestEntryId('not-an-entry-id', '1720000000000-2'), '1720000000000-2')
})

test('isTaskTerminal recognizes task completion and error chunks', () => {
  assert.equal(isTaskTerminal({ type: 'task-final' }), true)
  assert.equal(isTaskTerminal({ type: 'error' }), true)
  assert.equal(isTaskTerminal({ type: 'answer' }), false)
  assert.equal(isTaskTerminal(null), false)
})
