package com.stocksage.debate;

import com.stocksage.exception.ResearchBudgetExceededException;
import com.stocksage.exception.ResearchCapacityExceededException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;

/**
 * 判断模型调用失败是否属于可安全重试一次的供应商临时错误。
 *
 * <p>辩论各角色不调用工具、没有外部副作用，同一输入重发一次是安全的。只认白名单里的网络中断、
 * 5xx 和限流；预算、容量、执行权丢失、阶段硬超时以及解析失败都不在此列，按原有路径处理。</p>
 */
final class TransientModelErrors {

    private TransientModelErrors() {
    }

    static boolean isTransient(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ResearchBudgetExceededException
                    || cause instanceof ResearchCapacityExceededException
                    || cause instanceof ResearchDebateService.ModelStageTimeoutException) {
                return false;
            }
            // 流式响应中途被对端关闭：200 OK 外层包着 PrematureCloseException。
            if (cause.getClass().getName().equals("reactor.netty.http.client.PrematureCloseException")
                    || cause instanceof WebClientRequestException
                    || cause instanceof IOException
                    || cause.getClass().getName().equals("io.netty.handler.timeout.ReadTimeoutException")) {
                return true;
            }
            if (cause instanceof WebClientResponseException response
                    && (response.getStatusCode().is5xxServerError() || response.getStatusCode().value() == 429)) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
