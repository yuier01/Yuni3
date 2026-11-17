package com.yuier.yuni.plugin.api.session;

/**
 * 会话异常基类。
 */
public class SessionException extends RuntimeException {

    public SessionException(String message) {
        super(message);
    }

    public SessionException(String message, Throwable cause) {
        super(message, cause);
    }
}
