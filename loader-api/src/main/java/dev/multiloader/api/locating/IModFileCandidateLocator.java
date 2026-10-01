package dev.multiloader.api.locating;

/**
 * 定位待扫描的 Mod 文件候选(如扫描 {@code mods/} 目录).
 * <p>通过 {@code META-INF/services} 注册.核心层用 ServiceLoader 聚合全部实现,
 * 因此"Fabric 只在主目录找,某启动器要求在别处找"这类差异不需要核心层知道.
*/
public interface IModFileCandidateLocator {

    void findCandidates(IModFileCandidateConsumer consumer);
}
