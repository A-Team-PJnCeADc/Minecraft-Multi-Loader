package dev.multiloader.common;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * 极简日志门面.
 * <p>刻意不使用 SLF4J / Log4j:core 层要能在游戏类之前,在任何 mod 之前可用,
 * 依赖越少越好.底层走 JDK 自带的 {@link System.Logger},由启动参数决定实现.
 * <p>格式化沿用 {@code {}} 占位符(与常见 Java 日志一致),不暴露
 * {@link java.text.MessageFormat} 的 {@code {0}} 风格.
 * <p>不在此类里做全局可变状态以外的任何事.生产环境不打印 debug/trace,
 * 除非显式设置系统属性 {@code -Dmultiloader.verbose=true}.
*/
public final class Log {

    private static final String NAME = "MultiLoader";
    private static final Logger LOGGER = System.getLogger(NAME);

    private static final boolean VERBOSE =
            Boolean.parseBoolean(System.getProperty("multiloader.verbose", "false"));

    private Log() {
    }

    public static void trace(String format, Object... args) {
        if (VERBOSE) {
            log(Level.TRACE, format, args);
        }
    }

    public static void debug(String format, Object... args) {
        if (VERBOSE) {
            log(Level.DEBUG, format, args);
        }
    }

    public static void info(String format, Object... args) {
        log(Level.INFO, format, args);
    }

    public static void warn(String format, Object... args) {
        log(Level.WARNING, format, args);
    }

    public static void error(String format, Object... args) {
        log(Level.ERROR, format, args);
    }

    public static void error(String message, Throwable cause) {
        LOGGER.log(Level.ERROR, message, cause);
    }

/** 是否开启 verbose.供调用方跳过昂贵的诊断字符串拼接. */
    public static boolean isVerbose() {
        return VERBOSE;
    }

    private static void log(Level level, String format, Object... args) {
        if (!LOGGER.isLoggable(level)) {
            return;
        }
        LOGGER.log(level, format(format, args));
    }

/**
     * {@code {}} 占位符替换.
     * <p>包级可见以便单测.不做转义处理日志参数不参与任何解析语义.
*/
    static String format(String format, Object... args) {
        if (format == null) {
            return "null";
        }
        if (args == null || args.length == 0) {
            return format;
        }

        StringBuilder out = new StringBuilder(format.length() + 32);
        int argIndex = 0;
        int i = 0;

        while (i < format.length()) {
            char c = format.charAt(i);
            if (c == '{' && i + 1 < format.length() && format.charAt(i + 1) == '}') {
                out.append(argIndex < args.length ? String.valueOf(args[argIndex++]) : "{}");
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
