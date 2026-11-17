package com.yuier.yuni.plugin.api.session;

/**
 * 会话能力不可用（插件卸载、总开关关闭、上下文未绑定运行时等）。
 */
public final class SessionUnavailableException extends SessionException {

    public SessionUnavailableException(String message) {
        super(message);
    }

    public SessionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
