package dev.multiloader.mixin;

import dev.multiloader.common.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Mixin 冲突检测.
 * <p>检测三件事,按"是否致命"从高到低:
 * <ol>
 *   <li>{@link ConflictKind#OVERWRITE_COLLISION}  两个不同 mod 用 @Overwrite
 *       替换同一个目标类的同一个成员.这是**硬冲突**:无论什么加载顺序,
 *       后应用的都会把先应用的整个覆盖掉,且不会有任何报错.</li>
 *   <li>{@link ConflictKind#TARGET_CLASS_OVERLAP}  多个 mod 的 mixin 作用于
 *       同一个目标类.不必然出问题(注入点不同就相安无事),
 *       但这是排查注入顺序问题时的第一嫌疑,必须报出来.</li>
 *   <li>{@link ConflictKind#DUPLICATE_CONFIG_NAME}  两个 mod 声明了同名的
 *       mixin 配置文件.Mixin 按名字注册配置,重名会导致其中一个被静默忽略.</li>
 * </ol>
 * <p>只报"跨 mod"的冲突.同一个 mod 内部的两个 mixin 作用于同一个类是完全正常的
 * (甚至是最佳实践),报出来只会淹没真正的信号.
*/
public final class MixinConflictDetector {

    public enum ConflictKind {
        DUPLICATE_CONFIG_NAME,
        TARGET_CLASS_OVERLAP,
        OVERWRITE_COLLISION
    }

/** 严重度:越大越致命. */
    public enum Severity {
        WARN,
        ERROR
    }

/**
     * @param kind    冲突类型
     * @param target  冲突对象(配置名 / 目标类 / 目标成员)
     * @param sources 涉及的来源,形如 {@code modId:configName}
     * @param detail  人类可读说明
*/
    public record Conflict(ConflictKind kind, String target, List<String> sources, String detail) {

        public Severity severity() {
            return kind == ConflictKind.OVERWRITE_COLLISION ? Severity.ERROR : Severity.WARN;
        }

        @Override
        public String toString() {
            return "[%s/%s] %s <- %s (%s)"
                    .formatted(severity(), kind, target, sources, detail);
        }
    }

    public List<Conflict> detect(List<MixinConfigManager.MixinConfig> configs) {
        List<Conflict> conflicts = new ArrayList<>();
        conflicts.addAll(detectDuplicateConfigNames(configs));
        conflicts.addAll(detectTargetOverlap(configs));
        conflicts.addAll(detectOverwriteCollisions(configs));

        if (!conflicts.isEmpty()) {
            Log.warn("Mixin conflict detection found {} issue(s)", conflicts.size());
            for (Conflict conflict : conflicts) {
                Log.warn("  {}", conflict);
            }
        }
        return List.copyOf(conflicts);
    }

    // 1. 配置文件重名 

    private List<Conflict> detectDuplicateConfigNames(List<MixinConfigManager.MixinConfig> configs) {
        Map<String, Set<String>> owners = new LinkedHashMap<>();
        for (MixinConfigManager.MixinConfig config : configs) {
            owners.computeIfAbsent(config.configName(), k -> new LinkedHashSet<>()).add(config.origin());
        }

        List<Conflict> conflicts = new ArrayList<>();
        owners.forEach((configName, origins) -> {
            if (origins.size() > 1) {
                conflicts.add(new Conflict(ConflictKind.DUPLICATE_CONFIG_NAME, configName,
                        List.copyOf(origins),
                        "Mixin registers configs by name; duplicates cause one to be silently ignored"));
            }
        });
        return conflicts;
    }

    // 2. 目标类重叠 

    private List<Conflict> detectTargetOverlap(List<MixinConfigManager.MixinConfig> configs) {
        // 目标类 -> (modId -> origins),按 modId 去重,同 mod 内部不算冲突
        Map<String, Map<String, Set<String>>> byTarget = new TreeMap<>();

        for (MixinConfigManager.MixinConfig config : configs) {
            for (MixinConfigManager.MixinDeclaration declaration : config.declarations()) {
                for (String target : declaration.targetClasses()) {
                    byTarget.computeIfAbsent(target, k -> new LinkedHashMap<>())
                            .computeIfAbsent(config.modId(), k -> new LinkedHashSet<>())
                            .add(config.origin());
                }
            }
        }

        List<Conflict> conflicts = new ArrayList<>();
        byTarget.forEach((target, perMod) -> {
            if (perMod.size() > 1) {
                conflicts.add(new Conflict(ConflictKind.TARGET_CLASS_OVERLAP, target,
                        perMod.values().stream().flatMap(Set::stream).distinct().toList(),
                        "Target class mixed into by " + perMod.size() + " different mods"));
            }
        });
        return conflicts;
    }

    // 3. @Overwrite 硬冲突 

    private List<Conflict> detectOverwriteCollisions(List<MixinConfigManager.MixinConfig> configs) {
        // 目标类 + 成员签名 -> (modId -> origins)
        Map<String, Map<String, Set<String>>> byMember = new TreeMap<>();

        for (MixinConfigManager.MixinConfig config : configs) {
            for (MixinConfigManager.MixinDeclaration declaration : config.declarations()) {
                for (MixinConfigManager.MixinMember member : declaration.members()) {
                    if (member.policy() != MixinConfigManager.ConflictPolicy.OVERWRITE) {
                        // 只有 @Overwrite 的成员名/签名才是"目标成员".
                        // 对 @Inject 等方法名是处理器名,拿来比对会产出大量假冲突.
                        continue;
                    }
                    for (String target : declaration.targetClasses()) {
                        String key = target + "#" + member.name() + member.descriptor();
                        byMember.computeIfAbsent(key, k -> new LinkedHashMap<>())
                                .computeIfAbsent(config.modId(), k -> new LinkedHashSet<>())
                                .add(config.origin());
                    }
                }
            }
        }

        List<Conflict> conflicts = new ArrayList<>();
        byMember.forEach((key, perMod) -> {
            if (perMod.size() > 1) {
                conflicts.add(new Conflict(ConflictKind.OVERWRITE_COLLISION, key,
                        perMod.values().stream().flatMap(Set::stream).distinct().toList(),
                        "@Overwrite by multiple mods: the last applied silently discards the others"));
            }
        });
        return conflicts;
    }

/** 是否存在至少一个 ERROR 级冲突.生命周期层据此决定是否中止. */
    public static boolean hasFatal(List<Conflict> conflicts) {
        return conflicts.stream().anyMatch(c -> c.severity() == Severity.ERROR);
    }
}
