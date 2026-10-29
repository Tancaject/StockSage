<template>
  <section class="tools-layout">
    <div class="panel tool-catalog">
      <div class="panel-header"><h2>工具与 Skill</h2><button :disabled="loading || executing" @click="loadCatalog">刷新</button></div>
      <p class="description">列表来自当前服务。工具执行指定操作，Skill 按固定步骤组合工具。</p>
      <p v-if="loading" role="status">正在读取可用工具…</p>
      <p v-if="catalogError" role="alert" class="error">{{ catalogError }}</p>
      <template v-else>
        <h3>Skill</h3>
        <button v-for="skill in catalog.skills" :key="skill.id" class="catalog-item" :class="{ selected: selected?.id === skill.id && kind === 'skill' }" :disabled="executing" @click="select('skill', skill)">{{ skill.displayName || skill.id }}<small>{{ skill.id }}</small></button>
        <p v-if="!loading && !catalog.skills.length" class="description">没有可执行的 Skill。</p>
        <h3>工具</h3>
        <input v-model="filter" placeholder="搜索工具名称或说明" aria-label="搜索工具" />
        <button v-for="tool in filteredTools" :key="tool.name" class="catalog-item" :class="{ selected: selected?.name === tool.name && kind === 'tool' }" :disabled="executing" @click="select('tool', tool)">{{ tool.name }}<small>{{ tool.description }}</small></button>
        <p v-if="!loading && !filteredTools.length" class="description">没有匹配的工具。</p>
      </template>
    </div>
    <div class="panel tool-editor">
      <template v-if="selected">
        <div class="panel-header"><h2>{{ kind === 'skill' ? selected.displayName || selected.id : selected.name }}</h2><button class="primary-button" :disabled="executing" @click="execute">{{ executing ? '执行中…' : '执行' }}</button></div>
        <p class="description">{{ kind === 'skill' ? '按下列方法和步骤收集证据；缺口会保留在结果中。' : selected.description }}</p>
        <p class="description">以下示例仅说明输入格式，示例数值不代表实际市场数据。确认参数后点击执行。</p>
        <template v-if="kind === 'tool'">
          <label v-for="field in fields" :key="field.name" class="tool-field">
            <span>{{ field.name }}{{ field.required ? ' *' : '' }}<small>{{ field.schema.description }}</small></span>
            <select v-if="field.type === 'boolean'" v-model="values[field.name]" :disabled="executing"><option value="">未填写</option><option :value="true">true</option><option :value="false">false</option></select>
            <textarea v-else-if="field.json" v-model="values[field.name]" spellcheck="false" rows="10" :disabled="executing" :aria-label="field.name + ' JSON'" />
            <input v-else v-model="values[field.name]" :type="field.type === 'number' || field.type === 'integer' ? 'number' : 'text'" :step="field.type === 'integer' ? 1 : 'any'" :disabled="executing" />
          </label>
          <p v-if="!fields.length" class="description">此工具没有输入参数。</p>
        </template>
        <template v-else>
          <label class="tool-field"><span>Skill 输入 JSON</span><textarea v-model="skillInput" spellcheck="false" rows="14" :disabled="executing" /></label>
          <details><summary>方法与执行步骤</summary><p class="method">{{ selected.method || '未提供方法说明' }}</p><ol><li v-for="(step, index) in selected.steps || []" :key="index">{{ step.capability }}{{ step.required ? '（必要步骤）' : '' }}</li></ol></details>
        </template>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <section v-if="result !== null" class="execution-result" aria-live="polite">
          <div class="panel-header"><h3>执行结果</h3><button @click="download">下载 JSON</button></div>
          <p>状态：{{ result.status || (result.error ? 'ERROR' : '已返回') }}</p>
          <ul v-if="result.gaps?.length"><li v-for="(gap, index) in result.gaps" :key="index">{{ typeof gap === 'string' ? gap : JSON.stringify(gap) }}</li></ul>
          <div v-if="result.evidence?.length" class="evidence"><details v-for="(item, index) in result.evidence" :key="index"><summary>{{ item.tool }} · {{ item.status }}</summary><pre>{{ JSON.stringify(item.content, null, 2) }}</pre></details></div>
          <details :open="!result.evidence?.length"><summary>完整 JSON 输出</summary><pre>{{ JSON.stringify(result, null, 2) }}</pre></details>
        </section>
      </template>
      <p v-else class="description">选择一个工具或 Skill，填写参数后执行。</p>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { BASE_URL, getJson, requestJson } from '../../api/http.js'

