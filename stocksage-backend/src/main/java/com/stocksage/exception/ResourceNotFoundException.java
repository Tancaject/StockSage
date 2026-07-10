package com.stocksage.exception;

/**
 * 请求的资源不存在，或不属于当前用户。
 *
 * <p>两种情况刻意共用同一异常并映射为 404：多租户下不应向调用者区分
 * “资源不存在”与“存在但归属他人”，否则会泄露资源是否存在。消息保持通用，不回显资源 ID。</p>
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
