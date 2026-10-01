package dev.multiloader.core.discovery;

import dev.multiloader.api.locating.IModFile;
import dev.multiloader.api.locating.IModFileCandidateLocator;
import dev.multiloader.api.locating.IModFileFactory;
import dev.multiloader.api.locating.IModFileReader;
import dev.multiloader.api.locating.ModFileCandidate;
import dev.multiloader.api.locating.ModFileException;
import dev.multiloader.common.Log;
import dev.multiloader.core.service.ServiceRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Mod 发现.
 * <p>流程:locator 产出候选 ->reader(按 priority 升序)认领 ->读元数据 ->产出
 * {@link IModFile}.全程没有 {@code if (format.equals("fabric"))} 这类分支:
 * 加一个格式就是加一个 reader 的 service 文件.
 * <p>一个候选可能被多个 reader 同时认领(例如一个 jar 同时含
 * {@code fabric.mod.json} 与 {@code mods.toml}).当前取**优先级最高**的一个,
 * 真正的"多格式共存"留给后续阶段那时这里会变成一个候选产出多个 IModFile.
*/
public final class ModDiscoverer {

/**
     * 发现结果.
     * @param modFiles 成功识别的 mod 文件
     * @param problems 逐条问题描述:无 reader 认领 / 元数据非法 / 读取失败.
     *                 是否致命由调用方决定(发现阶段不擅自终止进程)
*/
    public record DiscoveryResult(List<IModFile> modFiles, List<String> problems) {

        public boolean hasProblems() {
            return !problems.isEmpty();
        }
    }

    private final List<IModFileCandidateLocator> locators;
    private final List<IModFileReader> readers;
    private final IModFileFactory factory;

    public ModDiscoverer(List<IModFileCandidateLocator> locators,
                         List<IModFileReader> readers,
                         IModFileFactory factory) {
        this.locators = List.copyOf(locators);
        this.readers = readers.stream()
                .sorted(Comparator.comparingInt(IModFileReader::priority))
                .toList();
        this.factory = factory;
    }

/** 从 ServiceLoader 取 reader,工厂用默认实现. */
    public static ModDiscoverer fromServices(List<IModFileCandidateLocator> locators) {
        return new ModDiscoverer(locators,
                ServiceRegistry.loadAll(IModFileReader.class),
                new ModFileFactory());
    }

    public DiscoveryResult run() {
        List<IModFile> modFiles = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        for (ModFileCandidate candidate : collectCandidates(problems)) {
            Optional<IModFileReader> reader = readers.stream()
                    .filter(r -> safelyCanRead(r, candidate))
                    .findFirst();

            if (reader.isEmpty()) {
                problems.add("No reader recognised: " + candidate.path());
                continue;
            }

            try {
                IModFile modFile = reader.get().read(candidate, factory);
                modFiles.add(modFile);
                Log.debug("Discovered {} via {}", modFile, reader.get().getClass().getSimpleName());
            } catch (ModFileException e) {
                problems.add("Failed to read " + candidate.path() + " as "
                        + reader.get().getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        Log.info("Mod discovery: {} mod file(s), {} problem(s)", modFiles.size(), problems.size());
        for (String problem : problems) {
            Log.warn("  {}", problem);
        }
        return new DiscoveryResult(List.copyOf(modFiles), List.copyOf(problems));
    }

    private List<ModFileCandidate> collectCandidates(List<String> problems) {
        List<ModFileCandidate> candidates = new ArrayList<>();
        for (IModFileCandidateLocator locator : locators) {
            try {
                locator.findCandidates(candidates::add);
            } catch (RuntimeException e) {
                problems.add("Candidate locator " + locator.getClass().getSimpleName() + " failed: " + e);
            }
        }
        return candidates;
    }

/** reader 的 canRead 不允许把整个发现流程带崩:异常按"不认识"处理. */
    private static boolean safelyCanRead(IModFileReader reader, ModFileCandidate candidate) {
        try {
            return reader.canRead(candidate);
        } catch (RuntimeException e) {
            Log.debug("Reader {} threw during canRead({}): {}",
                    reader.getClass().getSimpleName(), candidate.path(), e.toString());
            return false;
        }
    }
}
