package dev.multiloader.bridge.neoforge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MinimalModInfo} 的三类方法行为测试.
 * <p>这三类**必须分开测**,因为它们代表三种不同的失败策略:
 * 有真实值 / 类型允许为空 / 需要真实对象才有意义.
 * 如果混在一起只测"不抛异常",就无法发现"本该返回真实值的方法返回了空" 
 * 那会让 mod 的 displayName 静默变成 null,而界面与日志都显示不出来.
*/
class MinimalModInfoTest {

    private static MinimalModInfo info() {
        return new MinimalModInfo("multiloader-neotest",
                "MultiLoader NeoForge Test Mod",
                "验证载体",
                "1.0.0");
    }

    // 第一类:有真实值

    @Test
    void realValueMethodsReturnTheParsedMetadata() {
        MinimalModInfo info = info();

        assertEquals("multiloader-neotest", info.getModId());
        assertEquals("MultiLoader NeoForge Test Mod", info.getDisplayName());
        assertEquals("验证载体", info.getDescription());
        assertEquals("1.0.0", info.getVersion().toString());
    }

    @Test
    void namespaceIsTheLowercasedModId() {
        // NeoForge 的 namespace 就是小写 modId.推导而非另设参数 
        // 真实 mods.toml 里不存在这个字段,让调用方传只会制造需要额外校验的不变量.
        assertEquals("multiloader-neotest", info().getNamespace());

        MinimalModInfo upper = new MinimalModInfo("MyMod", null, null, null);
        assertEquals("mymod", upper.getNamespace());
    }

    @Test
    void blankDisplayNameFallsBackToModId() {
        // 回退必须落在实现里:返回 null 会让每处使用者各写一遍回退,且样式不一.
        MinimalModInfo blank = new MinimalModInfo("mymod", "  ", null, "1.0.0");
        assertEquals("mymod", blank.getDisplayName());

        MinimalModInfo missing = new MinimalModInfo("mymod", null, null, "1.0.0");
        assertEquals("mymod", missing.getDisplayName());
    }

    @Test
    void nullVersionFallsBackWithoutThrowing() {
        // 版本号写得不规范不该阻断加载流程.
        MinimalModInfo noVersion = new MinimalModInfo("mymod", null, null, null);
        assertEquals("0.0.0", noVersion.getVersion().toString());
    }

    @Test
    void blankModIdIsRejected() {
        // modId 是下游(依赖求解,冲突检测)的键,空值没有意义,
        // 让它通过只会把失败推到很远的地方.
        assertThrows(IllegalArgumentException.class,
                () -> new MinimalModInfo("  ", null, null, "1.0.0"));
    }

    // 第二类:类型允许"没有" ->返回空(不抛异常)

    @Test
    void absentByTypeMethodsReturnEmptyRatherThanThrowing() {
        // 这些接口的语义本来就包含"可能为空".抛异常会让调用方
        // 无从判断"是不支持还是确实没有"  两者对调用方的处理方式不同.
        MinimalModInfo info = info();

        assertTrue(info.getDependencies().isEmpty());
        assertTrue(info.getForgeFeatures().isEmpty());
        assertTrue(info.getModProperties().isEmpty());
        assertTrue(info.getUpdateURL().isEmpty());
        assertTrue(info.getModURL().isEmpty());
        assertTrue(info.getLogoFile().isEmpty());
    }

    @Test
    void logoBlurIsFalseWhichIsTheOnlyNeutralValue() {
        // boolean 无法表达"没有".false 是唯一中性值,
        // 且与 getLogoFile() 返回空保持一致(没有 logo 就无所谓模糊).
        assertFalse(info().getLogoBlur());
    }

    // 第三类:需要真实对象才有意义 ->明确拒绝

    @Test
    void objectRequiringMethodsRejectExplicitly() {
        // 静默返回 null 会把问题推给调用方:它在别处 NPE,堆栈指向
        // NeoForge 内部而不是"我们没提供这个能力",归因方向完全错.
        MinimalModInfo info = info();

        assertThrows(UnsupportedOperationException.class, info::getOwningFile);
        assertThrows(UnsupportedOperationException.class, info::getLoader);
        assertThrows(UnsupportedOperationException.class, info::getConfig);
    }

    @Test
    void unsupportedMessagesNameTheConcreteReason() {
        // 异常信息必须说清"缺的是什么能力",而不是只给一句"不支持".
        // 排查时前者直接可行动,后者要去读源码.
        String message = assertThrows(UnsupportedOperationException.class,
                () -> info().getOwningFile()).getMessage();

        assertTrue(message.contains("IModFileInfo"), "异常信息应点明缺的具体类型，实际: " + message);
    }

    @Test
    void toStringMentionsModIdAndVersion() {
        // toString 会被日志与调试器用到,缺字段会让"这是哪个 mod"不可读.
        String text = info().toString();
        assertTrue(text.contains("multiloader-neotest"), text);
        assertTrue(text.contains("1.0.0"), text);
    }

/** 确保列表/映射的返回类型不是 null(NeoForge 侧会直接调用迭代). */
    @Test
    void collectionsAreNeverNull() {
        MinimalModInfo info = info();
        List<?> dependencies = info.getDependencies();
        Map<String, Object> properties = info.getModProperties();
        assertEquals(List.of(), dependencies);
        assertEquals(Map.of(), properties);
    }
}
