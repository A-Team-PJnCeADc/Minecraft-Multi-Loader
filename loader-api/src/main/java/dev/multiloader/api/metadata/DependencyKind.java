package dev.multiloader.api.metadata;

/**
 * 依赖类型.取值对齐 NeoForge / Forge {@code mods.toml} 的 {@code type} 字段,
 * 因为 Fabric 的 dep 列表可以无损映射到这套语义.
*/
public enum DependencyKind {

/** 必须存在,否则拒绝加载. */
    REQUIRED,

/** 存在则加载,不存在也继续. */
    OPTIONAL,

/** 存在则拒绝加载. */
    INCOMPATIBLE,

/** 存在则告警但继续加载. */
    DISCOURAGED
}
