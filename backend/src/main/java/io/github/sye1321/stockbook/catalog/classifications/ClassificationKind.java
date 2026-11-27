package io.github.sye1321.stockbook.catalog.classifications;

public enum ClassificationKind {
    CATEGORY("product_category"),
    BRAND("product_brand"),
    FAMILY("product_family");

    private final String table;

    ClassificationKind(String table) {
        this.table = table;
    }

    String table() {
        return table;
    }
}
