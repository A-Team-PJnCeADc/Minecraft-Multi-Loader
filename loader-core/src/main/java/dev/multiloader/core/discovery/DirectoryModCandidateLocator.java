package dev.multiloader.core.discovery;

import dev.multiloader.api.locating.IModFileCandidateConsumer;
import dev.multiloader.api.locating.IModFileCandidateLocator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 扫描目录下的 mod 文件候选.
 * <p>刻意**不**在这里判断格式:这里只产出候选("这可能是个 mod 文件"),
 * 格式识别由 {@code IModFileReader} 基于内容完成.
 * <p>顺序按文件名排序:确定性是刚需同一份 mods 目录必须每次得到同一个加载顺序,
 * 否则 Mixin 冲突排查会变成掷骰子.
*/
public final class DirectoryModCandidateLocator implements IModFileCandidateLocator {

    private static final String JAR_SUFFIX = ".jar";
    private static final String DISABLED_SUFFIX = ".disabled";

    private final Path directory;

    public DirectoryModCandidateLocator(Path directory) {
        this.directory = directory;
    }

    public Path directory() {
        return directory;
    }

    @Override
    public void findCandidates(IModFileCandidateConsumer consumer) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        for (Path candidate : listModFiles()) {
            consumer.accept(new dev.multiloader.api.locating.ModFileCandidate(candidate, null));
        }
    }

    private List<Path> listModFiles() {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(DirectoryModCandidateLocator::looksLikeModFile)
                    .sorted(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean looksLikeModFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.startsWith(".") || name.endsWith(DISABLED_SUFFIX)) {
            return false;
        }
        return name.endsWith(JAR_SUFFIX);
    }
}
