const PRIMARY_NAV_ITEMS = Object.freeze([
  { id: 'chat', to: '/', label: '对话', description: 'AI 研究对话' },
  { id: 'workbench', to: '/workbench', label: '工作台', description: 'AI 投研工作台' },
])

const REPORT_SURFACE_LABELS = Object.freeze({
  aiReport: 'AI 研究报告',
  aiReportLibrary: 'AI 研究报告库',
  conversationExport: '对话导出',
  memoDraft: '投资备忘录',
  memoPreview: '备忘录预览',
  downloadMemoAction: '下载备忘录',
})

export function buildPrimaryNavItems(activePath = '/') {
  return PRIMARY_NAV_ITEMS.map(item => ({
    ...item,
    active: item.to === activePath,
  }))
}

export function getReportSurfaceLabels() {
  return { ...REPORT_SURFACE_LABELS }
}

export function getRunPanelControls(status) {
  const value = String(status || '').toLowerCase()
  return {
    canStop: value === 'running',
    canDismiss: ['completed', 'failed', 'stopped'].includes(value),
    canCollapse: value !== 'idle',
  }
}