const catalog = ref({ tools: [], skills: [] }), selected = ref(null), kind = ref('tool')
const loading = ref(false), executing = ref(false), catalogError = ref(''), error = ref(''), filter = ref('')
const values = ref({}), skillInput = ref('{}'), result = ref(null)
const filteredTools = computed(() => catalog.value.tools.filter(tool => (tool.name + ' ' + tool.description).toLowerCase().includes(filter.value.toLowerCase())))
const fields = computed(() => Object.entries(selected.value?.inputSchema?.properties || {}).map(([name, schema]) => ({ name, schema, type: schema.type, required: (selected.value?.inputSchema?.required || []).includes(name), jsonString: name.endsWith('Json'), json: name.endsWith('Json') || schema.type === 'object' || schema.type === 'array' })))
async function loadCatalog() {
  loading.value = true; catalogError.value = ''; selected.value = null; result.value = null
  try { const data = await getJson(BASE_URL + '/research-tools'); catalog.value = { tools: data.tools || [], skills: (data.skills || []).filter(skill => skill.enabled !== false) } }
  catch (cause) { catalogError.value = cause.message || '读取工具目录失败，请检查服务后重试。' }
  finally { loading.value = false }
}
function select(type, item) {
  kind.value = type; selected.value = item; result.value = null; error.value = ''; values.value = {}
  if (type === 'skill') { skillInput.value = JSON.stringify(item.inputs || {}, null, 2); return }
  for (const field of fields.value) {
    const value = item.example?.[field.name]
    values.value[field.name] = field.json ? (typeof value === 'string' ? value : JSON.stringify(value ?? (field.type === 'array' ? [] : {}), null, 2)) : (value ?? (field.type === 'boolean' ? false : ''))
  }
}
function buildInput() {
  if (kind.value === 'skill') {
    let input
    try { input = JSON.parse(skillInput.value) } catch { throw new Error('Skill 输入不是有效 JSON，请检查引号、逗号和括号。') }
    if (!input || Array.isArray(input) || typeof input !== 'object') throw new Error('Skill 输入必须为 JSON 对象。')
    return input
  }
  const input = {}
  for (const field of fields.value) {
    const raw = values.value[field.name]
    if ((raw === '' || raw === undefined) && (field.type === 'number' || field.type === 'integer')) { if (field.required) throw new Error('请填写 ' + field.name + '。'); continue }
    if (field.type === 'boolean' && raw === '') { if (field.required) throw new Error('请选择 ' + field.name + ' 的 true 或 false。'); continue }
    if (field.json) {
      let value
      try { value = JSON.parse(raw) } catch { throw new Error(field.name + ' 不是有效 JSON，请检查输入。') }
      input[field.name] = field.jsonString ? JSON.stringify(value) : value
    } else if (field.type === 'number' || field.type === 'integer') {
      const value = Number(raw)
      if (!Number.isFinite(value) || (field.type === 'integer' && !Number.isInteger(value))) throw new Error(field.name + ' 必须为有效' + (field.type === 'integer' ? '整数。' : '数字。'))
      input[field.name] = value
    } else input[field.name] = raw
  }
  return input
}
async function execute() {
  error.value = ''; result.value = null
  let input
  try { input = buildInput() } catch (cause) { error.value = cause.message; return }
  executing.value = true
  try {
    const path = kind.value === 'skill' ? '/skills/' + encodeURIComponent(selected.value.id) : '/' + encodeURIComponent(selected.value.name)
    result.value = await requestJson(BASE_URL + '/research-tools' + path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(input) })
  } catch (cause) { error.value = cause.message || '执行失败，请检查输入和服务后重试。' }
  finally { executing.value = false }
}
function download() {
  const url = URL.createObjectURL(new Blob([JSON.stringify(result.value, null, 2)], { type: 'application/json;charset=utf-8' }))
  const link = document.createElement('a'); link.href = url; link.download = (selected.value.name || selected.value.id) + '-result.json'; link.click(); URL.revokeObjectURL(url)
}
onMounted(loadCatalog)
</script>

<style scoped>
.tools-layout { display: grid; grid-template-columns: minmax(240px, 320px) minmax(0, 1fr); gap: 18px; align-items: start; }
.description, small { color: var(--text-secondary); font-size: 12px; line-height: 1.7; }
button { cursor: pointer; padding: 7px 12px; background: var(--surface-raised); color: var(--text-primary); border: 1px solid var(--border-soft); border-radius: 6px; }
button:disabled { opacity: 0.6; cursor: wait; }
.catalog-item { display: block; text-align: left; width: 100%; margin: 7px 0; overflow-wrap: anywhere; }
.catalog-item small { display: block; margin-top: 5px; }
.catalog-item.selected { border-color: var(--accent); background: var(--accent-soft); }
.tool-catalog { max-height: calc(100vh - 130px); overflow-y: auto; }
.tool-field { display: flex; flex-direction: column; gap: 7px; margin: 15px 0; }
.tool-field small { display: block; }
input, textarea, select { box-sizing: border-box; width: 100%; padding: 9px; border-radius: 6px; border: 1px solid var(--border-soft); color: var(--text-primary); background: var(--surface-raised); }
textarea { font-family: monospace; resize: vertical; font-size: 12px; line-height: 1.5; }
pre, .method { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; line-height: 1.7; }
pre { max-height: 500px; overflow-y: auto; }
.error { color: var(--danger, #dc4545); }
summary { cursor: pointer; }
details { margin: 12px 0; }
.execution-result { border-top: 1px solid var(--border-soft); margin-top: 20px; padding-top: 12px; }
@media (max-width: 900px) { .tools-layout { grid-template-columns: 1fr; } .tool-catalog { max-height: 360px; } }
</style>
