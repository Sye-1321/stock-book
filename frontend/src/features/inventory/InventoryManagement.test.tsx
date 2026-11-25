import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, mockApi } from "../../test/apiMock";
import InventoryManagement from "./InventoryManagement";

const warehouse = {
  id: "11111111-1111-4111-8111-111111111111",
  code: "MAIN",
  name: "Main Warehouse",
  locationId: "22222222-2222-4222-8222-222222222222",
  locationCode: "NAI",
  locationName: "Nairobi",
  active: true,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

const product = {
  id: "33333333-3333-4333-8333-333333333333",
  sku: "SOAP",
  name: "Bar Soap",
  baseUnitId: "44444444-4444-4444-8444-444444444444",
  baseUnitCode: "PIECE",
  baseUnitName: "Pieces",
  active: true,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

function movement(requestId: string) {
  return {
    id: "55555555-5555-4555-8555-555555555555",
    requestId,
    warehouseId: warehouse.id,
    warehouseCode: warehouse.code,
    productId: product.id,
    productSku: product.sku,
    type: "OPENING",
    enteredQuantity: "1.0000000000000000001",
    enteredUnitId: product.baseUnitId,
    enteredUnitCode: product.baseUnitCode,
    conversionFactor: "1",
    baseQuantity: "1.0000000000000000001",
    reason: null,
    postedBy: "66666666-6666-4666-8666-666666666666",
    postedByName: "Administrator",
    postedAt: "2026-01-01T00:00:00Z",
  };
}

function inventoryApi(
  post: (body: Record<string, unknown>) => Response | Promise<Response>,
) {
  return mockApi((url, init) => {
    if (url === "/api/v1/warehouses" && !init?.method) return json([warehouse]);
    if (url === "/api/v1/products" && !init?.method) return json([product]);
    if (url === "/api/v1/inventory/balances") return json([]);
    if (url === "/api/v1/inventory/movements?page=0&size=50")
      return json({ items: [], page: 0, size: 50, hasMore: false });
    if (url === `/api/v1/products/${product.id}/conversions`) return json([]);
    if (url === "/api/v1/inventory/movements" && init?.method === "POST")
      return post(JSON.parse(String(init.body)) as Record<string, unknown>);
  });
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("inventory management", () => {
  it("posts an exact decimal movement and refreshes confirmed inventory", async () => {
    const browser = userEvent.setup();
    let postedBody: Record<string, unknown> | undefined;
    inventoryApi((body) => {
      postedBody = body;
      return json(movement(String(body.requestId)), 201);
    });

    render(<InventoryManagement isAdmin onSessionExpired={vi.fn()} />);
    await screen.findByText("No stock balances are available.");
    await browser.selectOptions(
      screen.getByLabelText("Warehouse"),
      warehouse.id,
    );
    await browser.selectOptions(screen.getByLabelText("Product"), product.id);
    await waitFor(() =>
      expect(screen.getByLabelText("Unit")).not.toBeDisabled(),
    );
    await browser.selectOptions(
      screen.getByLabelText("Unit"),
      product.baseUnitId,
    );
    await browser.type(
      screen.getByLabelText("Quantity"),
      "1.0000000000000000001",
    );
    await browser.click(screen.getByRole("button", { name: "Post movement" }));

    expect(await screen.findByText("Movement posted.")).toBeInTheDocument();
    expect(postedBody).toMatchObject({
      warehouseId: warehouse.id,
      productId: product.id,
      type: "OPENING",
      quantity: "1.0000000000000000001",
      unitId: product.baseUnitId,
      reason: null,
    });
    expect(postedBody?.requestId).toEqual(expect.any(String));
  });

  it("keeps failed postings unconfirmed and reuses the request ID when retried", async () => {
    const browser = userEvent.setup();
    const requestIds: unknown[] = [];
    inventoryApi((body) => {
      requestIds.push(body.requestId);
      return json({ code: "conflict", message: "Posting was rejected." }, 409);
    });

    render(<InventoryManagement isAdmin onSessionExpired={vi.fn()} />);
    await screen.findByText("No stock balances are available.");
    await browser.selectOptions(
      screen.getByLabelText("Warehouse"),
      warehouse.id,
    );
    await browser.selectOptions(screen.getByLabelText("Product"), product.id);
    await waitFor(() =>
      expect(screen.getByLabelText("Unit")).not.toBeDisabled(),
    );
    await browser.selectOptions(
      screen.getByLabelText("Unit"),
      product.baseUnitId,
    );
    await browser.selectOptions(
      screen.getByLabelText("Movement type"),
      "ADJUSTMENT_IN",
    );
    await browser.type(screen.getByLabelText("Quantity"), "2.5");
    await browser.type(
      screen.getByLabelText("Reason for adjustment"),
      "Count correction",
    );

    await browser.click(screen.getByRole("button", { name: "Post movement" }));
    expect(
      await screen.findByText("Posting was rejected."),
    ).toBeInTheDocument();
    expect(screen.queryByText("Movement posted.")).not.toBeInTheDocument();
    await browser.click(screen.getByRole("button", { name: "Post movement" }));
    await waitFor(() => expect(requestIds).toHaveLength(2));
    expect(requestIds[1]).toBe(requestIds[0]);
    expect(screen.queryByText("Movement posted.")).not.toBeInTheDocument();
  });
});
