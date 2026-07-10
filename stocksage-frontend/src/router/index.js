import { createRouter, createWebHistory } from 'vue-router'
import ChatView from '../views/ChatView.vue'
import WorkbenchView from '../views/WorkbenchView.vue'
import EvalDesk from '../views/EvalDesk.vue'
import LoginView from '../views/LoginView.vue'
import RegisterView from '../views/RegisterView.vue'
import { getCurrentUser } from '../api/auth.js'

// 当前产品是单工作区对话终端。显式保留路由，便于后续添加链路追踪、记忆管理或后台管理页面。
const routes = [
  { path: '/login', name: 'Login', component: LoginView, meta: { public: true } },
  { path: '/register', name: 'Register', component: RegisterView, meta: { public: true } },
  { path: '/', name: 'Chat', component: ChatView },
  { path: '/workbench', name: 'Workbench', component: WorkbenchView },
  { path: '/eval', name: 'Eval', component: EvalDesk },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(async (to) => {
  if (to.meta.public) return true
  try {
    await getCurrentUser()
    return true
  } catch {
    return {
      path: '/login',
      query: { redirect: to.fullPath },
    }
  }
})

export default router
