package dev.multiloader.agent.launch;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个可执行的启动配置.
 * <p>对应 Mojang 启动器里 "launch profile" 的概念:主类 + 类路径 + JVM 参数 + 游戏参数 + 工作目录.
 * <p>Q1 决策下当 agent 存在时,{@link #mainClass()} 是
 * {@code dev.multiloader.launcher.Main},而 {@link #gameMainClass()} 才是真正的游戏主类
 * 由 launcher.Main 在游戏层类加载器里反射调用.
 * @param mainClass     JVM 实际启动的主类
 * @param gameMainClass 真正的游戏主类(server: net.minecraft.server.Main)
 * @param classpath     完整类路径(游戏 jar + 库 + 我们的模块 + Mixin)
 * @param jvmArgs       JVM 参数
 * @param gameArgs      传给主类的参数
 * @param workingDir    游戏工作目录
 * @param agentJar      agent jar 路径;为 null 表示不挂 agent(vanilla 基线)
 * @param agentArgs     agent 参数
*/
public record LaunchProfile(String mainClass,
                            String gameMainClass,
                            List<Path> classpath,
                            List<String> jvmArgs,
                            List<String> gameArgs,
                            Path workingDir,
                            Path agentJar,
                            String agentArgs) {

/** 完整命令行(不含 java 可执行文件本身),用于打印与排障. */
    public List<String> commandLine(String javaExecutable) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable);

        if (agentJar != null) {
            command.add("-javaagent:" + agentJar + (agentArgs == null || agentArgs.isBlank()
                    ? "" : "=" + agentArgs));
        }

        command.addAll(jvmArgs);
        command.add("-cp");
        command.add(String.join(File.pathSeparator,
                classpath.stream().map(Path::toString).toList()));
        command.add(mainClass);
        command.addAll(gameArgs);
        return List.copyOf(command);
    }

/**
     * 游戏自身的类路径条目(不含加载器附加的 extraClasspath).
     * <p>agent 需要它来构建游戏层类加载器:如果把加载器自己的 jar 也塞进去,
     * 游戏层加载器会重复定义我们的类.
     * @param extraCount 末尾有多少条是加载器附加的.
     *                   若大于等于总条数(声称"全部都是附加的"),返回空列表 
     *                   防御性 clamp,宁可为空也不要 IndexOutOfBounds
*/
    public List<Path> gameClasspath(int extraCount) {
        int gameCount = Math.max(0, classpath.size() - extraCount);
        return List.copyOf(classpath.subList(0, gameCount));
    }

/** 人类可读摘要,日志里用. */
    public String summarize() {
        return """
                mainClass   : %s
                gameMain    : %s
                agent       : %s
                classpath   : %d entries
                jvmArgs     : %s
                gameArgs    : %s
                workingDir  : %s"""
                .formatted(mainClass,
                        gameMainClass,
                        agentJar == null ? "<none: vanilla baseline>" : agentJar.getFileName() + "=" + agentArgs,
                        classpath.size(),
                        jvmArgs,
                        gameArgs,
                        workingDir);
    }
}
