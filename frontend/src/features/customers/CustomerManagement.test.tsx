import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { json, mockApi } from "../../test/apiMock";
import CustomerManagement from "./CustomerManagement";

const customer = {
  id: "22222222-2222-4222-8222-222222222222",
  partyId: "11111111-1111-4111-8111-111111111111",
  code: "ACME",
  name: "Acme Trading",
  phone: null,
  email: "accounts@acme.test",
  paymentTermsDays: 30,
  active: true,
  partyActive: true,
  partyUpdatedAt: "2026-01-01T00:00:00Z",
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

const party = {
  id: customer.partyId,
  name: customer.name,
  phone: customer.phone,
  email: customer.email,
  active: true,
  supplier: true,
  customer: false,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it("creates a customer profile linked to an existing party", async () => {
  const browser = userEvent.setup();
  let payload: Record<string, unknown> | undefined;
  mockApi((url, init) => {
    if (url === "/api/v1/customers" && !init?.method) return json([]);
    if (url === "/api/v1/parties?query=Shared") return json([party]);
    if (url === "/api/v1/customers" && init?.method === "POST") {
      payload = JSON.parse(String(init.body)) as Record<string, unknown>;
      return json(customer, 201);
    }
  });

  render(<CustomerManagement isAdmin onSessionExpired={vi.fn()} />);
  await screen.findByText("No customers are available.");
  await browser.click(screen.getByLabelText("Link an existing party"));
  await browser.type(screen.getByLabelText("Find existing party"), "Shared");
  await browser.click(screen.getByRole("button", { name: "Search parties" }));
  await browser.selectOptions(
    await screen.findByLabelText("Existing party"),
    party.id,
  );
  await browser.type(screen.getByLabelText("Customer code"), "acme");
  const terms = screen.getByLabelText("Payment terms (days)");
  await browser.clear(terms);
  await browser.type(terms, "30");
  await browser.click(screen.getByRole("button", { name: "Create customer" }));

  expect(await screen.findByText("Customer created.")).toBeInTheDocument();
  expect(screen.getByDisplayValue("ACME")).toBeInTheDocument();
  expect(payload).toEqual({
    partyId: party.id,
    code: "acme",
    name: null,
    phone: null,
    email: null,
    paymentTermsDays: 30,
  });
});

it("retains an edited customer draft after failure and clears it after confirmation", async () => {
  const browser = userEvent.setup();
  let saves = 0;
  let attemptedPayload: Record<string, unknown> | undefined;
  mockApi((url, init) => {
    if (url === "/api/v1/customers" && !init?.method) return json([customer]);
    if (url === `/api/v1/customers/${customer.id}` && init?.method === "PUT") {
      saves += 1;
      attemptedPayload = JSON.parse(String(init.body)) as Record<
        string,
        unknown
      >;
      if (saves === 1)
        return json(
          {
            message:
              "Shared party information changed since it was loaded; reload and try again",
          },
          409,
        );
      return json({
        ...customer,
        name: "Acme Updated",
        active: false,
        paymentTermsDays: 45,
        partyUpdatedAt: "2026-01-02T00:00:00Z",
      });
    }
  });

  render(<CustomerManagement isAdmin onSessionExpired={vi.fn()} />);
  const terms = await screen.findByLabelText("Payment terms for ACME");
  await browser.clear(screen.getByLabelText("Name for ACME"));
  await browser.type(screen.getByLabelText("Name for ACME"), "Acme Updated");
  await browser.clear(terms);
  await browser.type(terms, "45");
  await browser.click(screen.getByLabelText("Customer profile active"));
  expect(screen.getByText("Unsaved changes")).toBeInTheDocument();

  await browser.click(screen.getByRole("button", { name: "Save ACME" }));
  expect(
    await screen.findByText(
      "Shared party information changed since it was loaded; reload and try again",
    ),
  ).toBeInTheDocument();
  expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
  expect(attemptedPayload).toMatchObject({
    updateParty: true,
    partyUpdatedAt: customer.partyUpdatedAt,
  });

  await browser.click(screen.getByRole("button", { name: "Save ACME" }));
  expect(await screen.findByText("Customer updated.")).toBeInTheDocument();
  await waitFor(() =>
    expect(screen.queryByText("Unsaved changes")).not.toBeInTheDocument(),
  );
});

it("searches the bounded party lookup and distinguishes failure from empty results", async () => {
  const browser = userEvent.setup();
  mockApi((url) => {
    if (url === "/api/v1/customers") return json([]);
    if (url === "/api/v1/parties?query=missing")
      return json({ message: "Unavailable" }, 503);
    if (url === "/api/v1/parties?query=none") return json([]);
  });

  render(<CustomerManagement isAdmin onSessionExpired={vi.fn()} />);
  await screen.findByText("No customers are available.");
  await browser.click(screen.getByLabelText("Link an existing party"));
  const query = screen.getByLabelText("Find existing party");
  await browser.type(query, "missing");
  await browser.click(screen.getByRole("button", { name: "Search parties" }));
  expect(
    await screen.findByText("Unable to search parties."),
  ).toBeInTheDocument();
  expect(
    screen.queryByText("No matching parties are available."),
  ).not.toBeInTheDocument();

  await browser.clear(query);
  await browser.type(query, "none");
  await browser.click(screen.getByRole("button", { name: "Search parties" }));
  expect(
    await screen.findByText("No matching parties are available."),
  ).toBeInTheDocument();
});

it("does not show an empty result when customer loading fails", async () => {
  mockApi((url) => {
    if (url === "/api/v1/customers")
      return json({ message: "Unavailable" }, 503);
  });

  render(<CustomerManagement isAdmin onSessionExpired={vi.fn()} />);

  expect(
    await screen.findByText("Unable to load customers."),
  ).toBeInTheDocument();
  expect(
    screen.queryByText("No customers are available."),
  ).not.toBeInTheDocument();
});
