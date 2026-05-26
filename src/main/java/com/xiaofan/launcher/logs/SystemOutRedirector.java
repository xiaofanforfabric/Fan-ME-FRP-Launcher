package com.xiaofan.launcher.logs;

import java.io.OutputStream;
import java.io.PrintStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * System.out/err 重定向器
 * 
 * 将 System.out 和 System.err 替换为 DualOutputStream，
 * 同时写入原始 System.out/err（控制台）和 SLF4J Logger（日志文件）。
 * 
 * Logback 只配置了 FileAppender（无 ConsoleAppender），
 * 所以控制台输出完全由 DualOutputStream 写入原始 System.out/err 负责，
 * 不会形成循环。
 * 
 * 数据流：
 *   System.out.println("xxx")
 *     → SystemOutRedirector（替换后的 System.out）
 *     → DualOutputStream
 *     → 原始 System.out → 控制台显示 ✅
 *     → Logger → FileAppender → last.logs ✅
 * 
 * 用法：
 *   SystemOutRedirector.install(); // 在程序入口处调用一次
 */
public class SystemOutRedirector {

    private static boolean installed = false;

    /**
     * 安装 System.out/err 重定向器
     */
    public static synchronized void install() {
        if (installed) return;
        installed = true;

        // 保留原始 System.out/err（用于控制台输出）
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        // 创建 DualOutputStream：同时写入控制台和 Logger
        PrintStream dualOut = new PrintStream(new DualOutputStream(originalOut, false));
        PrintStream dualErr = new PrintStream(new DualOutputStream(originalErr, true));

        // 替换 System.out/err
        System.setOut(dualOut);
        System.setErr(dualErr);
    }

    /**
     * 双写 OutputStream：同时写入原始 PrintStream（控制台）和 Logger（日志文件）
     */
    private static class DualOutputStream extends OutputStream {
        private static final Logger log = LoggerFactory.getLogger("SystemOut");

        private final PrintStream original;
        private final boolean isError;
        private final StringBuilder buffer = new StringBuilder();

        public DualOutputStream(PrintStream original, boolean isError) {
            this.original = original;
            this.isError = isError;
        }

        @Override
        public void write(int b) {
            original.write(b);
            original.flush();
        }

        @Override
        public void write(byte[] buf, int off, int len) {
            // 写入控制台
            original.write(buf, off, len);
            original.flush();

            // 按行记录到 Logger
            synchronized (buffer) {
                String text = new String(buf, off, len);
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c == '\n') {
                        flushBuffer();
                    } else if (c != '\r') {
                        buffer.append(c);
                    }
                }
            }
        }

        private void flushBuffer() {
            if (buffer.length() > 0) {
                String line = buffer.toString();
                if (isError) {
                    log.warn(line);
                } else {
                    log.info(line);
                }
                buffer.setLength(0);
            }
        }

        @Override
        public void flush() {
            original.flush();
            synchronized (buffer) {
                flushBuffer();
            }
        }
    }
}
