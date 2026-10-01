package dev.multiloader.agent;

import dev.multiloader.common.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 版本/命名空间探测的排障工具.
 * <p>这是产品的一部分而不是一次性脚本:加载器在用户的机器上跑,
 * "为什么我的实例被判定成了混淆版"必须能一条命令查清.
 * <p>用法(Gradle):
 * <pre>
 *   ./gradlew :loader-agent:probeTool -Pjars="/path/to/minecraft-client.jar"
 *   ./gradlew :loader-agent:probeTool -Pjars="/a.jar,/b.jar" -PmcVersion=26.3
 * </pre>
 * <p>直接跑类:
 * <pre>
 *   java -cp loader-agent/build/classes/java/main:... dev.multiloader.agent.ProbeTool &lt;jar&gt;...
 * </pre>
 * <p>输出首行即 {@code namespace=... mcVersion=... patchProvider=...},
 * 其后是逐条证链(缩进).
*/
public final class ProbeTool {

    private ProbeTool() {
    }

    public static void main(String[] args) {
        List<Path> classpath = new ArrayList<>();
        for (String arg : args) {
            String trimmed = arg.trim();
            if (!trimmed.isEmpty()) {
                classpath.add(Path.of(trimmed));
            }
        }

        if (classpath.isEmpty()) {
            System.out.println("usage: ProbeTool <jar-or-classpath-entry>...");
            System.out.println("       (each argument is probed; entries that do not exist are reported)");
            return;
        }

        String declaredVersion = System.getProperty("probe.mcVersion");

        for (Path p : classpath) {
            if (!Files.exists(p)) {
                System.out.println("warning: path does not exist: " + p);
            }
        }

        VersionDetector.ProbeResult result = VersionDetector.detect(classpath, declaredVersion);

        System.out.println(result.summaryLine());
        for (String line : result.evidence()) {
            System.out.println(line);
        }

        // 排障时 verbose 打开更有用
        Log.info("probe complete: {}", result.summaryLine());
    }
}
