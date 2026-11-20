package dev.starsupply.api;

/** Physical strategic resource groups supported by StarSupply. */
public enum ResourceType {
    FOOD("军粮", "wheat"),
    MEDICAL("医疗物资", "medical_supplies"),
    EQUIPMENT("装备", "weapons"),
    MATERIALS("建筑材料", "steel"),
    ENGINEERING("工程物资", "explosives"),
    STRATEGIC("特殊战略资源", "ammunition");

    private final String label;
    private final String starCoreTradeId;

    ResourceType(String label, String starCoreTradeId) {
        this.label = label;
        this.starCoreTradeId = starCoreTradeId;
    }

    public String label() {
        return label;
    }

    /** Canonical StarCore agreement key; agreement quantity is enforced against mapped units. */
    public String starCoreTradeId() {
        return starCoreTradeId;
    }
}
