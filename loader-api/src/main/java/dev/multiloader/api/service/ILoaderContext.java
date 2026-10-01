package dev.multiloader.api.service;

import dev.multiloader.api.locating.IModFile;
import dev.multiloader.api.metadata.GameSide;

import java.nio.file.Path;
import java.util.List;

/**
 * 加载器层的环境视图,在 {@code MultiLoaderExtension#onLoaderInit} 中提供.
 * <p>刻意不暴露命名空间枚举:核心层与扩展的契约里不需要"游戏是否混淆"这个信息,
 * 暴露它只会让兼容层相关的概念往核心 API 上渗.需要判定命名空间的组件
 * ({@code VersionDetector} / {@code MixinService})在核心层内部直接读
 * {@code runtime-common} 的枚举.
*/
public interface ILoaderContext {

/** 游戏根目录(含 mods/ ,config/ 的目录). */
    Path gameRoot();

/** mods 目录. */
    Path modsDir();

/** 目标 Minecraft 版本,如 {@code "26.3"}. */
    String minecraftVersion();

/** 是否开发环境(由 agent 参数决定). */
    boolean isDevelopment();

/**
     * 当前进程运行在哪一侧.
     * <p>不含 {@link dev.multiloader.api.metadata.Environment}(那是 mod 声明的支持面)
     * 见 {@link GameSide} 的说明.
*/
    GameSide side();

/**
     * 已发现的 Mod 文件.在 {@code onLoaderInit} 阶段可能为空,
     * 在 {@code onGameBoot} 阶段一定已填充.
*/
    List<IModFile> modFiles();

/** 加载器层的类加载器.扩展自身的类由它加载. */
    ClassLoader loaderLayer();
}
