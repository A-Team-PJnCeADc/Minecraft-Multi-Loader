package dev.multiloader.api.service;

/**
 * 加载器扩展点.
 * <p>这是"核心层不需要知道某个功能存在"的通用机制.旧版兼容层,API 桥接层
 * 都通过 {@code META-INF/services} 注册本接口的实现,核心层在
 * {@code ServiceRegistry} 初始化时聚合并调用.
 * <p>核心层用 {@code loadAll} 语义(拿到几个就调用几个),
 * 拿不到任何实现时只是一个空列表,不报错.
*/
public interface MultiLoaderExtension {

/** 扩展名,用于日志与冲突诊断,必须唯一. */
    String name();

/**
     * 加载器层初始化完成,Mod 尚未发现时调用.
     * <p>典型用途:注册 {@code ClassProcessor},声明额外 classpath 根.
*/
    default void onLoaderInit(ILoaderContext ctx) {
    }

/**
     * 游戏层类加载器已就绪,Mod 已全部发现,生命周期开始前调用.
*/
    default void onGameBoot(IGameContext ctx) {
    }
}
