package dev.multiloader.mixin.platform;

import org.spongepowered.asm.service.IMixinServiceBootstrap;

/**
 * {@link MultiLoaderMixinService} 的引导声明.
 * <p>Mixin 的 {@code MixinBootstrap.offerInternals()} 之前会先确定"用哪个平台服务":
 * 它先看系统属性 {@code mixin.bootstrapService},拿不到才回退到
 * {@code ServiceLoader.load(IMixinServiceBootstrap.class, MixinService.class.getClassLoader())}.
 * <p>我们用系统属性走**确定性**路径(见 {@code MixinService.bootstrap()}),
 * 同时也在 {@code META-INF/services} 里注册一份:
 * 属性提供确定性,service 文件提供声明性  万一属性那条路被别的组件清掉,
 * 还有 ServiceLoader 兜底.
 * <p>{@link #bootstrap()} 是空的,和 Fabric 的 {@code MixinServiceKnotBootstrap} 一样:
 * 真正的初始化由调用方(我们的 {@code MixinService})在拿到服务之后统一做.
 * 在这里做会造成"服务自己启动自己"的循环依赖.
*/
public class MultiLoaderMixinServiceBootstrap implements IMixinServiceBootstrap {

    @Override
    public String getName() {
        return "MultiLoader";
    }

    @Override
    public String getServiceClassName() {
        return MultiLoaderMixinService.class.getName();
    }

    @Override
    public void bootstrap() {
        // 有意为空:见类注释
    }
}
