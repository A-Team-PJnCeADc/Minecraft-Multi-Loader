package dev.multiloader.api.lifecycle;

/**
 * 统一生命周期阶段.三种加载器格式的入口点都归一到这套阶段上.
 * <p>顺序即声明顺序,执行时按此推进.加载器特有的阶段映射:
 * <ul>
 *   <li>Fabric:{@code preLaunch} ->{@link #PREINIT},{@code main} ->{@link #INIT},
 *       {@code client} / {@code server} ->{@link #SIDED_SETUP}</li>
 *   <li>NeoForge / Forge:{@code @Mod} 构造器 ->{@link #INIT},
 *       注册表事件 ->{@link #REGISTER},侧别事件 ->{@link #SIDED_SETUP}</li>
 * </ul>
*/
public enum LifecyclePhase {

/** 加载器自身构造完成,还没有任何 mod 代码执行. */
    CONSTRUCT,

/** 游戏类尚未加载,mod 只能做最保守的准备(Fabric preLaunch). */
    PREINIT,

/** 主初始化.绝大多数 mod 的入口在这里. */
    INIT,

/** 注册表填充(方块/物品/实体类型等). */
    REGISTER,

/** 侧别相关初始化(客户端渲染,服务端世界相关). */
    SIDED_SETUP,

/** 加载完成,可以安全读取其他 mod 的最终状态. */
    LOAD_COMPLETE;

/** 是否属于"侧别相关"阶段,用于按 {@code Environment} 过滤入口点. */
    public boolean isSided() {
        return this == SIDED_SETUP;
    }
}
