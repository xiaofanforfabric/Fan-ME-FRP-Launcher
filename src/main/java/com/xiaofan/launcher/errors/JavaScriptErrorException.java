package com.xiaofan.launcher.errors;

/**
 * JavaScript 错误异常 - 由 BrowserTab 在捕获到 JS console.error 或未处理错误时抛出
 * 表示前端发生了严重错误
 */
public class JavaScriptErrorException extends RuntimeException {

    public JavaScriptErrorException(String message) {
        super("你前端炸了: " + message);
    }
}
