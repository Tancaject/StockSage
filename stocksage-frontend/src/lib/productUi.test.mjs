import test from 'node:test'
import assert from 'node:assert/strict'

import {
  buildPrimaryNavItems,
  getRunPanelControls,
  getReportSurfaceLabels,
} from './productUi.js'

test('buildPrimaryNavItems exposes the same top-level routes for chat and workbench', () => {
  const nav = buildPrimaryNavItems('/workbench')

  assert.deepEqual(nav.map(item => [item.to, item.label, item.active]), [
    ['/', '对话', false],
    ['/workbench', '工作台', true],
  ])
  assert.equal(nav[0].description, 'AI 研究对话')
  assert.equal(nav[1].description, 'AI 投研工作台')
})

test('getReportSurfaceLabels separates AI reports, conversation exports, and memo drafts', () => {
  const labels = getReportSurfaceLabels()

  assert.equal(labels.aiReport, 'AI 研究报告')
  assert.equal(labels.conversationExport, '对话导出')
  assert.equal(labels.memoDraft, '投资备忘录')
  assert.equal(labels.downloadMemoAction, '下载备忘录')
})

test('getRunPanelControls allows finished runs to be dismissed without stopping active work', () => {
  assert.deepEqual(getRunPanelControls('running'), {
    canStop: true,
    canDismiss: false,
    canCollapse: true,
  })
  assert.deepEqual(getRunPanelControls('completed'), {
    canStop: false,
    canDismiss: true,
    canCollapse: true,
  })
  assert.equal(getRunPanelControls('failed').canDismiss, true)
  assert.equal(getRunPanelControls('stopped').canDismiss, true)
})
