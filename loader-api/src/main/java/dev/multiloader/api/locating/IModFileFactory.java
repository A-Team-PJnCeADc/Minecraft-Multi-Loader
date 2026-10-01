package dev.multiloader.api.locating;

import dev.multiloader.api.metadata.ModDependency;
import dev.multiloader.api.metadata.ModMetadata;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 由 loader-core 注入给适配器,用来构造 {@link IModFile}.
 * <p>为什么要工厂而不是让适配器自己 new:核心层需要在装配时统一做
 * 校验,统计与缓存,并且要保证 {@code IModFile} 的实现类只有一份
 * (适配器提供的是数据,不是加载器实现).用 builder 而不是超长参数列表,
 * 避免每加一个可选字段就破坏所有调用点.
*/
public interface IModFileFactory {

    Builder builder(ModFileCandidate candidate, String format);

    interface Builder {

        Builder metadata(ModMetadata metadata);

        Builder metadata(List<ModMetadata> metadata);

        Builder dependencies(List<ModDependency> dependencies);

/**
         * 声明 mixin 配置(推荐路径:含生效侧别).
         * <p>侧别不是可省略的细节见 {@link MixinConfigRef}.
*/
        Builder mixinConfigRefs(List<MixinConfigRef> refs);

/**
         * 便捷路径:只给配置名,全部按双侧处理.
         * <p>仅适用于确实没有侧别信息的场景(测试,诊断).
         * 格式适配器应当用 {@link #mixinConfigRefs(List)}.
*/
        Builder mixinConfigs(Set<String> mixinConfigs);

        Builder classpathRoot(Path root);

        Builder nestedLibrary(Path nested);

        Builder extension(IModFileExtension extension);

/**
         * @throws IllegalStateException 未声明任何 mod 时
*/
        IModFile build();
    }
}
