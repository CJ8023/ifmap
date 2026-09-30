package cn.cj.ifmap.core.rule;

/**
 * 规则元信息：用于启动期校验、规则清单文档、管理端展示。
 *
 * @author caijun
 */
public final class RuleDescriptor {

    private final String name;
    private final String owner;
    private final String signature;
    private final String desc;
    private final String example;
    private final boolean override;
    private final boolean allowNullArgs;

    RuleDescriptor(String name, String owner, String signature, String desc, String example,
                   boolean override, boolean allowNullArgs) {
        this.name = name;
        this.owner = owner;
        this.signature = signature;
        this.desc = desc;
        this.example = example;
        this.override = override;
        this.allowNullArgs = allowNullArgs;
    }

    /** 规则名。 */
    public String getName() {
        return name;
    }

    /** 声明类全名。 */
    public String getOwner() {
        return owner;
    }

    /** 方法签名（含参数类型），用于同名校验与文档。 */
    public String getSignature() {
        return signature;
    }

    public String getDesc() {
        return desc;
    }

    public String getExample() {
        return example;
    }

    /** 是否为覆盖注册。 */
    public boolean isOverride() {
        return override;
    }

    /** 是否允许实际参数为 null。 */
    public boolean allowsNullArgs() {
        return allowNullArgs;
    }

    @Override
    public String toString() {
        return name + " -> " + owner + "#" + signature;
    }
}
