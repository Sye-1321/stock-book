import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "../../app/App";
import { json, mockApi, user } from "../../test/apiMock";
import LocationManagement from "./LocationManagement";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("location management", () => {
  it("lists locations and lets administrators create and update them", async () => {
    const browser = userEvent.setup();
    const location = {
      id: "cc4c3c20-09ec-4fd4-a485-84d043ce3a10",
      code: "NAI",
      name: "Nairobi",
      active: true,
      createdAt: "2026-01-01T00:00:00Z",
      updatedAt: "2026-01-01T00:00:00Z",
    };
    const created = {
      ...location,
      id: "7502f6f0-ce5c-424c-b44e-eb56a358addf",
      code: "MSA",
      name: "Mombasa",
    };
    mockApi((url, init) => {
      if (url === "/api/v1/auth/me") return json(user);
      if (url === "/api/v1/locations" && !init?.method) {
        return json([location]);
      }
      if (url === "/api/v1/locations" && init?.method === "POST") {
        return json(created, 201);
      }
      if (
        url === `/api/v1/locations/${location.id}` &&
        init?.method === "PUT"
      ) {
        return json({ ...location, name: "Nairobi Region" });
      }
    });

    render(<App />);
    expect(
      await screen.findByRole("heading", { name: "Locations" }),
    ).toBeInTheDocument();
    expect(await screen.findByDisplayValue("Nairobi")).toBeInTheDocument();

    await browser.type(screen.getByLabelText("Location code"), "MSA");
    await browser.type(screen.getByLabelText("Location name"), "Mombasa");
    await browser.click(
      screen.getByRole("button", { name: "Create location" }),
    );
    expect(await screen.findByText("Location created.")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Mombasa")).toBeInTheDocument();

    const existingName = screen.getByLabelText("Name for NAI");
    await browser.clear(existingName);
    await browser.type(existingName, "Nairobi Region");
    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
    await browser.click(screen.getByRole("button", { name: "Save NAI" }));
    expect(await screen.findByText("Location updated.")).toBeInTheDocument();
    expect(screen.queryByText("Unsaved changes")).not.toBeInTheDocument();
    expect(screen.getByDisplayValue("Nairobi Region")).toBeInTheDocument();
  });

  it("does not show an empty result after loading fails", async () => {
    mockApi((url) => {
      if (url === "/api/v1/locations") {
        return json({ code: "unavailable" }, 503);
      }
    });

    render(
      <LocationManagement
        isAdmin={false}
        onSessionExpired={vi.fn()}
        onLocationsChanged={vi.fn()}
      />,
    );

    expect(
      await screen.findByText("Unable to load locations."),
    ).toBeInTheDocument();
    expect(
      screen.queryByText("No locations are available."),
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
    mockApi((url, init) => {
      if (url === "/api/v1/locations" && !init?.method) {
        return json([location]);
      }
      if (
        url === `/api/v1/locations/${location.id}` &&
        init?.method === "PUT"
      ) {
        return json(
          { code: "conflict", message: "Location update rejected" },
          409,
        );
      }
    });

    render(
      <LocationManagement
        isAdmin
        onSessionExpired={vi.fn()}
        onLocationsChanged={vi.fn()}
      />,
    );
    const name = await screen.findByLabelText("Name for NAI");
    const save = screen.getByRole("button", { name: "Save NAI" });
    expect(save).toBeDisabled();

    await browser.clear(name);
    await browser.type(name, "Changed locally");
    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();

    await browser.clear(name);
    await browser.type(name, "Nairobi");
    expect(screen.queryByText("Unsaved changes")).not.toBeInTheDocument();
    expect(save).toBeDisabled();

    await browser.clear(name);
    await browser.type(name, "Changed locally");
    await browser.click(save);
    expect(
      await screen.findByText("Location update rejected"),
    ).toBeInTheDocument();
    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
  });
});
