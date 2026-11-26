import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, mockApi } from "../../test/apiMock";
import GoodsReceiving from "./GoodsReceiving";

const supplier = {
  id: "11111111-1111-4111-8111-111111111111",
  code: "ACME",
  name: "Acme Supplies",
  phone: null,
  email: null,
  active: true,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};
const warehouse = {
  id: "22222222-2222-4222-8222-222222222222",
  code: "MAIN",
  name: "Main Warehouse",
  active: true,
};
const piece = { id: "33333333-3333-4333-8333-333333333333", code: "PIECE" };
const soap = {
  id: "44444444-4444-4444-8444-444444444444",
  sku: "SOAP",
  name: "Bar Soap",
  baseUnitId: piece.id,
  baseUnitCode: piece.code,
  baseUnitName: "Pieces",
  active: true,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

function receipt(requestId: string) {
  return {
    id: "55555555-5555-4555-8555-555555555555",
    requestId,
    supplierId: supplier.id,
    supplierCode: supplier.code,
    warehouseId: warehouse.id,
    warehouseCode: warehouse.code,
    supplierDocumentReference: "INV-10",
    postedBy: "66666666-6666-4666-8666-666666666666",
    postedByName: "Administrator",
    postedAt: "2026-01-01T00:00:00Z",
    lines: [
      {
        id: "77777777-7777-4777-8777-777777777777",
        lineNumber: 1,
        productId: soap.id,
        productSku: soap.sku,
        enteredQuantity: "2.5000000000000000001",
        enteredUnitId: piece.id,
        enteredUnitCode: piece.code,
        conversionFactor: "1",
        baseQuantity: "2.5000000000000000001",
      },
    ],
  };
}

function receivingApi(
  post: (body: Record<string, unknown>) => Response | Promise<Response>,
) {
  mockApi((url, init) => {
    if (url === "/api/v1/suppliers") return json([supplier]);
    if (url === "/api/v1/warehouses") return json([warehouse]);
    if (url === "/api/v1/products") return json([soap]);
    if (url === `/api/v1/products/${soap.id}/conversions`) return json([]);
    if (url === "/api/v1/inventory/receipts" && init?.method === "POST")
      return post(JSON.parse(String(init.body)) as Record<string, unknown>);
  });
}

async function completeForm(browser: ReturnType<typeof userEvent.setup>) {
  await screen.findByRole("button", { name: "Post receipt" });
  await browser.selectOptions(
    screen.getByLabelText("Receipt supplier"),
    supplier.id,
  );
  await browser.selectOptions(
    screen.getByLabelText("Receipt warehouse"),
    warehouse.id,
  );
  await browser.type(
    screen.getByLabelText("Supplier document reference"),
    "INV-10",
  );
  await browser.selectOptions(
    screen.getByLabelText("Product for line 1"),
    soap.id,
  );
  await waitFor(() =>
    expect(screen.getByLabelText("Unit for line 1")).not.toBeDisabled(),
  );
  await browser.selectOptions(
    screen.getByLabelText("Unit for line 1"),
    piece.id,
  );
  await browser.type(
    screen.getByLabelText("Quantity for line 1"),
    "2.5000000000000000001",
  );
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("goods receiving", () => {
  it("posts an exact receipt only after server confirmation", async () => {
    const browser = userEvent.setup();
    const posted = vi.fn();
    let payload: Record<string, unknown> | undefined;
    receivingApi((body) => {
      payload = body;
      return json(receipt(String(body.requestId)), 201);
    });
    render(
      <GoodsReceiving
        suppliersRevision={0}
        onSessionExpired={vi.fn()}
        onReceiptPosted={posted}
      />,
    );
    await completeForm(browser);
    await browser.click(screen.getByRole("button", { name: "Post receipt" }));

    expect(
      await screen.findByText(
        "Receipt posted: 55555555-5555-4555-8555-555555555555",
      ),
    ).toBeInTheDocument();
    expect(payload).toMatchObject({
      supplierId: supplier.id,
      warehouseId: warehouse.id,
      supplierDocumentReference: "INV-10",
      lines: [
        {
          productId: soap.id,
          quantity: "2.5000000000000000001",
          unitId: piece.id,
        },
      ],
    });
    expect(posted).toHaveBeenCalledOnce();
  });

  it("keeps a failed receipt unconfirmed and reuses its request ID", async () => {
    const browser = userEvent.setup();
    const requestIds: unknown[] = [];
    receivingApi((body) => {
      requestIds.push(body.requestId);
      return json({ code: "conflict", message: "Receipt was rejected." }, 409);
    });
    render(
      <GoodsReceiving
        suppliersRevision={0}
        onSessionExpired={vi.fn()}
        onReceiptPosted={vi.fn()}
      />,
    );
    await completeForm(browser);
    await browser.click(screen.getByRole("button", { name: "Post receipt" }));
    expect(
      await screen.findByText("Receipt was rejected."),
    ).toBeInTheDocument();
    expect(screen.queryByText(/Receipt posted:/)).not.toBeInTheDocument();
    await browser.click(screen.getByRole("button", { name: "Post receipt" }));
    await waitFor(() => expect(requestIds).toHaveLength(2));
    expect(requestIds[1]).toBe(requestIds[0]);
    expect(screen.queryByText(/Receipt posted:/)).not.toBeInTheDocument();
  });
});
