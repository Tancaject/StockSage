package com.stocksage.tool;

import com.stocksage.identity.RequestIdentity;
import java.util.LinkedHashSet;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Component;

/** 私有只读工具的用户身份；只接受认证或服务端显式传递的会话/研究运行，不接受模型参数。 */
@Component
@RequiredArgsConstructor
public class ToolUserIdentity {
    private final RequestIdentity requestIdentity;
    private final JdbcTemplate jdbc;

    public String currentUserId() {
        var owners = new LinkedHashSet<String>();
        try { owners.add(requestIdentity.currentUserId()); }
        catch (AuthenticationCredentialsNotFoundException missing) { /* Gateway 工作线程不继承 SecurityContext。 */ }
        Long conversationId = ToolCallContext.getConversationId();
        if (conversationId != null) {
            owners.add(jdbc.queryForList("SELECT user_id FROM conversations WHERE id = ?", String.class, conversationId)
                    .stream().findFirst().orElseThrow(ToolUserIdentity::unavailable));
        }
        var run = ToolCallContext.currentRunExecution();
        if (run != null) {
            // Gateway 已显式传播 run/attempt/lease；旧工作线程不能借已转移的运行读取私有研究。
            owners.add(jdbc.queryForList("SELECT user_id FROM research_tasks WHERE id = ? AND attempts = ? AND lease_token = ?",
                    String.class, run.runId(), run.attempt(), run.leaseToken()).stream().findFirst()
                    .orElseThrow(ToolUserIdentity::unavailable));
        }
        if (owners.size() != 1 || owners.iterator().next() == null || owners.iterator().next().isBlank()) throw unavailable();
        return owners.iterator().next();
    }

    private static AuthenticationCredentialsNotFoundException unavailable() {
        return new AuthenticationCredentialsNotFoundException("工具缺少一致的可信用户身份，请重新登录或从当前会话重试。");
    }
}
