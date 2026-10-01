package dev.multiloader.common;

import java.util.Locale;

/**
 * 运行侧别.
 * <p>注:与 {@code loader-api} 的 {@code Environment} 不同.
 * {@code Environment} 描述"一个 mod/入口点在哪一侧生效"(含 BOTH),
 * 而本枚举描述"当前这个游戏进程是哪一侧",是二元的.
*/
public enum Side {

    CLIENT,
    SERVER;

/** 解析启动参数里的 side 值.空值默认 CLIENT(开发期最常见). */
    public static Side parse(String value) {
        if (value == null || value.isBlank()) {
            return CLIENT;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "client", "c" -> CLIENT;
            case "server", "s" -> SERVER;
            default -> throw new IllegalArgumentException(
                    "Unknown side '" + value + "' (expected client|server)");
        };
    }

    public boolean isClient() {
        return this == CLIENT;
    }

    public boolean isServer() {
        return this == SERVER;
    }
}
