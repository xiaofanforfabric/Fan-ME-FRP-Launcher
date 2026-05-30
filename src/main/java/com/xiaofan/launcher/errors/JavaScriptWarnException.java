package com.xiaofan.launcher.errors;

/**
 * JavaScript 警告异常 - 由 BrowserTab 在捕获到 JS console.warn 时抛出
 * 表示前端存在潜在问题，但不影响核心功能
 */
public class JavaScriptWarnException extends RuntimeException {

    public JavaScriptWarnException(String message) {
        super("你前端好像有点问题: " + message);
    }
}
