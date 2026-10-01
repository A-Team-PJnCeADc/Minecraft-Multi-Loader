package dev.multiloader.api.locating;

import java.nio.file.Path;

/**
 * 一个待识别的 Mod 文件候选.
 * @param path     文件路径(jar 或目录)
 * @param typeHint 来源给出的格式提示,可为 null.
 *                 为 null 时适配器必须靠内容探测(锚点文件)判断,
 *                 不得依赖路径后缀.
*/
public record ModFileCandidate(Path path, String typeHint) {

    public ModFileCandidate(Path path) {
        this(path, null);
    }
}
