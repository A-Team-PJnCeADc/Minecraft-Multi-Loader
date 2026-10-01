package dev.multiloader.bridge.fabric.meta;

import net.fabricmc.loader.api.metadata.ModOrigin;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 增量 1a 三个映射类型的正确性测试.
 * <p>{@code Version} 与 {@code ModEnvironment} 不在此列  它们**复用上游**
 * ({@code VersionParser.parse} 与枚举本身),不写实现,因此没有可测的映射代码.
 * 复用的理由见 {@code docs/04}:自写版本比较会与 Fabric 的选版语义产生静默差异.
*/
class MetaMappingTest {

    // ContactInformation

    @Test
    void contactInformationKeepsEveryOpenKey() {
        // feel 的键是开放的(有的 mod 写 discord,有的写 email).
        // 只认"我们认识的键"会让其余静默丢失,而 asMap() 的调用方正是要看全部.
        var contact = new FabricContactInformation(Map.of(
                "homepage", "https://example.invalid",
                "discord", "https://discord.invalid/invite",
                "email", "a@b.invalid"));

        assertEquals(3, contact.asMap().size());
        assertEquals("https://example.invalid", contact.get("homepage").orElseThrow());
        assertEquals("https://discord.invalid/invite", contact.get("discord").orElseThrow());
    }

    @Test
    void missingContactKeyIsEmptyOptionalNotNull() {
        assertTrue(FabricContactInformation.EMPTY.get("nope").isEmpty());
    }

    @Test
    void asMapIsImmutable() {
        var contact = new FabricContactInformation(Map.of("homepage", "x"));

        assertThrows(UnsupportedOperationException.class,
                () -> contact.asMap().put("injected", "y"));
    }

    @Test
    void mappingIsCopiedNotAliased() {
        // 构造后改动源 Map 不得影响已建对象:fabric.mod.json 的解析结果可能在别处被复用.
        var source = new java.util.HashMap<String, String>();
        source.put("homepage", "x");
        var contact = new FabricContactInformation(source);

        source.put("later", "y");

        assertEquals(1, contact.asMap().size());
        assertTrue(contact.get("later").isEmpty());
    }

    // Person

    @Test
    void personCarriesOnlyTheName() {
        assertEquals("Alice", new FabricPerson("Alice").getName());
    }

    @Test
    void personContactIsEmptyAndShared() {
        // 作者条目本身不带联系方式(那是 mod 级的 contact 段).
        // 并共用 EMPTY 实例  每个作者都 new 一个空表是无谓开销.
        assertSame(FabricContactInformation.EMPTY, new FabricPerson("Alice").getContact());
        assertTrue(new FabricPerson("Alice").getContact().asMap().isEmpty());
    }

    @Test
    void personNameWithAtSignIsNotTurnedIntoContact() {
        // 反例:不要"聪明地"把名字里看起来像邮箱的部分解析成联系方式 
        // 那会把"名字里恰好有 @ 的作者"变成假的 contact 数据.
        var person = new FabricPerson("weird@name");

        assertEquals("weird@name", person.getName());
        assertTrue(person.getContact().asMap().isEmpty());
    }

    // ModOrigin

    @Test
    void topLevelModFileIsAPathOrigin(@org.junit.jupiter.api.io.TempDir Path tmp) {
        Path jar = tmp.resolve("mymod.jar");
        ModOrigin origin = new FabricModOrigin(jar);

        assertEquals(ModOrigin.Kind.PATH, origin.getKind());
        assertEquals(java.util.List.of(jar), origin.getPaths());
    }

    @Test
    void topLevelOriginHasNoParent() {
        // Fabric 的约定:只有 NESTED 形态才有父.顶层文件返回 null 而不是空串 
        // 空串会让"有没有父"的判断变成""成立"这种荒谬结果.
        ModOrigin origin = new FabricModOrigin(Path.of("mymod.jar"));

        assertNull(origin.getParentModId());
        assertNull(origin.getParentSubLocation());
    }
}
