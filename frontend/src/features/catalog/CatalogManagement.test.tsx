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
  it("lists and edits units, products and exact conversion factors", async () => {
    const browser = userEvent.setup();
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
        return json({ ...conversion, conversionFactor: "12.5001" });
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
    expect(screen.getByText("1 CARTON = 24 PIECE")).toBeInTheDocument();

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
    await browser.type(factor, "12.5001");
    await browser.click(
      screen.getByRole("button", { name: "Save CARTON conversion" }),
    );
    expect(await screen.findByText("Conversion updated.")).toBeInTheDocument();
    expect(screen.getByText("1 CARTON = 12.5001 PIECE")).toBeInTheDocument();

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
    expect(screen.getByText("1 CARTON = 24 PIECE")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Create product" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Create unit" }),
    ).not.toBeInTheDocument();
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
