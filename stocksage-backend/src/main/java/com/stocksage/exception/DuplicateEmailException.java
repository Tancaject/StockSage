package com.stocksage.exception;

/**
 * 注册邮箱已存在时抛出的业务异常。
 *
 * <p>{@link GlobalExceptionHandler} 将它映射为 HTTP 409，区别于请求格式错误。</p>
 */
public class DuplicateEmailException extends RuntimeException {

    /**
     * @param message 可安全返回给注册调用方的冲突说明
     */
    public DuplicateEmailException(String message) {
        super(message);
    }
}
