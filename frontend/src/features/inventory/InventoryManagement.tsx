import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { ApiError, isRecord, readJson } from "../../app/api";
import { CsrfRequestError, fetchCsrf } from "../auth/csrf";
import {
  isConversion,
  isProduct,
  Product,
  ProductConversion,
} from "../catalog/types";

type Warehouse = {
  id: string;
  code: string;
  name: string;
  active: boolean;
};

type Balance = {
  warehouseId: string;
  warehouseCode: string;
  warehouseName: string;
  productId: string;
  productSku: string;
  productName: string;
  baseUnitId: string;
  baseUnitCode: string;
  baseQuantity: string;
  updatedAt: string;
};

type Movement = {
  id: string;
  requestId: string | null;
  transferId: string | null;
  receiptLineId: string | null;
  receiptId: string | null;
  warehouseId: string;
  warehouseCode: string;
  productId: string;
  productSku: string;
  type:
    | "OPENING"
    | "ADJUSTMENT_IN"
    | "ADJUSTMENT_OUT"
    | "TRANSFER_IN"
    | "TRANSFER_OUT"
    | "RECEIPT_IN";
  enteredQuantity: string;
  enteredUnitId: string;
  enteredUnitCode: string;
  conversionFactor: string;
  baseQuantity: string;
  reason: string | null;
  postedBy: string;
  postedByName: string;
  postedAt: string;
};

type Transfer = {
  id: string;
  requestId: string;
  sourceWarehouseId: string;
  sourceWarehouseCode: string;
  destinationWarehouseId: string;
  destinationWarehouseCode: string;
  productId: string;
  productSku: string;
  enteredQuantity: string;
  enteredUnitId: string;
  enteredUnitCode: string;
  conversionFactor: string;
  baseQuantity: string;
  reason: string;
  postedBy: string;
  postedByName: string;
  postedAt: string;
};

type MovementPage = {
  items: Movement[];
  page: number;
  size: number;
  hasMore: boolean;
};

type Props = {
  isAdmin: boolean;
  onSessionExpired: (message?: string) => void;
  revision?: number;
};

function isWarehouse(value: unknown): value is Warehouse {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    typeof value.code === "string" &&
    typeof value.name === "string" &&
    typeof value.active === "boolean"
  );
}

function isBalance(value: unknown): value is Balance {
  return (
    isRecord(value) &&
    typeof value.warehouseId === "string" &&
    typeof value.warehouseCode === "string" &&
    typeof value.warehouseName === "string" &&
    typeof value.productId === "string" &&
    typeof value.productSku === "string" &&
    typeof value.productName === "string" &&
    typeof value.baseUnitId === "string" &&
    typeof value.baseUnitCode === "string" &&
    typeof value.baseQuantity === "string" &&
    typeof value.updatedAt === "string"
  );
}

function isMovement(value: unknown): value is Movement {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    (typeof value.requestId === "string" || value.requestId === null) &&
    (typeof value.transferId === "string" || value.transferId === null) &&
    (typeof value.receiptLineId === "string" || value.receiptLineId === null) &&
    (typeof value.receiptId === "string" || value.receiptId === null) &&
    typeof value.warehouseId === "string" &&
    typeof value.warehouseCode === "string" &&
    typeof value.productId === "string" &&
    typeof value.productSku === "string" &&
    (value.type === "OPENING" ||
      value.type === "ADJUSTMENT_IN" ||
      value.type === "ADJUSTMENT_OUT" ||
      value.type === "TRANSFER_IN" ||
      value.type === "TRANSFER_OUT" ||
      value.type === "RECEIPT_IN") &&
    typeof value.enteredQuantity === "string" &&
    typeof value.enteredUnitId === "string" &&
    typeof value.enteredUnitCode === "string" &&
    typeof value.conversionFactor === "string" &&
    typeof value.baseQuantity === "string" &&
    (typeof value.reason === "string" || value.reason === null) &&
    typeof value.postedBy === "string" &&
    typeof value.postedByName === "string" &&
    typeof value.postedAt === "string"
  );
}

