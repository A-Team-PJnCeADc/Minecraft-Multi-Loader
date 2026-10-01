package dev.multiloader.common;

/**
 * 映射命名空间标识.
 * <p>26.3 起 Minecraft 不再混淆,游戏本体固定处于 {@link #OFFICIAL},
 * 因此本枚举当前只有两个值.{@link #OBFUSCATED} 仅作为
 * {@code VersionDetector} 探测失败时的兜底值保留.
 * <p>刻意只保留这两个值:真正的映射命名空间(SRG / INTERMEDIARY / MOJMAP 等)
 * 属于后续旧版兼容层,届时由 {@code compat-legacy-mapping} 自行定义,
 * 不得预先塞进核心层.
 * <p>与 {@link GameNamespace} 的分工:本枚举是"命名空间"这个通用概念,
 * 将来会被兼容层的映射图扩展;{@code GameNamespace} 是"探测出来的游戏当前
 * 处于哪个命名空间"这一运行时事实.两者现在值域相同,但演化方向不同,
 * 因此刻意分为两个类型,由 {@code GameNamespace.toNameSpace()} 单点桥接.
*/
public enum NameSpace {

/** 未混淆,官方名.MC 26.1+ 恒为该值. */
    OFFICIAL,

/** 混淆名.仅用于兼容层与探测兜底,核心层不产生该值. */
    OBFUSCATED
}
