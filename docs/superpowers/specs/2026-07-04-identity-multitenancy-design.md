# 身份与多租户（Identity + Multi-tenancy）设计

> 属"真·可上线"子项目序列的第 1 项（脊椎）。后续：安全护栏 → 数据诚实化(已有 spec) → 可观测性/部署 → 弹性。
> 前置事实：后端一期重构已完成（见 `docs/archive/superpowers/specs/2026-07-04-backend-phase1-refactor-design.md`），Flyway 已接入（V1 基线）。

## 1. 背景与目标

当前全站硬编码单用户 `u_001`（`RequestIdentity.currentUserId()` 返回配置常量）。任何访问者都是同一个人、共享同一份会话/自选/画像。这不是"应用"，是"单人 demo"。

**目标**：引入真实注册/登录，把 `userId` 接到已认证主体，使系统成为多用户应用；每个用户只能访问自己的数据。

**非目标（划归后续子项目，本轮明确不做）**：
- 邮箱验证、密码重置（需 SMTP，→ 安全子项目）
- OAuth 社交登录
- 角色化 admin（admin token 拦截器本轮不动）
- 限流 / LLM 成本配额（→ 安全子项目）
- 知识库按用户隔离（RAG 语料、SEC 关系图设计上就保持全局共享）

## 2. 关键决策（已定）

- **认证机制**：邮箱 + 密码；Spring Security + **Spring Session Data Redis**（复用现有 Redis）。会话式，`SESSION` cookie 设 httpOnly（防 XSS 盗令牌），会话体存 Redis（因此可水平扩展，不占实例内存）。密码 BCrypt。
- **进入体验**：开放注册（不验证邮箱）；登录墙（未登录不能用应用）；`u_001` 转为公开 demo 账号（已知密码、内含现有展示数据），面试官可秒注册或直接"以 demo 登录"。
- **前端不存任何令牌**：cookie 自动携带，这正是 session 方案比 JWT 在 XSS 面更安全的地方。

## 3. 架构与安全矩阵

Spring Security 接管过滤器链。路径授权矩阵：

| 路径 | 授权 |
| --- | --- |
| `/api/auth/register`、`/api/auth/login`、`/api/auth/csrf`、静态资源 | permitAll |
| `/api/docs/**`、`/api/eval/**`、`/api/memory/**`、`/api/chat/regression/**`、`/api/chat/test` | permitAll（**由现有 `AdminApiInterceptor` token 继续把守**，保留机器对机器访问路径，如 RAG 评测脚本只带 admin token 无 session） |
| `/api/auth/me`、`/api/auth/logout` | authenticated |
| 其余 `/api/**`（chat、workbench、user、reports、trace） | authenticated |

- `/api/docs/search`（公开 RAG 健康探针）位于 permitAll 段且本就被 admin 拦截器排除，行为不变。
- **CSRF**：`CookieCsrfTokenRepository.withHttpOnlyFalse()`。前端应用加载时先 `GET /api/auth/csrf`（或任一 GET）拿到 `XSRF-TOKEN` cookie，后续写操作回传 `X-XSRF-TOKEN` 头。cookie 认证必须做对这一环。
- **CORS**：现有配置已 `allowCredentials(true)` + 指定 origin `localhost:5173`，满足 cookie 携带；生产部署时把 origin 换成部署域名。
- **会话超时**：`server.servlet.session.timeout`，默认 7 天（demo 便利），可配置。
- **admin 拦截器共存**：admin 路径在 Spring Security 层 permitAll，`AdminApiInterceptor` 作为其唯一门禁（token）保持不变——admin 是与用户会话正交的机器访问路径。

## 4. 数据模型与迁移

**Flyway `V2__add_user_credentials.sql`**（本仓库第一条真实增量迁移，顺带验证 V2 路径）：
- `users` 表加列：`email VARCHAR(255)`（唯一索引）、`password_hash VARCHAR(100)`、`email_verified BOOLEAN NOT NULL DEFAULT FALSE`（列先建，验证流程后续接）。
- 回填 `u_001`：`email='demo@stocksage.local'`、`password_hash=<BCrypt('demo1234')>`、`email_verified=TRUE`。

**本轮 `email_verified` 语义**：本轮无验证流程，注册即写 `email_verified=TRUE`，登录**不校验**该列（否则新用户被自己锁死）。该列纯为后续安全子项目预留——届时改注册为写 FALSE + 加验证闸门即可，本轮不加。

**身份与画像分离**：
- 新建 `User` JPA 实体（凭证/身份）+ `UserAccountRepository`，映射 `users` 表。与现有 `UserProfile`（持仓/自选/风险偏好，映射 `user_profiles`）**职责分开**——一个管"你是谁"，一个管"你的偏好"。
- `user_id` 保持 VARCHAR(32) 内部主键，新用户生成 `"u_" + 12位 base62 随机`。**所有现有外键（conversations/reports/research_tasks/user_profiles/agent_traces 的 user_id）零改动**。

## 5. 组件

