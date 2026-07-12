import assert from 'node:assert/strict'
import fs from 'node:fs'
import test from 'node:test'
import { fileURLToPath } from 'node:url'


test('ChatView reopens the latest conversation on mount so active tasks can reconnect', () => {
  const viewPath = fileURLToPath(new URL('../views/ChatView.vue', import.meta.url))
  const source = fs.readFileSync(viewPath, 'utf8')

  assert.match(
    source,
    /onMounted\(async \(\) => \{[\s\S]*?loadConversations\(\{ selectLatest: true \}\)[\s\S]*?\}\)/,
  )
  assert.match(
    source,
    /async function switchConversation[\s\S]*?await resumeActiveTask\(id\)/,
  )
})
