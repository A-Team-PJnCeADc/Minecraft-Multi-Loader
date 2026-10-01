package dev.multiloader.api.locating;

import dev.multiloader.api.metadata.Environment;

import java.util.Objects;

/**
 * 一条 mixin 配置声明.
 * <p>为什么需要它而不是一个 {@code String}:配置的**生效侧别**只能由格式适配器提供,
 * 无法从配置名推导.
 * <ul>
 *   <li>Fabric:{@code {"config": "x.mixins.json", "environment": "client"}}</li>
 *   <li>NeoForge / Forge:{@code [[mixins]]} 块</li>
 * </ul>
 * 丢掉侧别不是"少个字段"这么轻:服务端会去加载 client-only 的 mixin 配置,
 * 而那个配置里的 mixin 类引用客户端类  崩的是整个服务端,不是某个 mod.
 * <p>刻意不含 {@code required} 字段:那是 mixins.json **文件内容**里的属性,
 * 由 {@code MixinConfigManager} 解析文件时得到.放在这里会造成同一个事实有两处来源.
 * @param configName  配置文件名,相对 mod 根,如 {@code mymod.mixins.json}
 * @param environment 生效侧别
*/
public record MixinConfigRef(String configName, Environment environment) {

    public MixinConfigRef {
        Objects.requireNonNull(configName, "configName");
        Objects.requireNonNull(environment, "environment");
        if (configName.isBlank()) {
            throw new IllegalArgumentException("configName must not be blank");
        }
    }

/** 双侧配置(绝大多数 mod 的形态). */
    public MixinConfigRef(String configName) {
        this(configName, Environment.BOTH);
    }

    public boolean appliesToClient() {
        return environment.isClient();
    }

    public boolean appliesToServer() {
        return environment.isServer();
    }

/**
     * 是否应在给定侧别加载本配置.
     * <p>刻意接收 {@code boolean} 而不是 {@code Side}:{@code Side} 位于
     * runtime-common,而 loader-api 必须保持零依赖(硬不变量 INV-1).
     * @param clientSide 当前进程是客户端时为 true
*/
    public boolean appliesTo(boolean clientSide) {
        // 委托给 Environment:侧别匹配规则只有一个实现
        return environment.appliesTo(clientSide);
    }

    @Override
    public String toString() {
        return environment == Environment.BOTH ? configName : configName + "(" + environment + ")";
    }
}
