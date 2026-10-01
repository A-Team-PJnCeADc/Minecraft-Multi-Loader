package dev.multiloader.api.metadata;

/**
 * 相对加载顺序约束.仅对非 REQUIRED 依赖有意义(与 Forge / NeoForge 语义一致).
*/
public enum Ordering {

/** 不关心顺序. */
    NONE,

/** 本 mod 必须在该依赖之前加载. */
    BEFORE,

/** 本 mod 必须在该依赖之后加载. */
    AFTER
}
