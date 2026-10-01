package dev.multiloader.api.service;

import dev.multiloader.api.transform.ClassProcessor;

/**
 * 游戏层的环境视图,在 {@code MultiLoaderExtension#onGameBoot} 中提供.
*/
public interface IGameContext {

/** 游戏层类加载器(TransformingClassLoader). */
    ClassLoader gameLoader();

/**
     * 追加一个字节码处理器.
     * <p>这是桥接层/兼容层接入转换管道的正式入口.
     * 注册时机必须在管道首次使用之前即 {@code onGameBoot} 之内.
*/
    void registerClassProcessor(ClassProcessor processor);

/**
     * 注册一个游戏层可见的服务实例,供后续处理器或入口点取用.
*/
    <T> void registerService(Class<T> type, T instance);

    <T> T getService(Class<T> type);
}
