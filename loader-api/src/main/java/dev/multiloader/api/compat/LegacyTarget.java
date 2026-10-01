package dev.multiloader.api.compat;

/**
 * 兼容层的目标判定输入.
 * <p>刻意只用 JDK 基元类型,不引用 {@code NameSpace} / {@code GameNamespace}:
 * 保证 {@code loader-api} 保持零依赖,也就保证了硬不变量 INV-1
 * (loader-api 的编译类路径不出现任何映射概念).
 * @param minecraftVersion 目标游戏版本,如 {@code "26.3"} 或 {@code "1.21.11"}
 * @param obfuscated       探测判定游戏为混淆版本时为 true
*/
public record LegacyTarget(String minecraftVersion, boolean obfuscated) {

/** 是否落在"需要旧版兼容处理"的范围.当前仅作判定,不含版本比较逻辑. */
    public boolean isLegacy() {
        return obfuscated;
    }
}
