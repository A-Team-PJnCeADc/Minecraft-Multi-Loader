package dev.multiloader.api.metadata;

/**
 * Mod 声明的运行侧别.
 * <p>两个加载器的原始取值到本枚举的映射:
 * <ul>
 *   <li>Fabric {@code fabric.mod.json}:{@code "*"} ->{@link #BOTH},
 *       {@code "client"} ->{@link #CLIENT},{@code "server"} ->{@link #SERVER}</li>
 *   <li>NeoForge / Forge {@code mods.toml} 的 {@code side}:
 *       {@code "BOTH"} / {@code "CLIENT"} / {@code "SERVER"}</li>
 * </ul>
*/
public enum Environment {

    CLIENT,
    SERVER,

/** 双侧.绝大多数 mod 是这个. */
    BOTH;

/**
     * 是否在客户端生效.
     * <p>注意 {@link #BOTH} 同时满足 client 与 server  它不是"两侧都不".
     * 写成 {@code this == CLIENT} 会让双侧配置在两边都被丢掉,是个静默失效.
*/
    public boolean isClient() {
        return this == CLIENT || this == BOTH;
    }

/** 是否在服务端生效.{@link #BOTH} 同时满足两侧. */
    public boolean isServer() {
        return this == SERVER || this == BOTH;
    }

/**
     * 本侧别声明是否在给定侧别上生效.
     * <p>这是"侧别匹配"这条规则的**唯一实现**:{@code MixinConfigRef} 与入口点分发
     * 都委托到这里,避免同一个三目表达式散落多处.
     * @param clientSide 当前进程是客户端时为 true
*/
    public boolean appliesTo(boolean clientSide) {
        return clientSide ? isClient() : isServer();
    }
}
