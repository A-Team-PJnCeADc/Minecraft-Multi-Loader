package dev.multiloader.api.locating;

/**
 * 把候选文件读成 {@link IModFile}.三个格式适配器各实现一个,
 * 通过 {@code META-INF/services} 注册,核心层用 ServiceLoader 发现.
 * <p>适配器之间互不知道对方存在:不存在 {@code FabricReader instanceof ...}
 * 这种分支,也不存在"适配器优先表"这类硬编码.
*/
public interface IModFileReader {

/** 越小越先被尝试.同一文件可能被多个读取器认领(例如同时含三种元数据). */
    int priority();

/**
     * 本读取器是否认得该候选.
     * <p>实现应基于锚点文件探测(例如 jar 里是否存在 {@code fabric.mod.json}),
     * 不得基于文件名或扩展名.
     * @return 认得则 true;无法确认则为 false(不抛异常,IO 错误在 read 里报)
*/
    boolean canRead(ModFileCandidate candidate);

/**
     * 读取元数据并构造 {@link IModFile}.
     * @throws ModFileException 锚点文件缺失字段/语法错误/无法读取时
*/
    IModFile read(ModFileCandidate candidate, IModFileFactory factory) throws ModFileException;
}
