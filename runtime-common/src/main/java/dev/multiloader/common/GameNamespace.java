package dev.multiloader.common;

/**
 * 探测结果:游戏本体当前所处的命名空间.
 * <p>这是 {@code VersionDetector} 的输出类型,属于"运行时事实",
 * 与 {@link NameSpace} 这个"通用命名空间概念"刻意分开维护.
 * <p>本枚举在核心层只作判定用(例:{@code MixinService} 在 {@link #OFFICIAL}
 * 下固定 {@code remap=false}),不携带任何映射数据.
*/
public enum GameNamespace {

/** 未混淆.MC 26.1+ 恒为该值. */
    OFFICIAL(NameSpace.OFFICIAL),

/** 混淆.探测到旧版游戏时才会出现,本轮不实现其处理链路. */
    OBFUSCATED(NameSpace.OBFUSCATED);

    private final NameSpace nameSpace;

    GameNamespace(NameSpace nameSpace) {
        this.nameSpace = nameSpace;
    }

/**
     * 桥接到通用命名空间枚举.
     * <p>这是两个类型之间唯一的转换点.任何需要 {@link NameSpace} 的地方都走这里,
     * 不要各写各的映射(避免出现第二份"哪个值对应哪个值"的知识).
*/
    public NameSpace toNameSpace() {
        return nameSpace;
    }

/** 是否未混淆.调用方若只是想判断这一点,优先用本方法而不是比较枚举. */
    public boolean isOfficial() {
        return this == OFFICIAL;
    }

/**
     * 由探测到的布尔事实构造.
     * @param obfuscated 探针判定游戏为混淆版本时传 true
*/
    public static GameNamespace of(boolean obfuscated) {
        return obfuscated ? OBFUSCATED : OFFICIAL;
    }
}
