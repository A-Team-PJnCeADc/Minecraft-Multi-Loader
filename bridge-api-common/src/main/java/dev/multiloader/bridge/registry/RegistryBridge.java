package dev.multiloader.bridge.registry;

/**
 * 注册表桥接 统一层的**边界声明**,本期不提供实现.
 * <p><b>为什么现在只放"边界声明"而不放方法</b>:注册表语义(
 * NeoForge 的 {@code RegisterEvent},Fabric 的 registry 注册时机)在各加载器间
 * 差异很大,且它属于设计文档 §3 明确不映射的类别.此刻凭空定义
 * {@code register(Identifier, Object)} 这种签名,等于把某一加载器的形状
 * 当成通用形状 那是"边写边改 API"的典型,改起来要动所有调用方.
 * <p>所以本期只约定**如何询问可用性**,让调用方能明确分支而不是靠猜:
 * <pre>{@code
 * if (bridge.supported()) { ... } else { log.warn(bridge.unsupportedReason()); }
 * }</pre>
 * 这比"接口里放几个将来会改的方法"更稳:没有方法就没有错误的方法可依赖.
 * <p>实现推迟的理由与 §3 一致:需要先有各加载器注册表系统的统一抽象.
*/
public interface RegistryBridge {

/** 本桥接当前是否可用.本期恒为 false. */
    boolean supported();

/**
     * 不可用的原因;{@link #supported()} 为 true 时返回 null.
     * <p>刻意返回"原因"而不是仅一个布尔:调用方无法据 false 决定该报什么给用户,
     * 于是要么静默,要么自己编一句话  两者都会让诊断信息与真实原因脱节.
*/
    String unsupportedReason();
}
