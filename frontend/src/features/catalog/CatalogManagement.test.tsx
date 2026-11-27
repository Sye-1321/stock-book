import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, mockApi } from "../../test/apiMock";
import CatalogManagement from "./CatalogManagement";

const piece = {
  id: "11111111-1111-4111-8111-111111111111",
  code: "PIECE",
  name: "Pieces",
};
const carton = {
  id: "22222222-2222-4222-8222-222222222222",
  code: "CARTON",
  name: "Cartons",
};
const box = {
  id: "33333333-3333-4333-8333-333333333333",
  code: "BOX",
  name: "Boxes",
};
const product = {
  id: "44444444-4444-4444-8444-444444444444",
  sku: "SOAP",
  name: "Bar Soap",
  baseUnitId: piece.id,
  baseUnitCode: piece.code,
  baseUnitName: piece.name,
  active: true,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};
const conversion = {
  productId: product.id,
  alternateUnitId: carton.id,
  alternateUnitCode: carton.code,
  alternateUnitName: carton.name,
  conversionFactor: "24",
};

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("catalog management", () => {
  it("keeps a stale classification draft after the server reports a conflict", async () => {
    const browser = userEvent.setup();
    const category = {
      id: "77777777-7777-4777-8777-777777777777",
      code: "SOAP",
      name: "Soap",
      active: true,
      createdAt: "2026-01-01T00:00:00Z",
      updatedAt: "2026-01-01T00:00:00Z",
    };
    mockApi((url, init) => {
      if (url === "/api/v1/catalog/categories" && !init?.method)
        return json([category]);
      if (/^\/api\/v1\/catalog\/(brands|families)$/.test(url) && !init?.method)
        return json([]);
      if (
        url === `/api/v1/catalog/categories/${category.id}` &&
        init?.method === "PUT"
      )
        return json(
          { message: "The category was changed by another user" },
          409,
        );
    });

    render(<CatalogManagement isAdmin onSessionExpired={vi.fn()} />);
    const section = (
      await screen.findByRole("heading", {
        name: "Product classifications",
      })
    ).parentElement!;
    const name = within(section).getByLabelText("Name for SOAP");
    await browser.clear(name);
    await browser.type(name, "Updated soap");
    await browser.click(
      within(section).getByRole("button", { name: "Save SOAP" }),
    );
    expect(
      await within(section).findByText(
        "The category was changed by another user",
      ),
    ).toBeInTheDocument();
    expect(within(section).getByText("Unsaved changes")).toBeInTheDocument();
    expect(name).toHaveValue("Updated soap");
  });

  it("edits classifications and records packaging-specific channel price history", async () => {
    const browser = userEvent.setup();
    const category = {
      id: "77777777-7777-4777-8777-777777777777",
      code: "SOAP",
      name: "Soap",
      active: true,
      createdAt: "2026-01-01T00:00:00Z",
      updatedAt: "2026-01-01T00:00:00Z",
    };
    const currentPrice = {
      id: "88888888-8888-4888-8888-888888888888",
      productId: product.id,
      unitId: carton.id,
      unitCode: carton.code,
      channel: "WHOLESALE" as const,
      amount: "100.00",
      conversionFactor: "24",
      validFrom: "2026-01-01T00:00:00Z",
      validTo: null,
    };
    let pricePayload: Record<string, unknown> | undefined;
    let pricePosts = 0;
    mockApi((url, init) => {
      if (url === "/api/v1/units" && !init?.method)
        return json([piece, carton]);
      if (url === "/api/v1/products" && !init?.method)
        return json([
          { ...product, categoryId: category.id, categoryName: "Soap" },
        ]);
      if (url === "/api/v1/catalog/categories" && !init?.method)
        return json([category]);
      if (/^\/api\/v1\/catalog\/(brands|families)$/.test(url) && !init?.method)
        return json([]);
      if (url === `/api/v1/products/${product.id}/conversions`)
        return json([
          {
            ...conversion,
            active: true,
            packagingCompatible: true,
            updatedAt: "2026-01-01T00:00:00Z",
          },
        ]);
      if (url === `/api/v1/products/${product.id}/prices` && !init?.method)
        return json([currentPrice]);
      if (
        url === `/api/v1/products/${product.id}/prices` &&
        init?.method === "POST"
      ) {
        pricePosts += 1;
        pricePayload = JSON.parse(String(init.body));
        return json(
          {
            ...currentPrice,
            id: "99999999-9999-4999-8999-999999999999",
            amount: "105.12345678",
            validFrom: "2026-02-01T00:00:00Z",
          },
          201,
        );
      }
    });

    render(<CatalogManagement isAdmin onSessionExpired={vi.fn()} />);
    await screen.findByDisplayValue("Bar Soap");
    await browser.click(
      screen.getByRole("button", { name: "Show details for SOAP" }),
    );
    expect(
      await screen.findByText(/WHOLESALE CARTON: 100\.00 .*\(current\)/),
    ).toBeInTheDocument();
    await browser.selectOptions(
      screen.getByLabelText("Price package for SOAP"),
      carton.id,
    );
    await browser.type(
      screen.getByLabelText("Price amount for SOAP"),
      "105.12345678",
    );
    await browser.click(screen.getByRole("button", { name: "Record price" }));
    expect(await screen.findByText("Price recorded.")).toBeInTheDocument();
    expect(pricePayload).toEqual({
      unitId: carton.id,
      channel: "WHOLESALE",
      amount: "105.12345678",
      expectedCurrentValidFrom: currentPrice.validFrom,
    });
    expect(
      screen.getByText(/WHOLESALE CARTON: 105\.12345678 .*\(current\)/),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/WHOLESALE CARTON: 100\.00 .*\(historical\)/),
    ).toBeInTheDocument();
    await browser.type(
      screen.getByLabelText("Price amount for SOAP"),
      "1.123456789",
    );
    await browser.click(screen.getByRole("button", { name: "Record price" }));
    expect(
      await screen.findByText(
        "Price must be nonnegative with at most eight decimal places.",
      ),
    ).toBeInTheDocument();
    expect(pricePosts).toBe(1);
  });

  it("lists and edits units, products and exact conversion factors", async () => {
    const browser = userEvent.setup();
    const exactFactor = "12";
    mockApi((url, init) => {
      if (url === "/api/v1/units" && !init?.method)
        return json([piece, carton, box]);
      if (url === "/api/v1/products" && !init?.method) return json([product]);
      if (
        url === `/api/v1/products/${product.id}/conversions` &&
        !init?.method
      ) {
        return json([conversion]);
      }
      if (url === `/api/v1/units/${piece.id}` && init?.method === "PUT") {
        return json({ ...piece, name: "Each" });
      }
      if (url === `/api/v1/products/${product.id}` && init?.method === "PUT") {
        return json({ ...product, name: "Soap Bar" });
      }
      if (
        url === `/api/v1/products/${product.id}/conversions/${carton.id}` &&
        init?.method === "PUT"
      ) {
        expect(JSON.parse(String(init.body))).toEqual({
          conversionFactor: exactFactor,
          active: true,
        });
        return json({ ...conversion, conversionFactor: exactFactor });
      }
      if (
        url === `/api/v1/products/${product.id}/conversions` &&
        init?.method === "POST"
      ) {
        return json(
          {
            productId: product.id,
            alternateUnitId: box.id,
            alternateUnitCode: box.code,
            alternateUnitName: box.name,
            conversionFactor: "6",
          },
          201,
        );
      }
    });

    render(<CatalogManagement isAdmin onSessionExpired={vi.fn()} />);
    expect(await screen.findByDisplayValue("Bar Soap")).toBeInTheDocument();
    await browser.click(
      screen.getByRole("button", { name: "Show details for SOAP" }),
    );
    expect(await screen.findByText("1 CARTON = 24 PIECE")).toBeInTheDocument();

    const unitName = screen.getByLabelText("Name for PIECE");
    await browser.clear(unitName);
    await browser.type(unitName, "Each");
    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
    await browser.click(screen.getByRole("button", { name: "Save PIECE" }));
    expect(await screen.findByText("Unit updated.")).toBeInTheDocument();

    const productName = screen.getByLabelText("Name for SOAP");
    await browser.clear(productName);
    await browser.type(productName, "Soap Bar");
    await browser.click(screen.getByRole("button", { name: "Save SOAP" }));
    expect(await screen.findByText("Product updated.")).toBeInTheDocument();

    const factor = screen.getByLabelText("CARTON factor");
    await browser.clear(factor);
    await browser.type(factor, exactFactor);
    await browser.click(
      screen.getByRole("button", { name: "Save CARTON conversion" }),
    );
    expect(await screen.findByText("Conversion updated.")).toBeInTheDocument();
    expect(
      screen.getByText(`1 CARTON = ${exactFactor} PIECE`),
    ).toBeInTheDocument();

    await browser.selectOptions(
      screen.getByLabelText("Alternate unit for SOAP"),
      box.id,
    );
    await browser.type(
      screen.getByLabelText("Conversion factor for SOAP"),
      "6",
    );
    await browser.click(
      screen.getByRole("button", { name: "Add conversion for SOAP" }),
    );
    expect(await screen.findByText("Conversion created.")).toBeInTheDocument();
    expect(screen.getByText("1 BOX = 6 PIECE")).toBeInTheDocument();
  });

  it("shows read-only catalog listings to staff users", async () => {
    const browser = userEvent.setup();
    mockApi((url, init) => {
      if (url === "/api/v1/units" && !init?.method)
        return json([piece, carton]);
      if (url === "/api/v1/products" && !init?.method) return json([product]);
      if (url === `/api/v1/products/${product.id}/conversions`)
        return json([conversion]);
    });

    render(<CatalogManagement isAdmin={false} onSessionExpired={vi.fn()} />);
    const products = await screen.findByRole("heading", { name: "Products" });
    expect(
      within(products.parentElement!).getByText("Bar Soap"),
    ).toBeInTheDocument();
    await browser.click(
      screen.getByRole("button", { name: "Show details for SOAP" }),
    );
    expect(await screen.findByText("1 CARTON = 24 PIECE")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Create product" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Create unit" }),
    ).not.toBeInTheDocument();
  });

  it("creates units and products", async () => {
    const browser = userEvent.setup();
    const pallet = {
      id: "55555555-5555-4555-8555-555555555555",
      code: "PALLET",
      name: "Pallets",
    };
    const createdProduct = {
      ...product,
      id: "66666666-6666-4666-8666-666666666666",
      sku: "RICE",
      name: "Rice",
    };
    let units = [piece, carton];
    const requests: Array<{ url: string; init?: RequestInit }> = [];
    mockApi((url, init) => {
      requests.push({ url, init });
      if (url === "/api/v1/units" && !init?.method) return json(units);
      if (url === "/api/v1/products" && !init?.method) return json([]);
      if (url === "/api/v1/units" && init?.method === "POST") {
        units = [...units, pallet];
        return json(pallet, 201);
      }
      if (url === "/api/v1/products" && init?.method === "POST")
        return json(createdProduct, 201);
    });

    render(<CatalogManagement isAdmin onSessionExpired={vi.fn()} />);
    await screen.findByText("No products are available.");

    await browser.type(screen.getByLabelText("Unit code"), "PALLET");
    await browser.type(screen.getByLabelText("Unit name"), "Pallets");
    await browser.click(screen.getByRole("button", { name: "Create unit" }));
    expect(await screen.findByText("Unit created.")).toBeInTheDocument();

    await browser.type(screen.getByLabelText("Product SKU"), "RICE");
    await browser.type(screen.getByLabelText("Product name"), "Rice");
    await browser.selectOptions(screen.getByLabelText("Base unit"), piece.id);
    await browser.click(screen.getByRole("button", { name: "Create product" }));
    expect(await screen.findByText("Product created.")).toBeInTheDocument();
    expect(screen.getByDisplayValue("RICE")).toBeInTheDocument();

    const unitRequest = requests.find(
      ({ url, init }) => url === "/api/v1/units" && init?.method === "POST",
    );
    const productRequest = requests.find(
      ({ url, init }) => url === "/api/v1/products" && init?.method === "POST",
    );
    expect(JSON.parse(String(unitRequest?.init?.body))).toEqual({
      code: "PALLET",
      name: "Pallets",
    });
    expect(JSON.parse(String(productRequest?.init?.body))).toEqual({
      sku: "RICE",
      name: "Rice",
      baseUnitId: piece.id,
      categoryId: null,
      brandId: null,
      familyId: null,
    });
  });

  it("refreshes price history after a packaging factor changes", async () => {
    const browser = userEvent.setup();
    let priceRequests = 0;
    const currentPrice = {
      id: "88888888-8888-4888-8888-888888888888",
      productId: product.id,
      unitId: carton.id,
      unitCode: carton.code,
      channel: "WHOLESALE" as const,
      amount: "100.00000000",
      conversionFactor: "24",
      validFrom: "2026-01-01T00:00:00Z",
      validTo: null,
    };
    mockApi((url, init) => {
      if (url === "/api/v1/units" && !init?.method)
        return json([piece, carton]);
      if (url === "/api/v1/products" && !init?.method) return json([product]);
      if (url === `/api/v1/products/${product.id}/conversions` && !init?.method)
        return json([
          {
            ...conversion,
            active: true,
            packagingCompatible: true,
            updatedAt: "2026-01-01T00:00:00Z",
          },
        ]);
      if (
        url === `/api/v1/products/${product.id}/conversions/${carton.id}` &&
        init?.method === "PUT"
      )
        return json({
          ...conversion,
          conversionFactor: "12",
          active: true,
          packagingCompatible: true,
          updatedAt: "2026-01-02T00:00:00Z",
        });
      if (url === `/api/v1/products/${product.id}/prices` && !init?.method) {
        priceRequests += 1;
        return json([
          priceRequests === 1
            ? currentPrice
            : {
                ...currentPrice,
                validTo: "2026-01-02T00:00:00Z",
              },
        ]);
      }
    });

    render(<CatalogManagement isAdmin onSessionExpired={vi.fn()} />);
    await screen.findByDisplayValue("Bar Soap");
    await browser.click(
      screen.getByRole("button", { name: "Show details for SOAP" }),
    );
    expect(
      await screen.findByText(/WHOLESALE CARTON: 100\.00000000 .*\(current\)/),
    ).toBeInTheDocument();

    const factor = screen.getByLabelText("CARTON factor");
    await browser.clear(factor);
    await browser.type(factor, "12");
    await browser.click(
      screen.getByRole("button", { name: "Save CARTON conversion" }),
    );

    expect(
      await screen.findByText(
        /WHOLESALE CARTON: 100\.00000000 \(1 CARTON = 24 base units\) \(historical\)/,
      ),
    ).toBeInTheDocument();
    expect(priceRequests).toBe(2);
  });

  it("loads conversions on expansion, retries failures, and reuses loaded data", async () => {
    const browser = userEvent.setup();
    let conversionRequests = 0;
    mockApi((url, init) => {
      if (url === "/api/v1/units" && !init?.method)
        return json([piece, carton]);
      if (url === "/api/v1/products" && !init?.method) return json([product]);
      if (
        url === `/api/v1/products/${product.id}/conversions` &&
        !init?.method
      ) {
        conversionRequests += 1;
        return conversionRequests === 1
          ? json({ message: "unavailable" }, 503)
          : json([conversion]);
      }
    });

    render(<CatalogManagement isAdmin onSessionExpired={vi.fn()} />);
    expect(await screen.findByDisplayValue("Bar Soap")).toBeInTheDocument();
    expect(conversionRequests).toBe(0);

    await browser.click(
      screen.getByRole("button", { name: "Show details for SOAP" }),
    );
    expect(
      await screen.findByText("Unable to load conversions."),
    ).toBeInTheDocument();
    expect(screen.getByDisplayValue("Bar Soap")).toBeInTheDocument();
    expect(conversionRequests).toBe(1);

    await browser.click(
      screen.getByRole("button", { name: "Retry conversions for SOAP" }),
    );
    expect(await screen.findByText("1 CARTON = 24 PIECE")).toBeInTheDocument();
    expect(conversionRequests).toBe(2);

    await browser.click(
      screen.getByRole("button", { name: "Hide details for SOAP" }),
    );
    await browser.click(
      screen.getByRole("button", { name: "Show details for SOAP" }),
    );
    expect(screen.getByText("1 CARTON = 24 PIECE")).toBeInTheDocument();
    expect(conversionRequests).toBe(2);
  });

  it("rejects zero and negative conversion factors without API mutations", async () => {
    const browser = userEvent.setup();
    let conversionMutations = 0;
    mockApi((url, init) => {
      if (url === "/api/v1/units" && !init?.method)
        return json([piece, carton, box]);
      if (url === "/api/v1/products" && !init?.method) return json([product]);
      if (url === `/api/v1/products/${product.id}/conversions`) {
        if (init?.method) conversionMutations += 1;
        return json([conversion]);
      }
      if (url === `/api/v1/products/${product.id}/conversions/${carton.id}`) {
        conversionMutations += 1;
        return json(conversion);
      }
    });

    render(<CatalogManagement isAdmin onSessionExpired={vi.fn()} />);
    await screen.findByDisplayValue("Bar Soap");
    await browser.click(
      screen.getByRole("button", { name: "Show details for SOAP" }),
    );
    const factor = await screen.findByLabelText("CARTON factor");
    await browser.clear(factor);
    await browser.type(factor, "0");
    expect(
      screen.getByRole("button", { name: "Save CARTON conversion" }),
    ).toBeDisabled();
    expect(
      screen.getByText(
        "Packaging factor must be a positive whole number of base units.",
      ),
    ).toBeInTheDocument();

    await browser.clear(factor);
    await browser.type(factor, "-0.0001");
    expect(
      screen.getByRole("button", { name: "Save CARTON conversion" }),
    ).toBeDisabled();

    await browser.clear(factor);
    await browser.type(factor, "1.5");
    expect(
      screen.getByRole("button", { name: "Save CARTON conversion" }),
    ).toBeDisabled();

    await browser.selectOptions(
      screen.getByLabelText("Alternate unit for SOAP"),
      box.id,
    );
    await browser.type(
      screen.getByLabelText("Conversion factor for SOAP"),
      "-1",
    );
    await browser.click(
      screen.getByRole("button", { name: "Add conversion for SOAP" }),
    );
    expect(
      screen.getAllByText(
        "Packaging factor must be a positive whole number of base units.",
      ),
    ).toHaveLength(2);
    expect(conversionMutations).toBe(0);
  });

  it("does not present failed catalog loads as empty results", async () => {
    mockApi((url) => {
      if (url === "/api/v1/units") return json({ code: "unavailable" }, 503);
      if (url === "/api/v1/products") return json({ code: "unavailable" }, 503);
    });

    render(<CatalogManagement isAdmin={false} onSessionExpired={vi.fn()} />);
    expect(
      await screen.findByText("Unable to load units."),
    ).toBeInTheDocument();
    expect(
      await screen.findByText("Unable to load products."),
    ).toBeInTheDocument();
    expect(
      screen.queryByText("No units are available."),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText("No products are available."),
    ).not.toBeInTheDocument();
  });
});