function isTransfer(value: unknown): value is Transfer {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    typeof value.requestId === "string" &&
    typeof value.sourceWarehouseId === "string" &&
    typeof value.sourceWarehouseCode === "string" &&
    typeof value.destinationWarehouseId === "string" &&
    typeof value.destinationWarehouseCode === "string" &&
    typeof value.productId === "string" &&
    typeof value.productSku === "string" &&
    typeof value.enteredQuantity === "string" &&
    typeof value.enteredUnitId === "string" &&
    typeof value.enteredUnitCode === "string" &&
    typeof value.conversionFactor === "string" &&
    typeof value.baseQuantity === "string" &&
    typeof value.reason === "string" &&
    typeof value.postedBy === "string" &&
    typeof value.postedByName === "string" &&
    typeof value.postedAt === "string"
  );
}

function isMovementPage(value: unknown): value is MovementPage {
  return (
    isRecord(value) &&
    Array.isArray(value.items) &&
    value.items.every(isMovement) &&
    typeof value.page === "number" &&
    typeof value.size === "number" &&
    typeof value.hasMore === "boolean"
  );
}

function isPositiveDecimal(value: string) {
  const decimal = value.trim();
  return /^(?:\d+(?:\.\d*)?|\.\d+)$/.test(decimal) && /[1-9]/.test(decimal);
}

