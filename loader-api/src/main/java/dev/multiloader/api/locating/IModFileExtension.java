package dev.multiloader.api.locating;

/**
 * 标记接口:格式适配器的私有数据载体.
 * <p>存在的意义是阻止核心接口膨胀.举例:
 * <ul>
 *   <li>{@code FabricModFileExtension}:accessWidener,entrypoints,
 *       languageAdapters,provides,嵌套 jar 列表</li>
 *   <li>{@code NeoForgeModFileExtension}:features,modLoader 名</li>
 *   <li>{@code ForgeModFileExtension}:FMLModType,FMLCorePlugin,FMLAT</li>
 * </ul>
 * <p>核心层只通过 {@link IModFile#getExtension(Class)} 做透传,
 * 绝不 import 任何具体实现,也绝不为某个加载器的字段往 {@link IModFile} 上加方法.
*/
public interface IModFileExtension {
}
