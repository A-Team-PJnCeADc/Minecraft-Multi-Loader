package dev.multiloader.api.metadata;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一个 mod 的身份与展示元数据.不可变.
 * <p>注意与 {@code IModFile} 的区别:一个 mod 文件可以声明**多个** mod
 * (NeoForge 的 {@code [[mods]]} 是数组表),所以这里是"一个 mod",
 * 而 {@code IModFile.getMetadataList()} 才是文件级的复数视图.
 * @param modId       全小写 mod 标识
 * @param version     版本字符串,解析交给 loader-core
 * @param displayName 展示名,可为 null
 * @param description 展示描述,可为 null
 * @param authors     作者列表,不可为 null,无作者时传 {@code List.of()}
 * @param license     许可证标识,可为 null
 * @param contacts    联系方式(homepage / sources / issues 等),不可为 null
 * @param environment 运行侧别
*/
public record ModMetadata(
        String modId,
        String version,
        String displayName,
        String description,
        List<String> authors,
        String license,
        Map<String, String> contacts,
        Environment environment) {

    public ModMetadata {
        Objects.requireNonNull(modId, "modId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(environment, "environment");

        if (modId.isBlank()) {
            throw new IllegalArgumentException("modId must not be blank");
        }
        if (version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank for mod " + modId);
        }

        authors = authors == null ? List.of() : List.copyOf(authors);
        contacts = contacts == null ? Map.of() : Map.copyOf(contacts);
    }

/** 只关心 id 与版本时的便捷构造(测试与内置 mod 用). */
    public static ModMetadata of(String modId, String version) {
        return new ModMetadata(modId, version, modId, null, List.of(), null, Map.of(), Environment.BOTH);
    }

/** 展示名,缺省回落到 modId. */
    public String displayNameOrId() {
        return displayName == null || displayName.isBlank() ? modId : displayName;
    }
}
