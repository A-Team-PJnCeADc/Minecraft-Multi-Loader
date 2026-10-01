package dev.multiloader.bridge.network;

/**
 * 网络桥接 统一层的**边界声明**,本期不提供实现.
 * <p>与 {@link dev.multiloader.bridge.registry.RegistryBridge} 同构,理由相同:
 * 网络事件的统一抽象(信道注册,payload 编解码,方向)在加载器间差异大,
 * 且属于设计文档 §3 明确不映射的类别.本期只约定"如何询问可用性",
 * 不放任何将来会改的注册/发送方法.
 * <p>附带说明一条<b>已知的连带关系</b>:设计文档 §2 里 {@code post} 抛
 * {@code UnsupportedOperationException},而网络事件的送达依赖事件分发.
 * 所以网络桥接的实现**必须**排在 {@code post} 与事件分发之后
 * 先做网络注册接口会得到一个"能注册但永远收不到消息"的空壳.
*/
public interface NetworkBridge {

/** 本桥接当前是否可用.本期恒为 false. */
    boolean supported();

/** 不可用的原因;{@link #supported()} 为 true 时返回 null. */
    String unsupportedReason();
}
