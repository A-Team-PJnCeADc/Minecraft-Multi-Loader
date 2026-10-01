package dev.multiloader.api.lifecycle;

/**
 * 入口点的**调用契约**:描述"给定一个类(和一个方法名),加载器该怎么把它跑起来".
 * <p>为什么是一个带参数的枚举而不是三个 Record:三种形态的差别只有
 * (a) 是否调用一个方法,(b) 方法叫什么.而第三种形态额外需要的"要注入哪些服务"
 * 并不由适配器决定那是加载器侧的能力(加载器才知道自己能提供什么),
 * 所以它不该出现在 EntrypointRef 里.用一个扁平 record + 枚举足够,
 * 不必往一个冻结的 API 里塞第二套类型层次.
 * <p>值一旦发布就不要再改:新增枚举值会破坏所有 {@code switch} 的穷尽性检查,
 * 而修改 EntrypointRef 的形状要同时动三个适配器.
*/
public enum EntrypointKind {

/**
     * 无参构造实例化,然后调用一个无参方法.
     * <p>Fabric 风格.方法名随入口点命名空间而变(这是为什么方法名必须在 ref 里):
     * {@code main} ->{@code onInitialize},
     * {@code client} ->{@code onInitializeClient},
     * {@code server} ->{@code onInitializeServer},
     * {@code preLaunch} ->{@code onPreLaunch}.
*/
    NO_ARG_METHOD(true),

/**
     * 只需实例化,构造器本身即入口点(不注入任何参数).
     * <p>NeoForge / Forge 的不带注入参数的 {@code @Mod} 类.
*/
    NO_ARG_CONSTRUCTOR(false),

/**
     * 构造器需要注入服务(NeoForge / Forge 的 {@code IEventBus},{@code ModContainer},{@code Dist} ...).
     * <p><b>本轮未实现.</b>注入需要加载器侧的"服务解析"能力(Class ->实例),
     * 那属于 adapt-neoforge 的工作范围.这里先把枚举值占住,
     * 是为了让 API 不必二次变更调度器遇到它会明确抛
     * {@code UnsupportedOperationException} 并点名缺什么,而不是静默跳过.
*/
    CONSTRUCTOR_WITH_SERVICES(false);

    private final boolean requiresMethodName;

    EntrypointKind(boolean requiresMethodName) {
        this.requiresMethodName = requiresMethodName;
    }

/** 本形态是否必须携带方法名.用于在 {@link EntrypointRef} 构造时做一致性校验. */
    public boolean requiresMethodName() {
        return requiresMethodName;
    }
}
