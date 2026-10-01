package net.fabricmc.loader.impl;

import dev.multiloader.bridge.fabric.loader.MultiLoaderFabricLoader;

/**
 * {@code FabricLoader.getInstance()} 的静态方法体直接读这个类的 {@code INSTANCE}:
 * <pre>
 *   static FabricLoader getInstance() {
 *       FabricLoader ret = FabricLoaderImpl.INSTANCE;
 *       if (ret == null) throw new RuntimeException("Accessed FabricLoader too early!");
 *       return ret;
 *   }
 * </pre>
 * <p><b>字段类型必须是 {@code FabricLoaderImpl},不能是 {@code FabricLoader}</b>:
 * 上面那行字节码是 {@code getstatic FabricLoaderImpl.INSTANCE : LFabricLoaderImpl;} --
 * 描述符写死在接口的 class 文件里.声明成 {@code FabricLoader INSTANCE}
 * 会在运行时以 {@code NoSuchFieldError} 失败(实测踩过).
 * <p><b>已知冲突(未解决,见下)</b>:上游 {@code fabric-loader} 产物里
 * **已经存在**一个同名同包的 {@code FabricLoaderImpl}.因此运行时是谁生效,
 * 取决于类路径顺序 -- 我们的产物必须排在 fabric-loader 之前,本类才会被采用.
 * 若顺序翻转,就会落到上游那个类:它的 {@code INSTANCE} 从未被我们写入,
 * 于是 mod 拿到 {@code "Accessed FabricLoader too early!"}.
 * <p>这个冲突是"要提供真实 Fabric API 类型"这条约束带来的必然结果,
 * 需要在架构层面决定(见 docs/04 第 3 节).
*/
public final class FabricLoaderImpl extends MultiLoaderFabricLoader {

/** 类型必须是本类 -- 见类注释(描述符写死在上游接口的字节码里). */
    public static FabricLoaderImpl INSTANCE;

    static {
        // 用静态初始化块赋值:getInstance() 读该字段的动作本身就会触发本类初始化,
        // 因此不需要核心层显式编排.代价是初始化时刻不可控 --
        // 所以 MultiLoaderFabricLoader 的每个方法都惰性读取上下文,不缓存快照.
        INSTANCE = new FabricLoaderImpl();
    }

    private FabricLoaderImpl() {
        // 仅由静态初始化块创建.
    }
}