1. **SecurityConfig**：`SecurityFilterChain`（上面的矩阵）、`PasswordEncoder`（BCrypt）、`UserDetailsService`（按 email 从 `users` 加载，构造 Spring `UserDetails`，principal 携带 user_id）、session 管理（`IF_REQUIRED`，Spring Session Redis 后端）、CSRF cookie repo。
2. **AuthController** + **AuthService**：
   - `POST /api/auth/register` {email, password}：校验邮箱格式 + 密码 ≥8 位；邮箱已存在 → 409；成功则在一个事务里建 `users` 行（`email_verified=TRUE`）+ 空 `user_profiles` 行，返回 201。**注册不自动登录**——返回 201 后前端跳 `/login`（保持 register 纯净、不在其中建会话）。
   - `POST /api/auth/login` {email, password}：自定义 JSON 端点走 `AuthenticationManager` 认证，成功建立 session（写 Redis + Set-Cookie），返回当前用户；失败 → 401 **通用错误**（不区分"邮箱不存在/密码错"，防用户枚举）。
   - `POST /api/auth/logout`：使会话失效、清 cookie。
   - `GET /api/auth/me`：返回当前登录用户（userId、email、nickname）。
   - `GET /api/auth/csrf`：触发 CSRF cookie 下发（供前端启动时预取）。
3. **RequestIdentity 接真实主体**：`currentUserId()` 改从 `SecurityContextHolder.getContext().getAuthentication()` 读 principal 的 user_id。**只改这一个类，所有 controller 调用点自动生效**——这是现有单一收口接缝的价值。保留一个受测试保护的空态处理（未认证时按 Spring Security 已拦截，理论不可达）。

## 6. 多租户审计

- **确认所有用户维度查询按已认证 user 过滤**。现有归属校验已到位：`ChatService.getConversationForUser`、`TraceService.getTraceForUser`、report 按 user 查。**补审** workbench（自选/持仓来自 `user_profiles`，经 `currentUserId()`）与 research 路径，确保无以 `u_001` 或入参 userId 越权的残留。
- `messages` 表无 user_id，但访问始终经会话归属校验（`getConversationForUser`）传递隔离，保持不变。
- **全局表明确保持全局**（不加 user_id）：`vector_documents`、`doc_index`、`contextual_gist_cache`、`company_relations`、`tool_call_logs`——RAG 语料、SEC 公司关系图、工具日志是共享资源。

## 7. 数据流

**注册**：POST /register → 校验 → BCrypt 哈希 → 建 users + user_profiles（事务）→ 201。
**登录**：POST /login → AuthenticationManager 认证 → SecurityContext + session 写 Redis → Set-Cookie `SESSION`(httpOnly) → 返回用户。
**已认证请求**：浏览器带 `SESSION` cookie → Spring Session 从 Redis 复原 SecurityContext → `RequestIdentity.currentUserId()` 读 principal → controller 照旧。
**登出**：POST /logout → 删 Redis 会话 + 清 cookie。

## 8. 错误处理

- 401 未认证 / 凭证错误（通用文案）；403 CSRF 校验失败或越权。
- 注册：409 邮箱已存在、400 邮箱/密码不合法。
- 全局异常处理器（`GlobalExceptionHandler`）补认证类异常到统一 JSON 错误体。

## 9. 前端

- **登录页 / 注册页**（Vue 路由 `/login`、`/register`）；登录页含"以 demo 登录"按钮（预填 demo 凭证）。
- HTTP 层：所有请求 `withCredentials: true`（带 cookie）；启动时 `GET /api/auth/csrf` 预取，写操作附 `X-XSRF-TOKEN` 头（从 `XSRF-TOKEN` cookie 读）。
- **401 拦截器** → 跳 `/login`；**路由守卫**：未登录访问受保护路由 → `/login`。
- 顶部/侧栏加登出按钮与当前用户显示（复用 `GET /api/auth/me`）。
- 前端不存令牌、不碰 localStorage 凭证。

## 10. 测试

- **单元**：AuthService 注册（BCrypt 哈希、重复邮箱 409、密码长度校验）；RequestIdentity 从 SecurityContext 读 user_id。
- **集成（`@SpringBootTest` + MockMvc/WebTestClient，顺带补掉"最复杂链路无集成测试"缺口）**：
  - 注册 → 登录 → 访问受保护资源 200 → 登出 → 再访问 401 全链路。
  - **跨用户隔离**：用户 A 登录后读用户 B 的会话/报告 → 403/404。
  - 未带 session 访问受保护端点 → 401。
  - CSRF：无 `X-XSRF-TOKEN` 的 POST → 403。
  - admin 路径：只带 admin token（无 session）仍可达（保护评测脚本）。

## 11. 验收标准

- 全新库经 Flyway 起来后：demo 账号可登录；新邮箱可注册并登录；登出后受保护接口 401。
- 两个不同账号的会话/自选/画像/报告互不可见。
- 前端登录墙生效，未登录只能见登录/注册页；"以 demo 登录"秒进且看到现有展示数据。
- RAG 评测脚本（仅 admin token）不受影响。
- `mvn test` 全绿（含新增集成测试）；前端构建绿。
- 全程不破坏现有 SSE 对话链路（已认证后 chunk 行为不变）。
