package dev.multiloader.api.transform;

import java.util.Optional;

/**
 * 单次类转换的上下文.
 * <p>注意:本接口刻意**不提供** {@code remapper()} 之类的映射相关方法.
 * 26.3 起游戏未混淆,核心层没有任何重映射需求;映射能力由旧版兼容层
 * 通过自己的 {@link ClassProcessor} 内部持有,不进入公共 API.
*/
public interface ClassContext {

/** 类的内部名({@code net/minecraft/client/Minecraft}),不是点分名. */
    String className();

/** 进入管道时的原始字节码. */
    byte[] input();

/** 游戏层类加载器.处理器需要引用游戏类型时应通过它反射,而非编译期依赖. */
    ClassLoader gameLoader();

/**
     * 只读的类层级查询.
     * <p>处理器判断"能不能注入某个类"时应走这里,而不是
     * {@code Class.forName}后者会触发完整转换管道,导致递归.
*/
    IClassHierarchy hierarchy();

/**
     * 处理器之间传递状态的槽位.
     * <p>键是调用方自定义的类型,核心层不解释内容,不清理内容
     * (生命周期与单次转换绑定).
*/
    <T> Optional<T> get(Class<T> key);

    <T> void put(Class<T> key, T value);
}
