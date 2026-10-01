package dev.multiloader.bridge.fabric.meta;

import net.fabricmc.loader.api.metadata.ContactInformation;
import net.fabricmc.loader.api.metadata.Person;

/**
 * {@link Person} 的映射实现.
 * <p>来源:统一模型的 {@code ModMetadata.authors}({@code List<String>}).
 * <p><b>为什么 contact 恒为空</b>:{@code fabric.mod.json} 的 {@code authors}
 * 是**纯字符串数组**(如 {@code ["Alice", "Bob"]}),每个条目不携带联系方式 
 * 联系方式是 mod 级的 {@code contact} 段.所以这里每个 Person 的 contact 都是空的,
 * 且**共用同一个 EMPTY 实例**.
 * <p>不要因为"看起来该有 email"就去解析作者字符串里的 '@' 之类  那会把
 * "名字里恰好有 @ 的作者"变成假的联系方式.
*/
public final class FabricPerson implements Person {

    private final String name;

    public FabricPerson(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ContactInformation getContact() {
        // 见类注释:作者条目本身不带联系方式.
        return FabricContactInformation.EMPTY;
    }

    @Override
    public String toString() {
        return "FabricPerson[" + name + "]";
    }
}
