package dev.multiloader.bridge.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.IExtensionPoint;
import net.neoforged.fml.config.ModConfig;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MinimalModContainer} 的行为测试.
 * <p>重点在两类断言:**继承来的 final 方法是否真的工作**,
 * 以及**不支持的方法是否响亮地失败而不是静默通过**.
*/
class MinimalModContainerTest {

    private static MinimalModInfo modInfo() {
        return new MinimalModInfo("multiloader-neotest", "Test Mod", "d", "1.0.0");
    }

    private static MinimalModContainer container(NeoForgeEventBus bus) {
        return new MinimalModContainer(modInfo(), bus);
    }

    // 抽象方法 + 继承来的 final 方法

    @Test
    void eventBusIsTheInjectedOne() {
        // 这是 @Mod 构造器注入会拿到的那个对象,必须就是同一个实例 
        // 如果这里返回一个新 bus,mod 注册的监听器会进到一个没人用的总线里,
        // 表现为"注册成功但永不触发".
        NeoForgeEventBus bus = new NeoForgeEventBus();
        assertSame(bus, container(bus).getEventBus());
    }

    @Test
    void getEventBusReturnsTheIEventBusContract() {
        NeoForgeEventBus bus = new NeoForgeEventBus();
        IEventBus asContract = container(bus).getEventBus();
        assertSame(bus, asContract);
    }

    @Test
    void modIdAndNamespaceComeFromThePassedModInfo() {
        // 关键点:getModId()/getNamespace() 在父类里是 **final**,读的是构造器
        // 写入的字段.所以它们能工作,靠的是我们传对了 IModInfo,
        // 而不是在这里覆写  试图覆写会被编译器拒绝.
        MinimalModContainer container = container(new NeoForgeEventBus());

        assertEquals("multiloader-neotest", container.getModId());
        assertEquals("multiloader-neotest", container.getNamespace());
    }

    @Test
    void getModInfoReturnsWhatWePassed() {
        assertEquals("Test Mod", container(new NeoForgeEventBus()).getModInfo().getDisplayName());
    }

    // 不支持的能力:必须响亮失败

/** 一个具体的扩展点类型;{@code IExtensionPoint} 是空接口,实现它是零成本的. */
    static final class TestExtension implements IExtensionPoint {
    }

    @Test
    void extensionPointMethodsRejectExplicitly() {
        // 这些方法在 @Mod 构造器里被 mod 调用.静默通过会让 mod 以为
        // "扩展点注册好了",然后整个运行期表现为能力不生效 
        // 那种症状会被归因到 mod 自己,而不会想到是桥接层没实现.
        MinimalModContainer container = container(new NeoForgeEventBus());
        // 显式类型见证 <TestExtension>:两个重载 (Class<T>, T) 与 (Class<T>, Supplier<T>)
        // 会让编译器在推断 T 时产生歧义(实例与方法引用都能被解释两种方式).
        // 见证不是绕过检查,而是把"我们指的是哪一个 T"写清楚.
        Supplier<TestExtension> supplier = TestExtension::new;

        assertThrows(UnsupportedOperationException.class,
                () -> container.getCustomExtension(TestExtension.class));
        assertThrows(UnsupportedOperationException.class,
                () -> container.<TestExtension>registerExtensionPoint(
                        TestExtension.class, new TestExtension()));
        assertThrows(UnsupportedOperationException.class,
                () -> container.<TestExtension>registerExtensionPoint(TestExtension.class, supplier));
    }

    @Test
    void configRegistrationRejectsExplicitly() {
        MinimalModContainer container = container(new NeoForgeEventBus());

        assertThrows(UnsupportedOperationException.class,
                () -> container.registerConfig(ModConfig.Type.STARTUP, null));
        assertThrows(UnsupportedOperationException.class,
                () -> container.registerConfig(ModConfig.Type.STARTUP, null, "x.toml"));
    }

    @Test
    void rejectionMessagesPointAtTheDesignDoc() {
        // 排查时"见 docs/02-bridge-design.md §6"直接可行动;
        // 只给一句"不支持"要去读源码才知道边界在哪.
        String message = assertThrows(UnsupportedOperationException.class,
                () -> container(new NeoForgeEventBus()).registerConfig(ModConfig.Type.STARTUP, null))
                .getMessage();

        assertTrue(message.contains("registerConfig"), message);
        assertTrue(message.contains("02-bridge-design.md"), message);
    }

    @Test
    void toStringMentionsModId() {
        assertTrue(container(new NeoForgeEventBus()).toString().contains("multiloader-neotest"));
    }
}
