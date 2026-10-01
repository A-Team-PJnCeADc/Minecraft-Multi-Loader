package dev.multiloader.classloader;

import dev.multiloader.api.transform.ClassProcessor;
import dev.multiloader.common.GameNamespace;
import dev.multiloader.common.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 类加载器图:装配平台层 / 加载器层 / 游戏层三层.
 * <p>分层(自下而上):
 * <pre>
 *   platform  (JDK 标准模块)               由调用方传入,通常是平台类加载器
 *   loader    (MultiLoader 自身)           即加载本类的那个类加载器
 *   game      (TransformingClassLoader)    Minecraft + 全部 mod
 * </pre>
 * <p>刻意不引用 {@code loader-agent} 的类型({@code StartupArgs} /
 * {@code ProbeResult}):loader-agent 依赖本模块,反向引用会形成环.
 * 因此输入收敛成 {@link GameNamespace} 与 {@link Path}.
*/
public final class ClassLoaderGraph implements AutoCloseable {

/** 默认的 child-first 前缀.mod 包前缀由 {@link Builder#addModRoot} 追加. */
    private static final Set<String> DEFAULT_CHILD_FIRST = Set.of("net.minecraft.");

    private final ClassLoader platformLoader;
    private final ClassLoader loaderLoader;
    private final TransformingClassLoader gameLoader;
    private final ClassPathIndex index;
    private final GameNamespace namespace;

    private ClassLoaderGraph(ClassLoader platformLoader,
                             TransformingClassLoader gameLoader,
                             ClassPathIndex index,
                             GameNamespace namespace) {
        this.platformLoader = platformLoader;
        this.loaderLoader = ClassLoaderGraph.class.getClassLoader();
        this.gameLoader = gameLoader;
        this.index = index;
        this.namespace = namespace;
    }

    public static Builder builder(ClassLoader platformParent, GameNamespace namespace) {
        return new Builder(platformParent, namespace);
    }

    public ClassLoader platformLoader() {
        return platformLoader;
    }

    public ClassLoader loaderLoader() {
        return loaderLoader;
    }

    public TransformingClassLoader gameLoader() {
        return gameLoader;
    }

    public ClassPathIndex index() {
        return index;
    }

    public GameNamespace namespace() {
        return namespace;
    }

    @Override
    public void close() {
        gameLoader.close();
    }

    public static final class Builder {

        private final ClassLoader platformParent;
        private final GameNamespace namespace;
        private final List<ClassPathIndex.Root> roots = new ArrayList<>();
        private final Set<String> childFirstPrefixes = new LinkedHashSet<>(DEFAULT_CHILD_FIRST);
        private final List<ClassProcessor> processors = new ArrayList<>();

        private Builder(ClassLoader platformParent, GameNamespace namespace) {
            this.platformParent = platformParent;
            this.namespace = namespace;
        }

/** 追加游戏/库类路径根(按调用顺序即查找优先级). */
        public Builder addClasspathRoot(Path root, ClassPathIndex.Scope scope) {
            if (root != null && Files.exists(root)) {
                roots.add(new ClassPathIndex.Root(root, scope));
            } else if (root != null) {
                Log.warn("Skipping non-existent classpath root: {}", root);
            }
            return this;
        }

        public Builder addClasspathRoot(Path root) {
            return addClasspathRoot(root, ClassPathIndex.Scope.GAME);
        }

/** 批量添加类路径根(启动器一次给出整条游戏类路径时用). */
        public Builder addClasspathRoots(java.util.Collection<Path> roots) {
            for (Path root : roots) {
                addClasspathRoot(root);
            }
            return this;
        }

/**
         * 追加一个 mod 的类路径根.
         * @param childFirstPackage mod 自己的包前缀(点分,结尾带 '.'),
         *                          用于让 mod 的类优先于父加载器解析
*/
        public Builder addModRoot(Path root, String childFirstPackage) {
            addClasspathRoot(root, ClassPathIndex.Scope.MOD);
            if (childFirstPackage != null && !childFirstPackage.isBlank()) {
                childFirstPrefixes.add(childFirstPackage.endsWith(".")
                        ? childFirstPackage
                        : childFirstPackage + ".");
            }
            return this;
        }

/** 覆盖默认的 child-first 前缀集合(会清掉默认值). */
        public Builder childFirstPrefixes(Set<String> prefixes) {
            childFirstPrefixes.clear();
            childFirstPrefixes.addAll(prefixes);
            return this;
        }

        public Builder addProcessor(ClassProcessor processor) {
            processors.add(processor);
            return this;
        }

        public ClassLoaderGraph build() {
            ClassPathIndex index = new ClassPathIndex(roots);

            TransformingClassLoader gameLoader = new TransformingClassLoader(
                    "MultiLoader Game", index, childFirstPrefixes, platformParent);

            for (ClassProcessor processor : processors) {
                gameLoader.addProcessor(processor);
            }

            if (namespace == GameNamespace.OBFUSCATED) {
                Log.warn("Building game class loader for OBFUSCATED namespace, "
                        + "but no remapping processor is registered (compat layer not implemented).");
            }
            Log.info("ClassLoaderGraph built: roots={} childFirst={} pipeline={}",
                    roots.size(), childFirstPrefixes, gameLoader.describePipeline());

            return new ClassLoaderGraph(platformParent, gameLoader, index, namespace);
        }
    }
}
