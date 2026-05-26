package com.xiaofan.launcher.logs;

/**
 * 调试错误异常 - 由 /debug/error API 触发
 * 仅在 --debug 模式下可用
 * 用于测试错误报告生成功能
 */
public class DebugErrorException extends RuntimeException {

    public DebugErrorException() {
        super("调试错误 - 由 /debug/error API 触发");
    }
}
