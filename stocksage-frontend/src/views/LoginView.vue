<template>
  <main class="auth-shell">
    <section class="auth-visual" aria-hidden="true">
      <div class="market-strip">
        <span v-for="row in marketRows" :key="row.ticker" class="market-row">
          <strong>{{ row.ticker }}</strong>
          <small :class="row.tone">{{ row.price }}</small>
        </span>
      </div>
      <div class="auth-brand">
        <span class="brand-mark"><el-icon><TrendCharts /></el-icon></span>
        <div>
          <strong>StockSage</strong>
          <small>AI 投研工作台</small>
        </div>
      </div>
    </section>

    <section class="auth-panel" aria-label="登录">
      <div class="auth-heading">
        <h1>登录</h1>
        <p>进入你的投研工作区</p>
      </div>

      <form class="auth-form" @submit.prevent="submit">
        <label>
          <span>邮箱</span>
          <input v-model.trim="email" autocomplete="email" type="email" placeholder="demo@stocksage.local" />
        </label>
        <label>
          <span>密码</span>
          <input v-model="password" autocomplete="current-password" type="password" placeholder="至少 8 位" />
        </label>
        <p v-if="error" class="auth-error">{{ error }}</p>
        <button class="auth-primary" type="submit" :disabled="submitting">
          <el-icon><Loading v-if="submitting" /><Lock v-else /></el-icon>
          <span>{{ submitting ? '登录中' : '登录' }}</span>
        </button>
        <button class="auth-secondary" type="button" :disabled="submitting" @click="loginDemo">
          <el-icon><User /></el-icon>
          <span>以 demo 登录</span>
        </button>
      </form>

      <p class="auth-link">
        没有账号
        <router-link to="/register">注册</router-link>
      </p>
    </section>
  </main>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Lock, Loading, TrendCharts, User } from '@element-plus/icons-vue'
import { login } from '../api/auth.js'

const router = useRouter()
const route = useRoute()
const email = ref('demo@stocksage.local')
const password = ref('demo1234')
const submitting = ref(false)
const error = ref('')

const redirectPath = computed(() => {
  const value = Array.isArray(route.query.redirect) ? route.query.redirect[0] : route.query.redirect
  return value && value.startsWith('/') ? value : '/'
})

const marketRows = [
  { ticker: 'NVDA', price: '+1.8%', tone: 'up' },
  { ticker: 'AAPL', price: '+0.4%', tone: 'up' },
  { ticker: 'TSLA', price: '-0.9%', tone: 'down' },
  { ticker: 'MSFT', price: '+0.7%', tone: 'up' },
]

async function submit() {
  error.value = ''
  submitting.value = true
  try {
    await login({ email: email.value, password: password.value })
    await router.replace(redirectPath.value)
  } catch (err) {
    error.value = err.message || '登录失败'
  } finally {
    submitting.value = false
  }
}

function loginDemo() {
  email.value = 'demo@stocksage.local'
  password.value = 'demo1234'
  submit()
}
</script>

<style scoped>
.auth-shell {
  min-height: 100%;
  display: grid;
  grid-template-columns: minmax(280px, 0.95fr) minmax(340px, 1fr);
  background: var(--app-bg);
}

.auth-visual {
  position: relative;
  overflow: hidden;
  min-height: 100vh;
  padding: 32px;
  display: flex;
  align-items: flex-end;
  background:
    linear-gradient(135deg, rgba(5, 150, 105, 0.9), rgba(15, 23, 42, 0.94)),
    url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='900' height='900' viewBox='0 0 900 900'%3E%3Cg fill='none' stroke='rgba(255,255,255,0.18)' stroke-width='2'%3E%3Cpath d='M0 610 C110 530 190 640 310 560 S520 470 630 520 790 610 900 510'/%3E%3Cpath d='M0 700 C130 650 220 720 340 650 S560 570 680 630 820 700 900 650'/%3E%3C/g%3E%3Cg fill='rgba(255,255,255,0.08)'%3E%3Crect x='120' y='240' width='44' height='220'/%3E%3Crect x='220' y='180' width='44' height='300'/%3E%3Crect x='320' y='300' width='44' height='170'/%3E%3Crect x='420' y='130' width='44' height='360'/%3E%3Crect x='520' y='260' width='44' height='220'/%3E%3C/g%3E%3C/svg%3E");
  background-size: cover;
  color: #fff;
}

.market-strip {
  position: absolute;
  top: 28px;
  left: 28px;
  right: 28px;
  display: grid;
  gap: 8px;
}

.market-row {
  display: flex;
  justify-content: space-between;
  padding: 10px 12px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(10px);
}

.market-row small.up {
  color: #86efac;
}

.market-row small.down {
  color: #fca5a5;
}

.auth-brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.brand-mark {
  width: 42px;
  height: 42px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.14);
}

.auth-brand strong {
  display: block;
  font-size: 24px;
}

.auth-brand small {
  color: rgba(255, 255, 255, 0.72);
}

.auth-panel {
  width: min(420px, calc(100vw - 40px));
  align-self: center;
  justify-self: center;
  padding: 32px 0;
}

.auth-heading h1 {
  margin: 0;
  font-size: 28px;
  letter-spacing: 0;
}

.auth-heading p,
.auth-link {
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

.auth-primary,
.auth-secondary {
  min-height: 42px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-radius: 8px;
  border: 1px solid var(--border-soft);
  cursor: pointer;
  font-weight: 800;
}

.auth-primary {
  margin-top: 6px;
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}

.auth-secondary {
  background: var(--surface);
  color: var(--text-primary);
}

.auth-primary:disabled,
.auth-secondary:disabled {
  cursor: not-allowed;
  opacity: 0.65;
}

.auth-error {
  margin: 0;
  color: var(--danger);
  font-size: 13px;
}

.auth-link a {
  color: var(--accent-dark);
  font-weight: 800;
  text-decoration: none;
}

@media (max-width: 820px) {
  .auth-shell {
    grid-template-columns: 1fr;
  }

  .auth-visual {
    min-height: 180px;
    align-items: flex-end;
  }

  .market-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
