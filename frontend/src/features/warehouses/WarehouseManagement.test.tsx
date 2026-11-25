import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "../../app/App";
import { json, mockApi, user } from "../../test/apiMock";
import WarehouseManagement from "./WarehouseManagement";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("warehouse management", () => {
  it("lists warehouses and lets administrators create and update them", async () => {
    const browser = userEvent.setup();
    const location = {
      id: "cc4c3c20-09ec-4fd4-a485-84d043ce3a10",
      code: "NAI",
      name: "Nairobi",
      active: true,
      createdAt: "2026-01-01T00:00:00Z",
      updatedAt: "2026-01-01T00:00:00Z",
    };
    const warehouse = {
      id: "b1de402f-45d1-4a2a-8adf-7e8d85fca73a",
      code: "MAIN",
      name: "Main Warehouse",
      locationId: location.id,
      locationCode: location.code,
      locationName: location.name,
      active: true,
      createdAt: "2026-01-01T00:00:00Z",
      updatedAt: "2026-01-01T00:00:00Z",
    };
    const created = {
      ...warehouse,
      id: "56d95fe3-b671-45c9-b78f-79fd5a249ebf",
      code: "SECOND",
      name: "Second Warehouse",
    };
    mockApi((url, init) => {
      if (url === "/api/v1/auth/me") return json(user);
      if (url === "/api/v1/locations" && !init?.method) {
        return json([location]);
      }
      if (url === "/api/v1/warehouses" && !init?.method) {
        return json([warehouse]);
      }
      if (url === "/api/v1/warehouses" && init?.method === "POST") {
        return json(created, 201);
      }
      if (
        url === `/api/v1/warehouses/${warehouse.id}` &&
        init?.method === "PUT"
      ) {
        return json({ ...warehouse, name: "Primary Warehouse" });
      }
    });

    render(<App />);
    expect(
      await screen.findByRole("heading", { name: "Warehouses" }),
    ).toBeInTheDocument();
    expect(
      await screen.findByDisplayValue("Main Warehouse"),
    ).toBeInTheDocument();

    await browser.type(screen.getByLabelText("Warehouse code"), "SECOND");
    await browser.type(
      screen.getByLabelText("Warehouse name"),
      "Second Warehouse",
    );
    await browser.selectOptions(
      screen.getByLabelText("Warehouse location"),
      location.id,
    );
    await browser.click(
      screen.getByRole("button", { name: "Create warehouse" }),
    );
    expect(await screen.findByText("Warehouse created.")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Second Warehouse")).toBeInTheDocument();

    const existingName = screen.getByLabelText("Name for MAIN");
    await browser.clear(existingName);
    await browser.type(existingName, "Primary Warehouse");
    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
    await browser.click(screen.getByRole("button", { name: "Save MAIN" }));
    expect(await screen.findByText("Warehouse updated.")).toBeInTheDocument();
    expect(screen.queryByText("Unsaved changes")).not.toBeInTheDocument();
    expect(screen.getByDisplayValue("Primary Warehouse")).toBeInTheDocument();
  });

  it("does not show an empty result after loading fails", async () => {
    mockApi((url) => {
      if (url === "/api/v1/warehouses") {
        return json({ code: "unavailable" }, 503);
      }
    });

    render(
      <WarehouseManagement
        isAdmin={false}
        locationsRevision={0}
        onSessionExpired={vi.fn()}
      />,
    );

    expect(
      await screen.findByText("Unable to load warehouses."),
    ).toBeInTheDocument();
    expect(
      screen.queryByText("No warehouses are available."),
    ).not.toBeInTheDocument();
  });

  it("marks only changed drafts and retains the marker after a failed save", async () => {
    const browser = userEvent.setup();
    const location = {
      id: "cc4c3c20-09ec-4fd4-a485-84d043ce3a10",
      code: "NAI",
      name: "Nairobi",
      active: true,
      createdAt: "2026-01-01T00:00:00Z",
      updatedAt: "2026-01-01T00:00:00Z",
    };
    const warehouse = {
      id: "b1de402f-45d1-4a2a-8adf-7e8d85fca73a",
      code: "MAIN",
      name: "Main Warehouse",
      locationId: location.id,
      locationCode: location.code,
      locationName: location.name,
      active: true,
      createdAt: "2026-01-01T00:00:00Z",
      updatedAt: "2026-01-01T00:00:00Z",
    };
    mockApi((url, init) => {
      if (url === "/api/v1/locations") return json([location]);
      if (url === "/api/v1/warehouses" && !init?.method) {
        return json([warehouse]);
      }
      if (
        url === `/api/v1/warehouses/${warehouse.id}` &&
        init?.method === "PUT"
      ) {
        return json(
          { code: "conflict", message: "Warehouse update rejected" },
          409,
        );
      }
    });

    render(
      <WarehouseManagement
        isAdmin
        locationsRevision={0}
        onSessionExpired={vi.fn()}
      />,
    );
    const name = await screen.findByLabelText("Name for MAIN");
    const save = screen.getByRole("button", { name: "Save MAIN" });
    expect(save).toBeDisabled();

    await browser.clear(name);
    await browser.type(name, "Changed locally");
    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();

    await browser.clear(name);
    await browser.type(name, "Main Warehouse");
    expect(screen.queryByText("Unsaved changes")).not.toBeInTheDocument();
    expect(save).toBeDisabled();

    await browser.clear(name);
    await browser.type(name, "Changed locally");
    await browser.click(save);
    expect(
      await screen.findByText("Warehouse update rejected"),
    ).toBeInTheDocument();
    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
  });
});
