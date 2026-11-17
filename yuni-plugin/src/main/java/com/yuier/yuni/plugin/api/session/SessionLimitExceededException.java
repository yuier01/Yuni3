package com.yuier.yuni.plugin.api.session;

/**
 * 会话配额超限。
 */
public final class SessionLimitExceededException extends SessionException {

    public SessionLimitExceededException(String message) {
        super(message);
    }
}
