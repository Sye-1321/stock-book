import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { ApiError, isRecord, readJson } from "../../app/api";
import { CsrfRequestError, fetchCsrf } from "../auth/csrf";
import {
  isConversion,
  isProduct,
  Product,
  ProductConversion,
} from "../catalog/types";
import { isSupplier, Supplier } from "../suppliers/SupplierManagement";

type Warehouse = { id: string; code: string; name: string; active: boolean };
type DraftLine = {
  key: number;
  productId: string;
  quantity: string;
  unitId: string;
};
type ReceiptResponse = { id: string };

type Props = {
  suppliersRevision: number;
  onSessionExpired: (message?: string) => void;
  onReceiptPosted: () => void;
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

function isReceipt(value: unknown): value is ReceiptResponse {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    typeof value.requestId === "string" &&
    typeof value.supplierId === "string" &&
    typeof value.supplierCode === "string" &&
    typeof value.warehouseId === "string" &&
    typeof value.warehouseCode === "string" &&
    (typeof value.supplierDocumentReference === "string" ||
      value.supplierDocumentReference === null) &&
    Array.isArray(value.lines) &&
    value.lines.length > 0 &&
    value.lines.every(
      (line) =>
        isRecord(line) &&
        typeof line.id === "string" &&
        typeof line.productId === "string" &&
        typeof line.enteredQuantity === "string" &&
        typeof line.enteredUnitId === "string" &&
        typeof line.conversionFactor === "string" &&
        typeof line.baseQuantity === "string",
    )
  );
}

function positiveDecimal(value: string) {
  const decimal = value.trim();
  return /^(?:\d+(?:\.\d*)?|\.\d+)$/.test(decimal) && /[1-9]/.test(decimal);
}

