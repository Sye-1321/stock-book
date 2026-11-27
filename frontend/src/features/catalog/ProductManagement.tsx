import { FormEvent, useCallback, useEffect, useState } from "react";
import { ApiError, isRecord, readJson } from "../../app/api";
import { CsrfRequestError, fetchCsrf } from "../auth/csrf";
import {
  isConversion,
  isChannelPrice,
  isClassification,
  isProduct,
  isUnit,
  Product,
  ProductConversion,
  ChannelPrice,
  Classification,
  UnitOfMeasure,
} from "./types";

type ProductDraft = Pick<
  Product,
  "sku" | "name" | "active" | "categoryId" | "brandId" | "familyId"
>;

type Props = {
  isAdmin: boolean;
  unitsRevision: number;
  classificationsRevision: number;
  onSessionExpired: (message?: string) => void;
};

function productModified(product: Product, draft: ProductDraft | undefined) {
  return (
    draft !== undefined &&
    (draft.sku !== product.sku ||
      draft.name !== product.name ||
      draft.active !== product.active ||
      draft.categoryId !== (product.categoryId ?? null) ||
      draft.brandId !== (product.brandId ?? null) ||
      draft.familyId !== (product.familyId ?? null))
  );
}

function conversionKey(productId: string, unitId: string) {
  return `${productId}:${unitId}`;
}

function isPositiveDecimal(value: string) {
  return /^[1-9]\d*$/.test(value.trim());
}

function isPrice(value: string) {
  return /^(?:0|[1-9]\d*)(?:\.\d{1,8})?$/.test(value.trim());
}

