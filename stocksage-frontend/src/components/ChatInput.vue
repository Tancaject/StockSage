<template>
  <div class="composer-wrap">
    <div class="composer" :class="{ disabled, 'has-attachments': imageAttachments.length > 0 }" @paste="handlePaste">
      <div v-if="imageAttachments.length > 0" class="attachment-strip">
        <div v-for="image in imageAttachments" :key="image.id" class="attachment-chip">
          <img :src="image.dataUrl" :alt="image.name" />
          <button
            class="attachment-remove"
            type="button"
            :disabled="disabled"
            :aria-label="`移除图片：${image.name}`"
            @click="removeImage(image.id)"
          >
            <el-icon><CloseBold /></el-icon>
          </button>
        </div>
      </div>

      <div class="composer-row">
        <el-input
          v-model="inputText"
          class="prompt-input"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 5 }"
          :maxlength="MAX_MESSAGE_CHARS"
          placeholder="询问股票、行业，或粘贴截图提问"
          :disabled="disabled"
          @keydown.enter.exact.prevent="sendFromEnter"
        />
        <div class="composer-actions">
        <input
          ref="fileInput"
          class="hidden-file-input"
          type="file"
          :accept="acceptedImageTypes"
          multiple
          @change="handleFileSelect"
        />
        <el-tooltip content="上传图片" placement="top">
          <button
            class="icon-action attach-action"
            type="button"
            :disabled="disabled || imageAttachments.length >= MAX_IMAGES"
            aria-label="上传图片"
            @click="openFilePicker"
          >
            <el-icon><Picture /></el-icon>
          </button>
        </el-tooltip>
        <el-tooltip v-if="!streaming" content="发送" placement="top">
          <button
            class="icon-action send-action"
            type="button"
            :disabled="!canSubmit"
            aria-label="发送"
            @click="send"
          >
            <el-icon><Promotion /></el-icon>
          </button>
        </el-tooltip>
        <el-tooltip v-else content="停止" placement="top">
          <button
            class="icon-action stop-action"
            type="button"
            aria-label="停止"
            @click="$emit('stop')"
          >
            <el-icon><VideoPause /></el-icon>
          </button>
        </el-tooltip>
        </div>
      </div>
    </div>
    <p class="disclaimer">内容由 AI 生成，仅供研究参考，不构成投资建议。</p>
  </div>
</template>

<script setup>
/**
 * 底部输入框组件。
 *
 * 父组件负责实际网络请求。本组件只管理本地文本输入状态，
 * 并发送“发送/停止”命令，便于复用。
 */
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { CloseBold, Picture, Promotion, VideoPause } from '@element-plus/icons-vue'
import { shouldSubmitEnter } from '../lib/keyboard.js'

const props = defineProps({
  disabled: { type: Boolean, default: false },
  sendDisabled: { type: Boolean, default: false },
  streaming: { type: Boolean, default: false },
})

const emit = defineEmits(['send', 'stop'])
const inputText = ref('')
const fileInput = ref(null)
const imageAttachments = ref([])

const MAX_IMAGES = 4
const MAX_MESSAGE_CHARS = 12000
const MAX_IMAGE_BYTES = 5 * 1024 * 1024
const SUPPORTED_IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp']
const acceptedImageTypes = SUPPORTED_IMAGE_TYPES.join(',')
const canSubmit = computed(() => {
  return !props.disabled && !props.sendDisabled && (inputText.value.trim().length > 0 || imageAttachments.value.length > 0)
})

function send() {
  if (!canSubmit.value) return
  const text = inputText.value.trim()
  emit('send', {
    text,
    images: imageAttachments.value.map(({ name, mediaType, dataUrl }) => ({ name, mediaType, dataUrl })),
  })
  inputText.value = ''
  imageAttachments.value = []
}

function sendFromEnter(event) {
  if (!shouldSubmitEnter(event)) return
  send()
}

function openFilePicker() {
  if (props.disabled || imageAttachments.value.length >= MAX_IMAGES) return
  fileInput.value?.click()
}

async function handleFileSelect(event) {
  const files = Array.from(event.target.files || [])
  await addFiles(files)
  event.target.value = ''
}

async function handlePaste(event) {
  if (props.disabled) return
  const items = Array.from(event.clipboardData?.items || [])
  const imageFiles = items
    .filter(item => item.kind === 'file' && item.type.startsWith('image/'))
    .map(item => item.getAsFile())
    .filter(Boolean)

  if (imageFiles.length === 0) return
  event.preventDefault()
  await addFiles(imageFiles, { pasted: true })
}

