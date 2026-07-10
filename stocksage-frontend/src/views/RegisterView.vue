<template>
  <main class="auth-shell register-shell">
    <section class="auth-panel" aria-label="注册">
      <router-link class="back-link" to="/login">返回登录</router-link>
      <div class="auth-heading">
        <h1>注册</h1>
        <p>创建独立的投研画像和报告空间</p>
      </div>

      <form class="auth-form" @submit.prevent="submit">
        <label>
          <span>邮箱</span>
          <input v-model.trim="email" autocomplete="email" type="email" placeholder="you@example.com" />
        </label>
        <label>
          <span>密码</span>
          <input v-model="password" autocomplete="new-password" type="password" placeholder="至少 8 位" />
        </label>
        <p v-if="error" class="auth-error">{{ error }}</p>
        <button class="auth-primary" type="submit" :disabled="submitting">
          <el-icon><Loading v-if="submitting" /><UserFilled v-else /></el-icon>
          <span>{{ submitting ? '创建中' : '创建账号' }}</span>
        </button>
      </form>
    </section>
  </main>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { Loading, UserFilled } from '@element-plus/icons-vue'
import { register } from '../api/auth.js'

const router = useRouter()
const email = ref('')
const password = ref('')
const submitting = ref(false)
const error = ref('')

async function submit() {
  error.value = ''
  submitting.value = true
  try {
    await register({ email: email.value, password: password.value })
    await router.replace({ path: '/login', query: { email: email.value } })
  } catch (err) {
    error.value = err.message || '注册失败'
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.auth-shell {
  min-height: 100%;
  display: grid;
  place-items: center;
  padding: 24px;
  background:
    linear-gradient(180deg, rgba(5, 150, 105, 0.08), transparent 44%),
    var(--app-bg);
}

.auth-panel {
  width: min(420px, 100%);
}

.back-link {
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 800;
  text-decoration: none;
}

.auth-heading {
  margin-top: 24px;
}

.auth-heading h1 {
  margin: 0;
  font-size: 28px;
  letter-spacing: 0;
}

.auth-heading p {
  color: var(--text-secondary);
}

.auth-form {
  display: grid;
  gap: 14px;
  margin-top: 28px;
}

.auth-form label {
  display: grid;
  gap: 7px;
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 700;
}

.auth-form input {
  width: 100%;
  min-height: 42px;
  padding: 0 12px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface);
  color: var(--text-primary);
}

.auth-primary {
  min-height: 42px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 6px;
  border-radius: 8px;
  border: 1px solid var(--accent);
  background: var(--accent);
  color: #fff;
  cursor: pointer;
  font-weight: 800;
}

.auth-primary:disabled {
  cursor: not-allowed;
  opacity: 0.65;
}

.auth-error {
  margin: 0;
  color: var(--danger);
  font-size: 13px;
}
</style>
