package dev.multiloader.api.metadata;

import java.util.Objects;

/**
 * 一条依赖声明.
 * <p>{@code versionRange} 这里刻意保持为字符串:版本范围语法的解析属于
 * loader-core 的职责,API 层不绑定任何解析器实现(Fabric 用 Maven 风格范围,
 * Forge / NeoForge 用 Maven 风格方括号范围,两者语法高度重合但并非完全一致).
 * @param modId       被依赖的 mod id
 * @param versionRange 版本范围表达式;{@code "*"} 或 null 表示不限制
 * @param kind        依赖类型
 * @param ordering    加载顺序约束
 * @param side        生效侧别
*/
public record ModDependency(
        String modId,
        String versionRange,
        DependencyKind kind,
        Ordering ordering,
        Environment side) {

    public ModDependency {
        Objects.requireNonNull(modId, "modId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(ordering, "ordering");
        Objects.requireNonNull(side, "side");

        if (modId.isBlank()) {
            throw new IllegalArgumentException("dependency modId must not be blank");
        }
    }

/** 最常见的形态:双侧必装,不限版本,不关心顺序. */
    public static ModDependency required(String modId, String versionRange) {
        return new ModDependency(modId, versionRange, DependencyKind.REQUIRED, Ordering.NONE, Environment.BOTH);
    }

/** 是否对版本范围有实际约束. */
    public boolean isVersionConstrained() {
        return versionRange != null && !versionRange.isBlank() && !"*".equals(versionRange);
    }
}
