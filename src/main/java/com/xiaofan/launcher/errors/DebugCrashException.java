package com.xiaofan.launcher.errors;

/**
 * 调试崩溃异常 - 由 /debug/crash API 触发
 * 仅在 --debug 模式下可用
 * 类似于 Minecraft 的 F3+C 长按 10 秒
 * 
 * 抛出此异常会触发 CrashReporter 生成崩溃报告
 */
public class DebugCrashException extends RuntimeException {

    public DebugCrashException() {
        super("调试崩溃 - 由 /debug/crash API 触发");
    }
}
