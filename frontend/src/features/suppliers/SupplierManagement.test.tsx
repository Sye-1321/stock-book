import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { json, mockApi } from "../../test/apiMock";
import SupplierManagement from "./SupplierManagement";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it("creates a supplier from confirmed server data", async () => {
  const browser = userEvent.setup();
  const changed = vi.fn();
  let payload: Record<string, unknown> | undefined;
  mockApi((url, init) => {
    if (url === "/api/v1/suppliers" && !init?.method) return json([]);
    if (url === "/api/v1/suppliers" && init?.method === "POST") {
      payload = JSON.parse(String(init.body)) as Record<string, unknown>;
      return json(
        {
          id: "11111111-1111-4111-8111-111111111111",
          code: "ACME",
          name: "Acme Supplies",
          phone: "+254700000000",
          email: "sales@acme.test",
          active: true,
          createdAt: "2026-01-01T00:00:00Z",
          updatedAt: "2026-01-01T00:00:00Z",
        },
        201,
      );
    }
  });

  render(
    <SupplierManagement
      isAdmin
      onSessionExpired={vi.fn()}
      onSuppliersChanged={changed}
    />,
  );
  await screen.findByText("No suppliers are available.");
  await browser.type(screen.getByLabelText("Supplier code"), "acme");
  await browser.type(screen.getByLabelText("Supplier name"), "Acme Supplies");
  await browser.type(screen.getByLabelText("Supplier phone"), "+254700000000");
  await browser.type(
    screen.getByLabelText("Supplier email"),
    "sales@acme.test",
  );
  await browser.click(screen.getByRole("button", { name: "Create supplier" }));

  expect(await screen.findByText("Supplier created.")).toBeInTheDocument();
  expect(screen.getByDisplayValue("ACME")).toBeInTheDocument();
  expect(payload).toEqual({
    code: "acme",
    name: "Acme Supplies",
    phone: "+254700000000",
    email: "sales@acme.test",
  });
  expect(changed).toHaveBeenCalledOnce();
});