export default function ProductManagement({
  isAdmin,
  unitsRevision,
  classificationsRevision,
  onSessionExpired,
}: Props) {
  const [products, setProducts] = useState<Product[]>([]);
  const [units, setUnits] = useState<UnitOfMeasure[]>([]);
  const [classifications, setClassifications] = useState<{
    categories: Classification[];
    brands: Classification[];
    families: Classification[];
  }>({ categories: [], brands: [], families: [] });
  const [conversions, setConversions] = useState<
    Record<string, ProductConversion[]>
  >({});
  const [productDrafts, setProductDrafts] = useState<
    Record<string, ProductDraft>
  >({});
  const [conversionDrafts, setConversionDrafts] = useState<
    Record<string, string>
  >({});
  const [expandedProducts, setExpandedProducts] = useState<
    Record<string, boolean>
  >({});
  const [conversionLoading, setConversionLoading] = useState<
    Record<string, boolean>
  >({});
  const [conversionErrors, setConversionErrors] = useState<
    Record<string, string | undefined>
  >({});
  const [prices, setPrices] = useState<Record<string, ChannelPrice[]>>({});
  const [priceLoading, setPriceLoading] = useState<Record<string, boolean>>({});
  const [priceErrors, setPriceErrors] = useState<
    Record<string, string | undefined>
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
        const [
          productResponse,
          unitResponse,
          categoryResponse,
          brandResponse,
          familyResponse,
        ] = await Promise.all([
          fetch("/api/v1/products", {
            headers: { Accept: "application/json" },
            signal: controller.signal,
          }),
          fetch("/api/v1/units", {
            headers: { Accept: "application/json" },
            signal: controller.signal,
          }),
          ...(["categories", "brands", "families"] as const).map((group) =>
            fetch(`/api/v1/catalog/${group}`, {
              headers: { Accept: "application/json" },
              signal: controller.signal,
            }),
          ),
        ]);
        const allResponses = [
          productResponse,
          unitResponse,
          categoryResponse,
          brandResponse,
          familyResponse,
        ];
        if (allResponses.some((response) => response.status === 401)) {
          onSessionExpired();
          return;
        }
        const [productBody, unitBody, categoryBody, brandBody, familyBody] =
          await Promise.all(allResponses.map(readJson));
        if (
          !productResponse.ok ||
          !unitResponse.ok ||
          !categoryResponse.ok ||
          !brandResponse.ok ||
          !familyResponse.ok ||
          !Array.isArray(productBody) ||
          !productBody.every(isProduct) ||
          !Array.isArray(unitBody) ||
          !unitBody.every(isUnit) ||
          !Array.isArray(categoryBody) ||
          !categoryBody.every(isClassification) ||
          !Array.isArray(brandBody) ||
          !brandBody.every(isClassification) ||
          !Array.isArray(familyBody) ||
          !familyBody.every(isClassification)
        ) {
          setMessage("Unable to load products.");
          return;
        }
        setProducts(productBody);
        setUnits(unitBody);
        setClassifications({
          categories: categoryBody,
          brands: brandBody,
          families: familyBody,
        });
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
  }, [onSessionExpired, unitsRevision, classificationsRevision]);

  async function loadConversions(productId: string, retry = false) {
    if (!retry && conversions[productId] !== undefined) return;
    setConversionLoading((current) => ({ ...current, [productId]: true }));
    setConversionErrors((current) => ({
      ...current,
      [productId]: undefined,
    }));
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
      if (!response.ok || !Array.isArray(body) || !body.every(isConversion)) {
        setConversionErrors((current) => ({
          ...current,
          [productId]: "Unable to load conversions.",
        }));
        return;
      }
      setConversions((current) => ({ ...current, [productId]: body }));
    } catch {
      setConversionErrors((current) => ({
        ...current,
        [productId]: "Conversion service unavailable.",
      }));
    } finally {
      setConversionLoading((current) => ({
        ...current,
        [productId]: false,
      }));
    }
  }

  async function loadPrices(productId: string, retry = false) {
    if (!retry && prices[productId] !== undefined) return;
    setPriceLoading((current) => ({ ...current, [productId]: true }));
    setPriceErrors((current) => ({ ...current, [productId]: undefined }));
    try {
      const response = await fetch(`/api/v1/products/${productId}/prices`, {
        headers: { Accept: "application/json" },
      });
      if (response.status === 401) {
        onSessionExpired();
        return;
      }
      const body = await readJson(response);
      if (!response.ok || !Array.isArray(body) || !body.every(isChannelPrice)) {
        setPriceErrors((current) => ({
          ...current,
          [productId]: "Unable to load prices.",
        }));
        return;
      }
      setPrices((current) => ({ ...current, [productId]: body }));
    } catch {
      setPriceErrors((current) => ({
        ...current,
        [productId]: "Price service unavailable.",
      }));
    } finally {
      setPriceLoading((current) => ({ ...current, [productId]: false }));
    }
  }

  function toggleProductDetails(productId: string) {
    const expanding = !expandedProducts[productId];
    setExpandedProducts((current) => ({
      ...current,
      [productId]: expanding,
    }));
    if (
      expanding &&
      conversions[productId] === undefined &&
      !conversionLoading[productId]
    )
      void loadConversions(productId);
    if (
      expanding &&
      prices[productId] === undefined &&
      !priceLoading[productId]
    )
      void loadPrices(productId);
  }

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
          categoryId: data.get("productCategoryId") || null,
          brandId: data.get("productBrandId") || null,
          familyId: data.get("productFamilyId") || null,
        }),
      });
      const body = await readJson(response);
      if (response.status === 401) onSessionExpired();
      else if (response.ok && isProduct(body)) {
        setProducts((current) =>
          [...current, body].sort((a, b) => a.sku.localeCompare(b.sku)),
        );
        setConversions((current) => ({ ...current, [body.id]: [] }));
        setPrices((current) => ({ ...current, [body.id]: [] }));
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
          categoryId: product.categoryId ?? null,
          brandId: product.brandId ?? null,
          familyId: product.familyId ?? null,
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
      { ...draft, expectedUpdatedAt: product.updatedAt },
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
    const conversionFactor = String(data.get("conversionFactor") ?? "").trim();
    if (!isPositiveDecimal(conversionFactor)) {
      setMessage(
        "Packaging factor must be a positive whole number of base units.",
      );
      return;
    }
    await submit(
      `/api/v1/products/${product.id}/conversions`,
      {
        alternateUnitId: data.get("alternateUnitId"),
        conversionFactor,
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
    if (!isPositiveDecimal(factor)) {
      setMessage(
        "Packaging factor must be a positive whole number of base units.",
      );
      return;
    }
    const key = conversionKey(product.id, conversion.alternateUnitId);
    const saved = await submit(
      `/api/v1/products/${product.id}/conversions/${conversion.alternateUnitId}`,
      {
        conversionFactor: factor,
        active: conversion.active ?? true,
        expectedUpdatedAt: conversion.updatedAt,
      },
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
    if (saved && prices[product.id] !== undefined) {
      await loadPrices(product.id, true);
    }
  }

  async function setPrice(event: FormEvent<HTMLFormElement>, product: Product) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const amount = String(data.get("amount") ?? "").trim();
    if (!isPrice(amount)) {
      setMessage(
        "Price must be nonnegative with at most eight decimal places.",
      );
      return;
    }
    const unitId = String(data.get("unitId"));
    const channel = String(data.get("channel"));
    const current = (prices[product.id] ?? []).find(
      (price) =>
        price.unitId === unitId &&
        price.channel === channel &&
        price.validTo === null,
    );
    await submit(
      `/api/v1/products/${product.id}/prices`,
      {
        unitId,
        channel,
        amount,
        expectedCurrentValidFrom: current?.validFrom ?? null,
      },
      (body) => {
        if (!isChannelPrice(body)) return false;
        setPrices((state) => ({
          ...state,
          [product.id]: [
            body,
            ...(state[product.id] ?? []).map((price) =>
              price.id === current?.id
                ? { ...price, validTo: body.validFrom }
                : price,
            ),
          ],
        }));
        form.reset();
        setMessage("Price recorded.");
        return true;
      },
      "Unable to record price.",
      "POST",
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
      if (response.status === 401) {
        onSessionExpired();
      } else if (!response.ok || !accept(body)) {
        const error = isRecord(body) ? (body as ApiError) : undefined;
        setMessage(error?.message ?? fallback);
      } else {
        return true;
      }
    } catch (error) {
      handleFailure(error);
    } finally {
      setBusy(false);
    }
    return false;
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
          {(["Category", "Brand", "Family"] as const).map((label) => {
            const group =
              label === "Category"
                ? "categories"
                : label === "Family"
                  ? "families"
                  : "brands";
            return (
              <label key={label}>
                {label}
                <select name={`product${label}Id`} defaultValue="">
                  <option value="">None</option>
                  {classifications[group]
                    .filter((value) => value.active)
                    .map((value) => (
                      <option key={value.id} value={value.id}>
                        {value.code} — {value.name}
                      </option>
                    ))}
                </select>
              </label>
            );
          })}
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
          const conversionsLoaded = conversions[product.id] !== undefined;
          const detailsExpanded = expandedProducts[product.id] ?? false;
          const conversionsAreLoading = conversionLoading[product.id] ?? false;
          const conversionError = conversionErrors[product.id];
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
                  {(["Category", "Brand", "Family"] as const).map((label) => {
                    const field = `${label.toLowerCase()}Id` as
                      "categoryId" | "brandId" | "familyId";
                    const group =
                      label === "Category"
                        ? "categories"
                        : label === "Family"
                          ? "families"
                          : "brands";
                    return (
                      <label key={label}>
                        {label} for {product.sku}
                        <select
                          value={draft[field] ?? ""}
                          onChange={(event) =>
                            editProduct(product, {
                              [field]: event.target.value || null,
                            })
                          }
                        >
                          <option value="">None</option>
                          {classifications[group].map((value) => (
                            <option key={value.id} value={value.id}>
                              {value.code} — {value.name}
                            </option>
                          ))}
                        </select>
                      </label>
                    );
                  })}
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
              <button
                type="button"
                onClick={() => toggleProductDetails(product.id)}
              >
                {detailsExpanded ? "Hide" : "Show"} details for {product.sku}
              </button>
              {detailsExpanded && (
                <div className="conversion-list">
                  <h3>Conversions for {product.sku}</h3>
                  {conversionsAreLoading && <p>Loading conversions…</p>}
                  {conversionError && (
                    <>
                      <p role="alert">{conversionError}</p>
                      <button
                        type="button"
                        disabled={conversionsAreLoading}
                        onClick={() => void loadConversions(product.id, true)}
                      >
                        Retry conversions for {product.sku}
                      </button>
                    </>
                  )}
                  {conversionsLoaded &&
                    !conversionError &&
                    productConversions.length === 0 && (
                      <p>No alternate-unit conversions.</p>
                    )}
                  {conversionsLoaded &&
                    !conversionError &&
                    productConversions.map((conversion) => {
                      const key = conversionKey(
                        product.id,
                        conversion.alternateUnitId,
                      );
                      const factor =
                        conversionDrafts[key] ?? conversion.conversionFactor;
                      const conversionModified =
                        factor !== conversion.conversionFactor;
                      const factorValid = isPositiveDecimal(factor);
                      return isAdmin ? (
                        <div
                          className="conversion-item"
                          key={conversion.alternateUnitId}
                        >
                          <label>
                            {conversion.alternateUnitCode} factor
                            <input
                              type="text"
                              inputMode="decimal"
                              aria-invalid={!factorValid}
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
                          {conversion.packagingCompatible === false && (
                            <p role="alert">
                              Legacy conversion is not a whole-number packaging
                              configuration. Historical quantities are
                              preserved; enter a positive whole number before
                              saving.
                            </p>
                          )}
                          {conversionModified && <p>Unsaved changes</p>}
                          {conversionModified && !factorValid && (
                            <p role="alert">
                              Packaging factor must be a positive whole number
                              of base units.
                            </p>
                          )}
                          <button
                            type="button"
                            disabled={
                              busy || !conversionModified || !factorValid
                            }
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
                  {isAdmin &&
                    conversionsLoaded &&
                    !conversionError &&
                    availableUnits.length > 0 && (
                      <form
                        className="management-form conversion-form"
                        onSubmit={(event) => createConversion(event, product)}
                      >
                        <label>
                          Alternate unit for {product.sku}
                          <select
                            name="alternateUnitId"
                            required
                            defaultValue=""
                          >
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
                            type="text"
                            inputMode="decimal"
                            required
                          />
                        </label>
                        <button type="submit" disabled={busy}>
                          Add conversion for {product.sku}
                        </button>
                      </form>
                    )}
                  <h3>Channel prices for {product.sku}</h3>
                  {priceLoading[product.id] && <p>Loading prices…</p>}
                  {priceErrors[product.id] && (
                    <>
                      <p role="alert">{priceErrors[product.id]}</p>
                      <button
                        type="button"
                        onClick={() => void loadPrices(product.id, true)}
                      >
                        Retry prices for {product.sku}
                      </button>
                    </>
                  )}
                  {prices[product.id] !== undefined &&
                    !priceErrors[product.id] &&
                    prices[product.id].length === 0 && <p>No price history.</p>}
                  {(prices[product.id] ?? []).map((price) => (
                    <p key={price.id}>
                      {price.channel} {price.unitCode}: {price.amount} (
                      {price.conversionFactor === null
                        ? "historical packaging basis unavailable"
                        : `1 ${price.unitCode} = ${price.conversionFactor} base units`}
                      ){price.validTo === null ? " (current)" : " (historical)"}
                    </p>
                  ))}
                  {isAdmin && prices[product.id] !== undefined && (
                    <form
                      className="management-form conversion-form"
                      onSubmit={(event) => setPrice(event, product)}
                    >
                      <label>
                        Price channel for {product.sku}
                        <select name="channel" defaultValue="WHOLESALE">
                          <option value="WHOLESALE">Wholesale</option>
                          <option value="RETAIL">Retail</option>
                        </select>
                      </label>
                      <label>
                        Price package for {product.sku}
                        <select name="unitId" defaultValue={product.baseUnitId}>
                          <option value={product.baseUnitId}>
                            {product.baseUnitCode} (base unit)
                          </option>
                          {productConversions
                            .filter(
                              (conversion) =>
                                (conversion.active ?? true) &&
                                (conversion.packagingCompatible ??
                                  /^\d+$/.test(conversion.conversionFactor)),
                            )
                            .map((conversion) => (
                              <option
                                key={conversion.alternateUnitId}
                                value={conversion.alternateUnitId}
                              >
                                {conversion.alternateUnitCode}
                              </option>
                            ))}
                        </select>
                      </label>
                      <label>
                        Price amount for {product.sku}
                        <input name="amount" inputMode="decimal" required />
                      </label>
                      <button type="submit" disabled={busy}>
                        Record price
                      </button>
                    </form>
                  )}
                </div>
              )}
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
