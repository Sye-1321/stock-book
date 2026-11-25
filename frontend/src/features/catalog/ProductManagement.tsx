import { FormEvent, useCallback, useEffect, useState } from "react";
import { ApiError, isRecord, readJson } from "../../app/api";
import { CsrfRequestError, fetchCsrf } from "../auth/csrf";
import {
  isConversion,
  isProduct,
  isUnit,
  Product,
  ProductConversion,
  UnitOfMeasure,
} from "./types";

type ProductDraft = Pick<Product, "sku" | "name" | "active">;

type Props = {
  isAdmin: boolean;
  unitsRevision: number;
  onSessionExpired: (message?: string) => void;
};

function productModified(product: Product, draft: ProductDraft | undefined) {
  return (
    draft !== undefined &&
    (draft.sku !== product.sku ||
      draft.name !== product.name ||
      draft.active !== product.active)
  );
}

function conversionKey(productId: string, unitId: string) {
  return `${productId}:${unitId}`;
}

export default function ProductManagement({
  isAdmin,
  unitsRevision,
  onSessionExpired,
}: Props) {
  const [products, setProducts] = useState<Product[]>([]);
  const [units, setUnits] = useState<UnitOfMeasure[]>([]);
  const [conversions, setConversions] = useState<
    Record<string, ProductConversion[]>
  >({});
  const [productDrafts, setProductDrafts] = useState<
    Record<string, ProductDraft>
  >({});
  const [conversionDrafts, setConversionDrafts] = useState<
    Record<string, string>
  >({});
  const [loading, setLoading] = useState(true);
  const [loadSucceeded, setLoadSucceeded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string>();

  const handleFailure = useCallback(
    (error: unknown) => {
      if (error instanceof CsrfRequestError && error.status === 401)
        onSessionExpired();
      else setMessage("Product catalog service unavailable.");
    },
    [onSessionExpired],
  );

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setLoadSucceeded(false);
    void (async () => {
      try {
        const [productResponse, unitResponse] = await Promise.all([
          fetch("/api/v1/products", {
            headers: { Accept: "application/json" },
            signal: controller.signal,
          }),
          fetch("/api/v1/units", {
            headers: { Accept: "application/json" },
            signal: controller.signal,
          }),
        ]);
        if (productResponse.status === 401 || unitResponse.status === 401) {
          onSessionExpired();
          return;
        }
        const [productBody, unitBody] = await Promise.all([
          readJson(productResponse),
          readJson(unitResponse),
        ]);
        if (
          !productResponse.ok ||
          !unitResponse.ok ||
          !Array.isArray(productBody) ||
          !productBody.every(isProduct) ||
          !Array.isArray(unitBody) ||
          !unitBody.every(isUnit)
        ) {
          setMessage("Unable to load products.");
          return;
        }
        const conversionResponses = await Promise.all(
          productBody.map((product) =>
            fetch(`/api/v1/products/${product.id}/conversions`, {
              headers: { Accept: "application/json" },
              signal: controller.signal,
            }),
          ),
        );
        if (conversionResponses.some((response) => response.status === 401)) {
          onSessionExpired();
          return;
        }
        const conversionBodies = await Promise.all(
          conversionResponses.map(readJson),
        );
        if (
          conversionResponses.some((response) => !response.ok) ||
          conversionBodies.some(
            (body) => !Array.isArray(body) || !body.every(isConversion),
          )
        ) {
          setMessage("Unable to load product conversions.");
          return;
        }
        const conversionLists = conversionBodies as ProductConversion[][];
        setProducts(productBody);
        setUnits(unitBody);
        setConversions(
          Object.fromEntries(
            productBody.map((product, index) => [
              product.id,
              conversionLists[index],
            ]),
          ),
        );
        setLoadSucceeded(true);
      } catch (error) {
        if (error instanceof DOMException && error.name === "AbortError")
          return;
        setMessage("Product catalog service unavailable.");
      } finally {
        setLoading(false);
      }
    })();
    return () => controller.abort();
  }, [onSessionExpired, unitsRevision]);

  async function createProduct(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setBusy(true);
    setMessage(undefined);
    try {
      const csrf = await fetchCsrf();
      const response = await fetch("/api/v1/products", {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          [csrf.headerName]: csrf.token,
        },
        body: JSON.stringify({
          sku: data.get("productSku"),
          name: data.get("productName"),
          baseUnitId: data.get("productBaseUnitId"),
        }),
      });
      const body = await readJson(response);
      if (response.status === 401) onSessionExpired();
      else if (response.ok && isProduct(body)) {
        setProducts((current) =>
          [...current, body].sort((a, b) => a.sku.localeCompare(b.sku)),
        );
        setConversions((current) => ({ ...current, [body.id]: [] }));
        form.reset();
        setMessage("Product created.");
      } else {
        const error = isRecord(body) ? (body as ApiError) : undefined;
        setMessage(error?.message ?? "Unable to create product.");
      }
    } catch (error) {
      handleFailure(error);
    } finally {
      setBusy(false);
    }
  }

  function editProduct(product: Product, changes: Partial<ProductDraft>) {
    setProductDrafts((current) => {
      const draft = {
        ...(current[product.id] ?? {
          sku: product.sku,
          name: product.name,
          active: product.active,
        }),
        ...changes,
      };
      const next = { ...current };
      if (productModified(product, draft)) next[product.id] = draft;
      else delete next[product.id];
      return next;
    });
  }

  async function saveProduct(product: Product, draft: ProductDraft) {
    await submit(
      `/api/v1/products/${product.id}`,
      draft,
      (body) => {
        if (!isProduct(body)) return false;
        setProducts((current) =>
          current.map((item) => (item.id === body.id ? body : item)),
        );
        setProductDrafts((current) => {
          const next = { ...current };
          delete next[body.id];
          return next;
        });
        setMessage("Product updated.");
        return true;
      },
      "Unable to update product.",
    );
  }

  async function createConversion(
    event: FormEvent<HTMLFormElement>,
    product: Product,
  ) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    await submit(
      `/api/v1/products/${product.id}/conversions`,
      {
        alternateUnitId: data.get("alternateUnitId"),
        conversionFactor: data.get("conversionFactor"),
      },
      (body) => {
        if (!isConversion(body)) return false;
        setConversions((current) => ({
          ...current,
          [product.id]: [...(current[product.id] ?? []), body].sort((a, b) =>
            a.alternateUnitCode.localeCompare(b.alternateUnitCode),
          ),
        }));
        form.reset();
        setMessage("Conversion created.");
        return true;
      },
      "Unable to create conversion.",
      "POST",
    );
  }

  async function saveConversion(
    product: Product,
    conversion: ProductConversion,
    factor: string,
  ) {
    const key = conversionKey(product.id, conversion.alternateUnitId);
    await submit(
      `/api/v1/products/${product.id}/conversions/${conversion.alternateUnitId}`,
      { conversionFactor: factor },
      (body) => {
        if (!isConversion(body)) return false;
        setConversions((current) => ({
          ...current,
          [product.id]: (current[product.id] ?? []).map((item) =>
            item.alternateUnitId === body.alternateUnitId ? body : item,
          ),
        }));
        setConversionDrafts((current) => {
          const next = { ...current };
          delete next[key];
          return next;
        });
        setMessage("Conversion updated.");
        return true;
      },
      "Unable to update conversion.",
    );
  }

  async function submit(
    url: string,
    payload: object,
    accept: (body: unknown) => boolean,
    fallback: string,
    method = "PUT",
  ) {
    setBusy(true);
    setMessage(undefined);
    try {
      const csrf = await fetchCsrf();
      const response = await fetch(url, {
        method,
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          [csrf.headerName]: csrf.token,
        },
        body: JSON.stringify(payload),
      });
      const body = await readJson(response);
      if (response.status === 401) onSessionExpired();
      else if (!response.ok || !accept(body)) {
        const error = isRecord(body) ? (body as ApiError) : undefined;
        setMessage(error?.message ?? fallback);
      }
    } catch (error) {
      handleFailure(error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="management-panel" aria-labelledby="products-heading">
      <h2 id="products-heading">Products</h2>
      {isAdmin && (
        <form className="management-form" onSubmit={createProduct}>
          <h3>Create product</h3>
          <label>
            Product SKU
            <input name="productSku" required maxLength={100} />
          </label>
          <label>
            Product name
            <input name="productName" required maxLength={200} />
          </label>
          <label>
            Base unit
            <select name="productBaseUnitId" required defaultValue="">
              <option value="" disabled>
                Select a unit
              </option>
              {units.map((unit) => (
                <option key={unit.id} value={unit.id}>
                  {unit.code} — {unit.name}
                </option>
              ))}
            </select>
          </label>
          <button type="submit" disabled={busy || units.length === 0}>
            Create product
          </button>
        </form>
      )}
      <div className="management-list" aria-live="polite">
        {loading && <p>Loading products…</p>}
        {!loading && loadSucceeded && products.length === 0 && (
          <p>No products are available.</p>
        )}
        {products.map((product) => {
          const draft = productDrafts[product.id] ?? product;
          const modified = productModified(product, productDrafts[product.id]);
          const productConversions = conversions[product.id] ?? [];
          const availableUnits = units.filter(
            (unit) =>
              unit.id !== product.baseUnitId &&
              !productConversions.some(
                (item) => item.alternateUnitId === unit.id,
              ),
          );
          return (
            <article className="management-item" key={product.id}>
              {isAdmin ? (
                <>
                  <label>
                    SKU for {product.sku}
                    <input
                      value={draft.sku}
                      maxLength={100}
                      onChange={(event) =>
                        editProduct(product, { sku: event.target.value })
                      }
                    />
                  </label>
                  <label>
                    Name for {product.sku}
                    <input
                      value={draft.name}
                      maxLength={200}
                      onChange={(event) =>
                        editProduct(product, { name: event.target.value })
                      }
                    />
                  </label>
                  <p>
                    Base unit: {product.baseUnitCode} — {product.baseUnitName}
                  </p>
                  <label className="checkbox-label">
                    <input
                      type="checkbox"
                      checked={draft.active}
                      onChange={(event) =>
                        editProduct(product, { active: event.target.checked })
                      }
                    />
                    Active
                  </label>
                  {modified && <p>Unsaved changes</p>}
                  <button
                    type="button"
                    disabled={busy || !modified}
                    onClick={() => saveProduct(product, draft)}
                  >
                    Save {product.sku}
                  </button>
                </>
              ) : (
                <>
                  <h3>{product.name}</h3>
                  <p>{product.sku}</p>
                  <p>Base unit: {product.baseUnitCode}</p>
                  <p>{product.active ? "Active" : "Inactive"}</p>
                </>
              )}
              <div className="conversion-list">
                <h3>Conversions for {product.sku}</h3>
                {productConversions.length === 0 && (
                  <p>No alternate-unit conversions.</p>
                )}
                {productConversions.map((conversion) => {
                  const key = conversionKey(
                    product.id,
                    conversion.alternateUnitId,
                  );
                  const factor =
                    conversionDrafts[key] ?? conversion.conversionFactor;
                  const conversionModified =
                    factor !== conversion.conversionFactor;
                  return isAdmin ? (
                    <div
                      className="conversion-item"
                      key={conversion.alternateUnitId}
                    >
                      <label>
                        {conversion.alternateUnitCode} factor
                        <input
                          type="number"
                          min="0"
                          step="any"
                          value={factor}
                          onChange={(event) =>
                            setConversionDrafts((current) => {
                              const next = { ...current };
                              if (
                                event.target.value ===
                                conversion.conversionFactor
                              )
                                delete next[key];
                              else next[key] = event.target.value;
                              return next;
                            })
                          }
                        />
                      </label>
                      <p>
                        1 {conversion.alternateUnitCode} = {factor}{" "}
                        {product.baseUnitCode}
                      </p>
                      {conversionModified && <p>Unsaved changes</p>}
                      <button
                        type="button"
                        disabled={busy || !conversionModified}
                        onClick={() =>
                          saveConversion(product, conversion, factor)
                        }
                      >
                        Save {conversion.alternateUnitCode} conversion
                      </button>
                    </div>
                  ) : (
                    <p key={conversion.alternateUnitId}>
                      1 {conversion.alternateUnitCode} = {factor}{" "}
                      {product.baseUnitCode}
                    </p>
                  );
                })}
                {isAdmin && availableUnits.length > 0 && (
                  <form
                    className="management-form conversion-form"
                    onSubmit={(event) => createConversion(event, product)}
                  >
                    <label>
                      Alternate unit for {product.sku}
                      <select name="alternateUnitId" required defaultValue="">
                        <option value="" disabled>
                          Select a unit
                        </option>
                        {availableUnits.map((unit) => (
                          <option key={unit.id} value={unit.id}>
                            {unit.code} — {unit.name}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      Conversion factor for {product.sku}
                      <input
                        name="conversionFactor"
                        type="number"
                        min="0"
                        step="any"
                        required
                      />
                    </label>
                    <button type="submit" disabled={busy}>
                      Add conversion for {product.sku}
                    </button>
                  </form>
                )}
              </div>
            </article>
          );
        })}
      </div>
      {message && (
        <p className="form-message" role="status">
          {message}
        </p>
      )}
    </section>
  );
}