export default function GoodsReceiving({
  suppliersRevision,
  onSessionExpired,
  onReceiptPosted,
}: Props) {
  const nextLineKey = useRef(2);
  const pendingRequest = useRef<
    { signature: string; requestId: string } | undefined
  >(undefined);
  const [suppliers, setSuppliers] = useState<Supplier[]>([]);
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [products, setProducts] = useState<Product[]>([]);
  const [lines, setLines] = useState<DraftLine[]>([
    { key: 1, productId: "", quantity: "", unitId: "" },
  ]);
  const [conversions, setConversions] = useState<
    Record<string, ProductConversion[]>
  >({});
  const [loadingConversions, setLoadingConversions] = useState<
    Record<string, boolean>
  >({});
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string>();

  const handleFailure = useCallback(
    (error: unknown) => {
      if (error instanceof CsrfRequestError && error.status === 401)
        onSessionExpired();
      else setMessage("Receiving service unavailable.");
    },
    [onSessionExpired],
  );

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    void (async () => {
      try {
        const responses = await Promise.all([
          fetch("/api/v1/suppliers", {
            headers: { Accept: "application/json" },
            signal: controller.signal,
          }),
          fetch("/api/v1/warehouses", {
            headers: { Accept: "application/json" },
            signal: controller.signal,
          }),
          fetch("/api/v1/products", {
            headers: { Accept: "application/json" },
            signal: controller.signal,
          }),
        ]);
        if (responses.some((response) => response.status === 401)) {
          onSessionExpired();
          return;
        }
        const [supplierBody, warehouseBody, productBody] = await Promise.all(
          responses.map(readJson),
        );
        if (
          responses.every((response) => response.ok) &&
          Array.isArray(supplierBody) &&
          supplierBody.every(isSupplier) &&
          Array.isArray(warehouseBody) &&
          warehouseBody.every(isWarehouse) &&
          Array.isArray(productBody) &&
          productBody.every(isProduct)
        ) {
          setSuppliers(supplierBody);
          setWarehouses(warehouseBody);
          setProducts(productBody);
        } else setMessage("Unable to load receiving references.");
      } catch (error) {
        if (!(error instanceof DOMException && error.name === "AbortError"))
          setMessage("Receiving service unavailable.");
      } finally {
        setLoading(false);
      }
    })();
    return () => controller.abort();
  }, [onSessionExpired, suppliersRevision]);

  async function selectProduct(key: number, productId: string) {
    setLines((current) =>
      current.map((line) =>
        line.key === key ? { ...line, productId, unitId: "" } : line,
      ),
    );
    if (
      !productId ||
      conversions[productId] !== undefined ||
      loadingConversions[productId]
    )
      return;
    setLoadingConversions((current) => ({ ...current, [productId]: true }));
    try {
      const response = await fetch(
        `/api/v1/products/${productId}/conversions`,
        { headers: { Accept: "application/json" } },
      );
      if (response.status === 401) {
        onSessionExpired();
        return;
      }
      const body = await readJson(response);
      if (response.ok && Array.isArray(body) && body.every(isConversion))
        setConversions((current) => ({ ...current, [productId]: body }));
      else setMessage("Unable to load product units.");
    } catch {
      setMessage("Product unit service unavailable.");
    } finally {
      setLoadingConversions((current) => ({ ...current, [productId]: false }));
    }
  }

  function updateLine(key: number, changes: Partial<DraftLine>) {
    setLines((current) =>
      current.map((line) =>
        line.key === key ? { ...line, ...changes } : line,
      ),
    );
  }

  async function postReceipt(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    if (
      lines.some(
        (line) =>
          !line.productId || !line.unitId || !positiveDecimal(line.quantity),
      )
    ) {
      setMessage(
        "Every receipt line requires a product, unit and positive quantity.",
      );
      return;
    }
    if (new Set(lines.map((line) => line.productId)).size !== lines.length) {
      setMessage("A product may appear only once in a receipt.");
      return;
    }
    const payload = {
      supplierId: String(data.get("receiptSupplierId")),
      warehouseId: String(data.get("receiptWarehouseId")),
      supplierDocumentReference:
        String(data.get("supplierDocumentReference") ?? "").trim() || null,
      lines: lines.map((line) => ({
        productId: line.productId,
        quantity: line.quantity.trim(),
        unitId: line.unitId,
      })),
    };
    const signature = JSON.stringify(payload);
    const requestId =
      pendingRequest.current?.signature === signature
        ? pendingRequest.current.requestId
        : crypto.randomUUID();
    pendingRequest.current = { signature, requestId };
    setBusy(true);
    setMessage(undefined);
    try {
      const csrf = await fetchCsrf();
      const response = await fetch("/api/v1/inventory/receipts", {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          [csrf.headerName]: csrf.token,
        },
        body: JSON.stringify({ requestId, ...payload }),
      });
      const body = await readJson(response);
      if (response.status === 401) onSessionExpired();
      else if (response.ok && isReceipt(body)) {
        pendingRequest.current = undefined;
        form.reset();
        setLines([
          {
            key: nextLineKey.current++,
            productId: "",
            quantity: "",
            unitId: "",
          },
        ]);
        setMessage(`Receipt posted: ${body.id}`);
        onReceiptPosted();
      } else {
        const error = isRecord(body) ? (body as ApiError) : undefined;
        setMessage(error?.message ?? "Unable to post receipt.");
      }
    } catch (error) {
      handleFailure(error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="management-panel" aria-labelledby="receiving-heading">
      <h2 id="receiving-heading">Goods receiving</h2>
      {loading ? (
        <p>Loading receiving references…</p>
      ) : (
        <form className="management-form" onSubmit={postReceipt}>
          <label>
            Receipt supplier
            <select name="receiptSupplierId" required defaultValue="">
              <option value="" disabled>
                Select a supplier
              </option>
              {suppliers
                .filter(
                  (supplier) =>
                    supplier.active && supplier.partyActive !== false,
                )
                .map((supplier) => (
                  <option key={supplier.id} value={supplier.id}>
                    {supplier.code} — {supplier.name}
                  </option>
                ))}
            </select>
          </label>
          <label>
            Receipt warehouse
            <select name="receiptWarehouseId" required defaultValue="">
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
            Supplier document reference
            <input name="supplierDocumentReference" maxLength={100} />
          </label>
          {lines.map((line, index) => {
            const product = products.find((item) => item.id === line.productId);
            const unitOptions = product
              ? [
                  {
                    id: product.baseUnitId,
                    code: product.baseUnitCode,
                    factor: "1",
                  },
                  ...(conversions[product.id] ?? []).map((conversion) => ({
                    id: conversion.alternateUnitId,
                    code: conversion.alternateUnitCode,
                    factor: conversion.conversionFactor,
                  })),
                ]
              : [];
            return (
              <fieldset key={line.key}>
                <legend>Receipt line {index + 1}</legend>
                <label>
                  Product for line {index + 1}
                  <select
                    required
                    value={line.productId}
                    onChange={(event) =>
                      void selectProduct(line.key, event.target.value)
                    }
                  >
                    <option value="" disabled>
                      Select a product
                    </option>
                    {products
                      .filter((item) => item.active)
                      .map((item) => (
                        <option key={item.id} value={item.id}>
                          {item.sku} — {item.name}
                        </option>
                      ))}
                  </select>
                </label>
                <label>
                  Quantity for line {index + 1}
                  <input
                    type="text"
                    inputMode="decimal"
                    required
                    value={line.quantity}
                    onChange={(event) =>
                      updateLine(line.key, { quantity: event.target.value })
                    }
                  />
                </label>
                <label>
                  Unit for line {index + 1}
                  <select
                    required
                    value={line.unitId}
                    disabled={!product || loadingConversions[line.productId]}
                    onChange={(event) =>
                      updateLine(line.key, { unitId: event.target.value })
                    }
                  >
                    <option value="" disabled>
                      {loadingConversions[line.productId]
                        ? "Loading units…"
                        : "Select a unit"}
                    </option>
                    {unitOptions.map((unit) => (
                      <option key={unit.id} value={unit.id}>
                        {unit.code} (factor {unit.factor})
                      </option>
                    ))}
                  </select>
                </label>
                {lines.length > 1 && (
                  <button
                    type="button"
                    onClick={() =>
                      setLines((current) =>
                        current.filter((item) => item.key !== line.key),
                      )
                    }
                  >
                    Remove line {index + 1}
                  </button>
                )}
              </fieldset>
            );
          })}
          <button
            type="button"
            onClick={() =>
              setLines((current) => [
                ...current,
                {
                  key: nextLineKey.current++,
                  productId: "",
                  quantity: "",
                  unitId: "",
                },
              ])
            }
          >
            Add receipt line
          </button>
          <button
            type="submit"
            disabled={busy || Object.values(loadingConversions).some(Boolean)}
          >
            Post receipt
          </button>
        </form>
      )}
      {message && (
        <p className="form-message" role="status">
          {message}
        </p>
      )}
    </section>
  );
}
