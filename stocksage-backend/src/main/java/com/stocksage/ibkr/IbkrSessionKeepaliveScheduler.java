package com.stocksage.ibkr;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 后端运行期间保持 IBKR Client Portal 经纪账户会话活跃。
 *
 * 该机制不能绕过 IBKR 登录或双重验证。一旦 IBKR 判定会话失效，
 * 用户仍需要通过 https://localhost:5000 重新登录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IbkrSessionKeepaliveScheduler {

    /** 决定 IBKR 集成和保活任务是否启用。 */
    private final IbkrProperties properties;
    /** 提供认证状态查询与 tickle 的只读服务。 */
    private final IbkrReadOnlyService ibkrReadOnlyService;

    /**
     * 定期检查并维持 IBKR Client Portal 会话。
     *
     * <p>只有在 IBKR 集成和 keepalive 开关都启用时才执行；如果会话已经认证且 brokerage session 已建立，
     * 就发送 tickle 保活。该方法不会尝试自动登录，也不会绕过 IBKR 的二次验证要求。</p>
     */
    @Scheduled(
            initialDelayString = "${stocksage.ibkr.keepalive-initial-delay-ms:15000}",
            fixedDelayString = "${stocksage.ibkr.keepalive-interval-ms:60000}"
    )
    public void keepSessionAlive() {
        if (!properties.isEnabled() || !properties.isKeepaliveEnabled()) {
            return;
        }

        String authStatus = ibkrReadOnlyService.getAuthStatus();
        if (authStatus.contains("\"authenticated\":true") && authStatus.contains("\"established\":true")) {
            ibkrReadOnlyService.tickle();
            log.debug("IBKR session keepalive tickle sent");
        } else {
            log.info("IBKR session is not fully authenticated; waiting for user login at https://localhost:5000. status={}", authStatus);
        }
    }
}
