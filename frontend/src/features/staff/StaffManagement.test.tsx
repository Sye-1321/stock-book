import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "../../app/App";
import { json, mockApi, urlOf, user } from "../../test/apiMock";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("staff management", () => {
  it("shows staff management only to administrators and supports create and update", async () => {
    const browser = userEvent.setup();
    const createdAccount = {
      id: "60db3370-9bdd-48f1-a5aa-839bd13f55d1",
      loginIdentifier: "staff@example.com",
      displayName: "Warehouse Staff",
      role: "STAFF",
      active: true,
    };
    const fetchMock = mockApi((url, init) => {
      if (url === "/api/v1/auth/me") return json(user);
      if (url === "/api/v1/staff" && !init?.method) return json([]);
      if (url === "/api/v1/staff" && init?.method === "POST") {
        return json(createdAccount, 201);
      }
      if (
        url === `/api/v1/staff/${createdAccount.id}` &&
        init?.method === "PUT"
      ) {
        return json({ ...createdAccount, role: "ADMIN", active: false });
      }
    });

    render(<App />);
    expect(
      await screen.findByRole("heading", { name: "Staff management" }),
    ).toBeInTheDocument();
    await browser.type(
      screen.getByLabelText("Login identifier"),
      "staff@example.com",
    );
    await browser.type(
      screen.getByLabelText("Display name"),
      "Warehouse Staff",
    );
    await browser.type(
      screen.getByLabelText("Initial password"),
      "staff initial password",
    );
    await browser.click(
      screen.getByRole("button", { name: "Create staff account" }),
    );

    expect(
      await screen.findByText("Staff account created."),
    ).toBeInTheDocument();
    await browser.selectOptions(
      screen.getByLabelText("Role for staff@example.com"),
      "ADMIN",
    );
    await browser.click(screen.getByLabelText("Active"));
    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
    await browser.click(
      screen.getByRole("button", { name: "Save staff@example.com" }),
    );
    expect(
      await screen.findByText("Staff account updated."),
    ).toBeInTheDocument();
    expect(screen.queryByText("Unsaved changes")).not.toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/staff/${createdAccount.id}`,
      expect.objectContaining({ method: "PUT" }),
    );
  });

  it("keeps failed account changes marked as unsaved", async () => {
    const browser = userEvent.setup();
    const existingAccount = {
      id: "60db3370-9bdd-48f1-a5aa-839bd13f55d1",
      loginIdentifier: "staff@example.com",
      displayName: "Warehouse Staff",
      role: "STAFF",
      active: true,
    } as const;
    mockApi((url, init) => {
      if (url === "/api/v1/auth/me") return json(user);
      if (url === "/api/v1/staff" && !init?.method) {
        return json([existingAccount]);
      }
      if (
        url === `/api/v1/staff/${existingAccount.id}` &&
        init?.method === "PUT"
      ) {
        return json({ code: "conflict", message: "Update rejected" }, 409);
      }
    });

    render(<App />);
    const displayName = await screen.findByLabelText(
      "Display name for staff@example.com",
    );
    await browser.clear(displayName);
    await browser.type(displayName, "Changed locally");
    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();

    await browser.click(
      screen.getByRole("button", { name: "Save staff@example.com" }),
    );

    expect(await screen.findByText("Update rejected")).toBeInTheDocument();
    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
    expect(displayName).toHaveValue("Changed locally");
    expect(
      screen.queryByText("Staff account updated."),
    ).not.toBeInTheDocument();
  });

  it("does not expose staff management to staff users", async () => {
    const fetchMock = mockApi((url) => {
      if (url === "/api/v1/auth/me") {
        return json({ ...user, role: "STAFF" });
      }
    });

    render(<App />);
    expect(
      await screen.findByText("Initial Administrator"),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "Staff management" }),
    ).not.toBeInTheDocument();
    expect(
      fetchMock.mock.calls.some(([input]) => urlOf(input) === "/api/v1/staff"),
    ).toBe(false);
  });
});
