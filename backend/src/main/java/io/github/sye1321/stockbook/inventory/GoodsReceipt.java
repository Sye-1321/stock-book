package io.github.sye1321.stockbook.inventory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GoodsReceipt(
        UUID id,
        UUID requestId,
        UUID supplierId,
        String supplierCode,
        UUID warehouseId,
        String warehouseCode,
        String supplierDocumentReference,
        UUID postedBy,
        String postedByName,
        Instant postedAt,
        List<GoodsReceiptLine> lines) {}
