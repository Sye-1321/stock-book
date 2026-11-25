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

const secondWarehouse = {
  ...warehouse,
  id: "77777777-7777-4777-8777-777777777777",
  code: "SECOND",
  name: "Second Warehouse",
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
    transferId: null,
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

function transfer(requestId: string) {
  return {
    id: "88888888-8888-4888-8888-888888888888",
    requestId,
    sourceWarehouseId: warehouse.id,
    sourceWarehouseCode: warehouse.code,
    destinationWarehouseId: secondWarehouse.id,
    destinationWarehouseCode: secondWarehouse.code,
    productId: product.id,
    productSku: product.sku,
    enteredQuantity: "1.2500000000000000001",
    enteredUnitId: product.baseUnitId,
    enteredUnitCode: product.baseUnitCode,
    conversionFactor: "1",
    baseQuantity: "1.2500000000000000001",
    reason: "Rebalance",
    postedBy: "66666666-6666-4666-8666-666666666666",
    postedByName: "Administrator",
    postedAt: "2026-01-01T00:00:00Z",
  };
}

function inventoryApi(
  post: (body: Record<string, unknown>) => Response | Promise<Response>,
  postTransfer?: (
    body: Record<string, unknown>,
  ) => Response | Promise<Response>,
  history: Record<string, unknown>[] = [],
) {
  return mockApi((url, init) => {
    if (url === "/api/v1/warehouses" && !init?.method)
      return json([warehouse, secondWarehouse]);
    if (url === "/api/v1/products" && !init?.method) return json([product]);
    if (url === "/api/v1/inventory/balances") return json([]);
    if (url === "/api/v1/inventory/movements?page=0&size=50")
      return json({ items: history, page: 0, size: 50, hasMore: false });
    if (url === `/api/v1/products/${product.id}/conversions`) return json([]);
    if (url === "/api/v1/inventory/movements" && init?.method === "POST")
      return post(JSON.parse(String(init.body)) as Record<string, unknown>);
    if (url === "/api/v1/inventory/transfers" && init?.method === "POST")
      return postTransfer?.(
        JSON.parse(String(init.body)) as Record<string, unknown>,
      );
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

  it("posts a transfer with an exact decimal and refreshes confirmed balances", async () => {
    const browser = userEvent.setup();
    let postedBody: Record<string, unknown> | undefined;
    inventoryApi(
      () => json({}, 500),
      (body) => {
        postedBody = body;
        return json(transfer(String(body.requestId)), 201);
      },
    );

    render(<InventoryManagement isAdmin onSessionExpired={vi.fn()} />);
    await screen.findByText("No stock balances are available.");
    await browser.selectOptions(
      screen.getByLabelText("Transfer source warehouse"),
      warehouse.id,
    );
    await browser.selectOptions(
      screen.getByLabelText("Transfer destination warehouse"),
      secondWarehouse.id,
    );
    await browser.selectOptions(
      screen.getByLabelText("Transfer product"),
      product.id,
    );
    await waitFor(() =>
      expect(screen.getByLabelText("Transfer unit")).not.toBeDisabled(),
    );
    await browser.selectOptions(
      screen.getByLabelText("Transfer unit"),
      product.baseUnitId,
    );
    await browser.type(
      screen.getByLabelText("Transfer quantity"),
      "1.2500000000000000001",
    );
    await browser.type(screen.getByLabelText("Transfer reason"), "Rebalance");
    await browser.click(screen.getByRole("button", { name: "Transfer stock" }));

    expect(await screen.findByText("Transfer posted.")).toBeInTheDocument();
    expect(postedBody).toMatchObject({
      sourceWarehouseId: warehouse.id,
      destinationWarehouseId: secondWarehouse.id,
      productId: product.id,
      quantity: "1.2500000000000000001",
      unitId: product.baseUnitId,
      reason: "Rebalance",
    });
    expect(postedBody?.requestId).toEqual(expect.any(String));
  });

  it("keeps failed transfers unconfirmed and safely retries their request ID", async () => {
    const browser = userEvent.setup();
    const requestIds: unknown[] = [];
    inventoryApi(
      () => json({}, 500),
      (body) => {
        requestIds.push(body.requestId);
        return json(
          { code: "conflict", message: "Transfer was rejected." },
          409,
        );
      },
    );

    render(<InventoryManagement isAdmin onSessionExpired={vi.fn()} />);
    await screen.findByText("No stock balances are available.");
    await browser.selectOptions(
      screen.getByLabelText("Transfer source warehouse"),
      warehouse.id,
    );
    await browser.selectOptions(
      screen.getByLabelText("Transfer destination warehouse"),
      secondWarehouse.id,
    );
    await browser.selectOptions(
      screen.getByLabelText("Transfer product"),
      product.id,
    );
    await waitFor(() =>
      expect(screen.getByLabelText("Transfer unit")).not.toBeDisabled(),
    );
    await browser.selectOptions(
      screen.getByLabelText("Transfer unit"),
      product.baseUnitId,
    );
    await browser.type(screen.getByLabelText("Transfer quantity"), "2");
    await browser.type(screen.getByLabelText("Transfer reason"), "Rebalance");

    await browser.click(screen.getByRole("button", { name: "Transfer stock" }));
    expect(
      await screen.findByText("Transfer was rejected."),
    ).toBeInTheDocument();
    expect(screen.queryByText("Transfer posted.")).not.toBeInTheDocument();
    await browser.click(screen.getByRole("button", { name: "Transfer stock" }));
    await waitFor(() => expect(requestIds).toHaveLength(2));
    expect(requestIds[1]).toBe(requestIds[0]);
    expect(screen.queryByText("Transfer posted.")).not.toBeInTheDocument();
  });

  it("shows both transfer movements under their shared transfer link", async () => {
    const transferId = "99999999-9999-4999-8999-999999999999";
    const transferOut = {
      ...movement("unused"),
      requestId: null,
      transferId,
      type: "TRANSFER_OUT",
      baseQuantity: "-2",
      reason: "Rebalance",
    };
    const transferIn = {
      ...transferOut,
      id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      warehouseId: secondWarehouse.id,
      warehouseCode: secondWarehouse.code,
      type: "TRANSFER_IN",
      baseQuantity: "2",
    };
    inventoryApi(() => json({}, 500), undefined, [transferOut, transferIn]);

    render(<InventoryManagement isAdmin={false} onSessionExpired={vi.fn()} />);
    expect(
      await screen.findAllByText(`Linked transfer: ${transferId}`),
    ).toHaveLength(2);
    expect(
      screen.getByRole("heading", { name: "TRANSFER OUT" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "TRANSFER IN" }),
    ).toBeInTheDocument();
  });
});