async function addFiles(files, { pasted = false } = {}) {
  for (const file of files) {
    if (imageAttachments.value.length >= MAX_IMAGES) {
      ElMessage.warning(`一次最多附加 ${MAX_IMAGES} 张图片`)
      break
    }

    const mediaType = normalizeMediaType(file.type)
    if (!SUPPORTED_IMAGE_TYPES.includes(mediaType)) {
      ElMessage.warning('只支持 PNG、JPG 和 WebP 图片')
      continue
    }
    if (file.size > MAX_IMAGE_BYTES) {
      ElMessage.warning('单张图片不能超过 5 MB')
      continue
    }

    try {
      const dataUrl = await readAsDataUrl(file)
      imageAttachments.value.push({
        id: `${Date.now()}-${Math.random().toString(16).slice(2)}`,
        name: file.name || (pasted ? `screenshot-${new Date().toISOString().replace(/[:.]/g, '-')}.png` : 'uploaded-image'),
        mediaType,
        dataUrl,
      })
    } catch {
      ElMessage.error('图片读取失败，请换一张再试')
    }
  }
}

function removeImage(id) {
  imageAttachments.value = imageAttachments.value.filter(image => image.id !== id)
}

function normalizeMediaType(mediaType) {
  const normalized = String(mediaType || '').toLowerCase()
  return normalized === 'image/jpg' ? 'image/jpeg' : normalized
}

function readAsDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result)
    reader.onerror = () => reject(reader.error)
    reader.readAsDataURL(file)
  })
}
</script>

<style scoped>
.composer-wrap {
  width: 100%;
  padding: 14px 22px 20px;
  border-top: 1px solid var(--border-soft);
  background: var(--app-bg);
}

.composer {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: min(var(--content-width), 100%);
  margin: 0 auto;
  padding: 10px 10px 10px 18px;
  border: 1px solid var(--border-soft);
  border-radius: 18px;
  background: var(--surface);
  box-shadow: var(--shadow-command);
  transition: border-color 0.15s ease, box-shadow 0.15s ease, background 0.15s ease;
}

.composer:focus-within {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft), var(--shadow-command);
}

.composer.disabled {
  background: var(--surface-raised);
}

.composer-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: 10px;
}

.prompt-input {
  flex: 1;
}

.composer :deep(.el-textarea__inner) {
  min-height: 28px !important;
  padding: 7px 0;
  border: 0;
  background: transparent;
  box-shadow: none;
  color: var(--text-primary);
  line-height: 1.55;
  resize: none;
}

.composer :deep(.el-textarea__inner::placeholder) {
  color: var(--text-muted);
}

.composer-actions {
  display: flex;
  align-items: flex-end;
  justify-content: flex-end;
  gap: 8px;
  min-height: 40px;
}

.hidden-file-input {
  display: none;
}

.icon-action {
  width: 36px;
  height: 36px;
  border: 0;
  border-radius: 12px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  cursor: pointer;
  transition: transform 0.16s ease, background 0.16s ease, opacity 0.16s ease;
}

.icon-action .el-icon {
  font-size: 18px;
}

.attach-action {
  border: 1px solid var(--border-soft);
  background: var(--surface);
  color: var(--text-secondary);
}

.attach-action:hover:not(:disabled) {
  color: var(--accent-dark);
  border-color: var(--accent);
  background: var(--accent-soft);
}

.send-action {
  background: var(--accent);
}

.send-action:hover:not(:disabled) {
  background: var(--accent-dark);
}

.stop-action {
  background: var(--danger);
}

.icon-action:disabled {
  background: var(--panel-active);
  cursor: not-allowed;
  opacity: 0.9;
}

.attachment-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding-right: 44px;
}

.attachment-chip {
  position: relative;
  width: 72px;
  height: 54px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  overflow: hidden;
  background: var(--surface-raised);
  box-shadow: var(--shadow-soft);
}

.attachment-chip img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
}

.attachment-remove {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 20px;
  height: 20px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.55);
  color: #ffffff;
  cursor: pointer;
}

.attachment-remove:hover:not(:disabled) {
  background: var(--danger);
}

.attachment-remove:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.attachment-remove .el-icon {
  font-size: 10px;
}

.disclaimer {
  margin: 6px 0 0;
  text-align: center;
  font-size: 11px;
  color: var(--text-muted);
  line-height: 1.4;
  opacity: 0.7;
}

@media (max-width: 720px) {
  .composer-wrap {
    padding: 10px 12px 14px;
  }

  .composer {
    border-radius: 20px;
    padding-left: 14px;
  }

  .composer-row {
    gap: 8px;
  }

  .composer-actions {
    gap: 6px;
  }

  .attachment-strip {
    padding-right: 0;
  }
}
</style>