export default function InventoryManagement({
  isAdmin,
  onSessionExpired,
  revision = 0,
}: Props) {
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [products, setProducts] = useState<Product[]>([]);
  const [balances, setBalances] = useState<Balance[]>([]);
  const [movements, setMovements] = useState<MovementPage>();
  const [selectedProductId, setSelectedProductId] = useState("");
  const [conversions, setConversions] = useState<ProductConversion[]>([]);
  const [conversionsLoading, setConversionsLoading] = useState(false);
  const [transferSourceWarehouseId, setTransferSourceWarehouseId] =
    useState("");
  const [transferProductId, setTransferProductId] = useState("");
  const [transferConversions, setTransferConversions] = useState<
    ProductConversion[]
  >([]);
  const [transferConversionsLoading, setTransferConversionsLoading] =
    useState(false);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string>();
  const [posting, setPosting] = useState(false);
  const [postMessage, setPostMessage] = useState<string>();
  const [transferring, setTransferring] = useState(false);
  const [transferMessage, setTransferMessage] = useState<string>();
  const pendingRequest = useRef<
    { signature: string; requestId: string } | undefined
  >(undefined);
  const pendingTransferRequest = useRef<
    { signature: string; requestId: string } | undefined
  >(undefined);

  const loadInventory = useCallback(async () => {
    setLoading(true);
    setLoadError(undefined);
    try {
      const responses = await Promise.all([
        fetch("/api/v1/warehouses", {
          headers: { Accept: "application/json" },
        }),
        fetch("/api/v1/products", { headers: { Accept: "application/json" } }),
        fetch("/api/v1/inventory/balances", {
          headers: { Accept: "application/json" },
        }),
        fetch("/api/v1/inventory/movements?page=0&size=50", {
          headers: { Accept: "application/json" },
        }),
      ]);
      if (responses.some((response) => response.status === 401)) {
        onSessionExpired();
        return;
      }
      const [warehouseBody, productBody, balanceBody, movementBody] =
        await Promise.all(responses.map(readJson));
      if (
        responses.some((response) => !response.ok) ||
        !Array.isArray(warehouseBody) ||
        !warehouseBody.every(isWarehouse) ||
        !Array.isArray(productBody) ||
        !productBody.every(isProduct) ||
        !Array.isArray(balanceBody) ||
        !balanceBody.every(isBalance) ||
        !isMovementPage(movementBody)
      ) {
        setLoadError("Unable to load inventory.");
        return;
      }
      setWarehouses(warehouseBody);
      setProducts(productBody);
      setBalances(balanceBody);
      setMovements(movementBody);
    } catch {
      setLoadError("Inventory service unavailable.");
    } finally {
      setLoading(false);
    }
  }, [onSessionExpired, revision]);

  useEffect(() => {
    void loadInventory();
  }, [loadInventory]);

  useEffect(() => {
    if (!selectedProductId) {
      setConversions([]);
      return;
    }
    const controller = new AbortController();
    setConversionsLoading(true);
    void (async () => {
      try {
        const response = await fetch(
          `/api/v1/products/${selectedProductId}/conversions`,
          {
            headers: { Accept: "application/json" },
            signal: controller.signal,
          },
        );
        if (response.status === 401) {
          onSessionExpired();
          return;
        }
        const body = await readJson(response);
        if (response.ok && Array.isArray(body) && body.every(isConversion)) {
          setConversions(body);
        } else {
          setPostMessage("Unable to load product units.");
        }
      } catch (error) {
        if (!(error instanceof DOMException && error.name === "AbortError"))
          setPostMessage("Product unit service unavailable.");
      } finally {
        setConversionsLoading(false);
      }
    })();
    return () => controller.abort();
  }, [onSessionExpired, selectedProductId]);

  useEffect(() => {
    if (!transferProductId) {
      setTransferConversions([]);
      return;
    }
    const controller = new AbortController();
    setTransferConversionsLoading(true);
    void (async () => {
      try {
        const response = await fetch(
          `/api/v1/products/${transferProductId}/conversions`,
          {
            headers: { Accept: "application/json" },
            signal: controller.signal,
          },
        );
        if (response.status === 401) {
          onSessionExpired();
          return;
        }
        const body = await readJson(response);
        if (response.ok && Array.isArray(body) && body.every(isConversion)) {
          setTransferConversions(body);
        } else {
          setTransferMessage("Unable to load transfer product units.");
        }
      } catch (error) {
        if (!(error instanceof DOMException && error.name === "AbortError"))
          setTransferMessage("Product unit service unavailable.");
      } finally {
        setTransferConversionsLoading(false);
      }
    })();
    return () => controller.abort();
  }, [onSessionExpired, transferProductId]);

  async function postMovement(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const type = String(data.get("movementType"));
    const quantity = String(data.get("quantity") ?? "").trim();
    const reason = String(data.get("reason") ?? "").trim();
    if (!isPositiveDecimal(quantity)) {
      setPostMessage("Quantity must be greater than zero.");
      return;
    }
    if (type !== "OPENING" && !reason) {
      setPostMessage("A reason is required for adjustments.");
      return;
    }
    const payload = {
      warehouseId: String(data.get("warehouseId")),
      productId: String(data.get("productId")),
      type,
      quantity,
      unitId: String(data.get("unitId")),
      reason: reason || null,
    };
    const signature = JSON.stringify(payload);
    const requestId =
      pendingRequest.current?.signature === signature
        ? pendingRequest.current.requestId
        : crypto.randomUUID();
    pendingRequest.current = { signature, requestId };
    setPosting(true);
    setPostMessage(undefined);
    try {
      const csrf = await fetchCsrf();
      const response = await fetch("/api/v1/inventory/movements", {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          [csrf.headerName]: csrf.token,
        },
        body: JSON.stringify({ requestId, ...payload }),
      });
      const body = await readJson(response);
      if (response.status === 401) {
        onSessionExpired();
      } else if (response.ok && isMovement(body)) {
        pendingRequest.current = undefined;
        form.reset();
        setSelectedProductId("");
        setPostMessage("Movement posted.");
        await loadInventory();
      } else {
        const error = isRecord(body) ? (body as ApiError) : undefined;
        setPostMessage(error?.message ?? "Unable to post movement.");
      }
    } catch (error) {
      if (error instanceof CsrfRequestError && error.status === 401)
        onSessionExpired();
      else setPostMessage("Inventory service unavailable.");
    } finally {
      setPosting(false);
    }
  }

  async function postTransfer(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const sourceWarehouseId = String(data.get("transferSourceWarehouseId"));
    const destinationWarehouseId = String(
      data.get("transferDestinationWarehouseId"),
    );
    const quantity = String(data.get("transferQuantity") ?? "").trim();
    const reason = String(data.get("transferReason") ?? "").trim();
    if (sourceWarehouseId === destinationWarehouseId) {
      setTransferMessage(
        "Source and destination warehouses must be different.",
      );
      return;
    }
    if (!isPositiveDecimal(quantity)) {
      setTransferMessage("Transfer quantity must be greater than zero.");
      return;
    }
    if (!reason) {
      setTransferMessage("A reason is required for transfers.");
      return;
    }
    const payload = {
      sourceWarehouseId,
      destinationWarehouseId,
      productId: String(data.get("transferProductId")),
      quantity,
      unitId: String(data.get("transferUnitId")),
      reason,
    };
    const signature = JSON.stringify(payload);
    const requestId =
      pendingTransferRequest.current?.signature === signature
        ? pendingTransferRequest.current.requestId
        : crypto.randomUUID();
    pendingTransferRequest.current = { signature, requestId };
    setTransferring(true);
    setTransferMessage(undefined);
    try {
      const csrf = await fetchCsrf();
      const response = await fetch("/api/v1/inventory/transfers", {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          [csrf.headerName]: csrf.token,
        },
        body: JSON.stringify({ requestId, ...payload }),
      });
      const body = await readJson(response);
      if (response.status === 401) {
        onSessionExpired();
      } else if (response.ok && isTransfer(body)) {
        pendingTransferRequest.current = undefined;
        form.reset();
        setTransferSourceWarehouseId("");
        setTransferProductId("");
        setTransferMessage("Transfer posted.");
        await loadInventory();
      } else {
        const error = isRecord(body) ? (body as ApiError) : undefined;
        setTransferMessage(error?.message ?? "Unable to post transfer.");
      }
    } catch (error) {
      if (error instanceof CsrfRequestError && error.status === 401)
        onSessionExpired();
      else setTransferMessage("Inventory service unavailable.");
    } finally {
      setTransferring(false);
    }
  }

  const selectedProduct = products.find(
    (product) => product.id === selectedProductId,
  );
  const unitOptions = selectedProduct
    ? [
        {
          id: selectedProduct.baseUnitId,
          code: selectedProduct.baseUnitCode,
          factor: "1",
        },
        ...conversions.map((conversion) => ({
          id: conversion.alternateUnitId,
          code: conversion.alternateUnitCode,
          factor: conversion.conversionFactor,
        })),
      ]
    : [];
  const selectedTransferProduct = products.find(
    (product) => product.id === transferProductId,
  );
  const transferUnitOptions = selectedTransferProduct
    ? [
        {
          id: selectedTransferProduct.baseUnitId,
          code: selectedTransferProduct.baseUnitCode,
          factor: "1",
        },
        ...transferConversions.map((conversion) => ({
          id: conversion.alternateUnitId,
          code: conversion.alternateUnitCode,
          factor: conversion.conversionFactor,
        })),
      ]
    : [];

  return (
    <section className="management-panel" aria-labelledby="inventory-heading">
      <h2 id="inventory-heading">Inventory</h2>
      {isAdmin && (
        <form className="management-form" onSubmit={postMovement}>
          <h3>Post opening balance or adjustment</h3>
          <label>
            Warehouse
            <select name="warehouseId" required defaultValue="">
              <option value="" disabled>
                Select a warehouse
              </option>
              {warehouses
                .filter((warehouse) => warehouse.active)
                .map((warehouse) => (
                  <option key={warehouse.id} value={warehouse.id}>
                    {warehouse.code} — {warehouse.name}
                  </option>
                ))}
            </select>
          </label>
          <label>
            Product
            <select
              name="productId"
              required
              value={selectedProductId}
              onChange={(event) => setSelectedProductId(event.target.value)}
            >
              <option value="" disabled>
                Select a product
              </option>
              {products
                .filter((product) => product.active)
                .map((product) => (
                  <option key={product.id} value={product.id}>
                    {product.sku} — {product.name}
                  </option>
                ))}
            </select>
          </label>
          <label>
            Movement type
            <select name="movementType" required defaultValue="OPENING">
              <option value="OPENING">Opening balance</option>
              <option value="ADJUSTMENT_IN">Adjustment in</option>
              <option value="ADJUSTMENT_OUT">Adjustment out</option>
            </select>
          </label>
          <label>
            Unit
            <select
              name="unitId"
              required
              defaultValue=""
              disabled={!selectedProduct || conversionsLoading}
            >
              <option value="" disabled>
                {conversionsLoading ? "Loading units…" : "Select a unit"}
              </option>
              {unitOptions.map((unit) => (
                <option key={unit.id} value={unit.id}>
                  {unit.code} (factor {unit.factor})
                </option>
              ))}
            </select>
          </label>
          <label>
            Quantity
            <input name="quantity" type="text" inputMode="decimal" required />
          </label>
          <label>
            Reason for adjustment
            <input name="reason" maxLength={500} />
          </label>
          <button type="submit" disabled={posting || conversionsLoading}>
            Post movement
          </button>
        </form>
      )}
      {isAdmin && (
        <form className="management-form" onSubmit={postTransfer}>
          <h3>Transfer stock</h3>
          <label>
            Transfer source warehouse
            <select
              name="transferSourceWarehouseId"
              required
              value={transferSourceWarehouseId}
              onChange={(event) =>
                setTransferSourceWarehouseId(event.target.value)
              }
            >
              <option value="" disabled>
                Select a source warehouse
              </option>
              {warehouses
                .filter((warehouse) => warehouse.active)
                .map((warehouse) => (
                  <option key={warehouse.id} value={warehouse.id}>
                    {warehouse.code} — {warehouse.name}
                  </option>
                ))}
            </select>
          </label>
          <label>
            Transfer destination warehouse
            <select
              name="transferDestinationWarehouseId"
              required
              defaultValue=""
            >
              <option value="" disabled>
                Select a destination warehouse
              </option>
              {warehouses
                .filter(
                  (warehouse) =>
                    warehouse.active &&
                    warehouse.id !== transferSourceWarehouseId,
                )
                .map((warehouse) => (
                  <option key={warehouse.id} value={warehouse.id}>
                    {warehouse.code} — {warehouse.name}
                  </option>
                ))}
            </select>
          </label>
          <label>
            Transfer product
            <select
              name="transferProductId"
              required
              value={transferProductId}
              onChange={(event) => setTransferProductId(event.target.value)}
            >
              <option value="" disabled>
                Select a product
              </option>
              {products
                .filter((product) => product.active)
                .map((product) => (
                  <option key={product.id} value={product.id}>
                    {product.sku} — {product.name}
                  </option>
                ))}
            </select>
          </label>
          <label>
            Transfer unit
            <select
              name="transferUnitId"
              required
              defaultValue=""
              disabled={!selectedTransferProduct || transferConversionsLoading}
            >
              <option value="" disabled>
                {transferConversionsLoading
                  ? "Loading transfer units…"
                  : "Select a unit"}
              </option>
              {transferUnitOptions.map((unit) => (
                <option key={unit.id} value={unit.id}>
                  {unit.code} (factor {unit.factor})
                </option>
              ))}
            </select>
          </label>
          <label>
            Transfer quantity
            <input
              name="transferQuantity"
              type="text"
              inputMode="decimal"
              required
            />
          </label>
          <label>
            Transfer reason
            <input name="transferReason" maxLength={500} required />
          </label>
          <button
            type="submit"
            disabled={transferring || transferConversionsLoading}
          >
            Transfer stock
          </button>
        </form>
      )}
      {postMessage && (
        <p className="form-message" role="status">
          {postMessage}
        </p>
      )}
      {transferMessage && (
        <p className="form-message" role="status">
          {transferMessage}
        </p>
      )}
      {loading && <p>Loading inventory…</p>}
      {loadError && (
        <div>
          <p role="alert">{loadError}</p>
          <button type="button" onClick={() => void loadInventory()}>
            Retry inventory
          </button>
        </div>
      )}
      {!loading && !loadError && (
        <>
          <h3>Warehouse stock balances</h3>
          {balances.length === 0 ? (
            <p>No stock balances are available.</p>
          ) : (
            <div className="management-list">
              {balances.map((balance) => (
                <article
                  className="management-item"
                  key={`${balance.warehouseId}:${balance.productId}`}
                >
                  <h4>
                    {balance.productSku} at {balance.warehouseCode}
                  </h4>
                  <p>
                    {balance.baseQuantity} {balance.baseUnitCode}
                  </p>
                </article>
              ))}
            </div>
          )}
          <h3>Movement history</h3>
          {movements?.items.length === 0 ? (
            <p>No inventory movements are available.</p>
          ) : (
            <div className="management-list">
              {movements?.items.map((movement) => (
                <article className="management-item" key={movement.id}>
                  <h4>{movement.type.replaceAll("_", " ")}</h4>
                  <p>
                    {movement.productSku} at {movement.warehouseCode}
                  </p>
                  {movement.transferId && (
                    <p>Linked transfer: {movement.transferId}</p>
                  )}
                  {movement.receiptLineId && (
                    <p>Linked receipt: {movement.receiptId}</p>
                  )}
                  <p>
                    {movement.enteredQuantity} {movement.enteredUnitCode} ={" "}
                    {movement.baseQuantity} base units
                  </p>
                  {movement.reason && <p>{movement.reason}</p>}
                </article>
              ))}
            </div>
          )}
        </>
      )}
    </section>
  );
}
