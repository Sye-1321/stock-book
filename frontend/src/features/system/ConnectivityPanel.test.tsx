import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "../../app/App";
import { json, mockApi } from "../../test/apiMock";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("system connectivity", () => {
  it("shows actual connectivity and an anonymous sign-in screen", async () => {
    const fetchMock = mockApi();

    render(<App />);

    expect(
      screen.getByText("Checking backend and databaseâ€¦"),
    ).toBeInTheDocument();
    expect(
      await screen.findByText("Backend and database connected"),
    ).toBeInTheDocument();
    expect(
      await screen.findByRole("heading", { name: "Sign in" }),
    ).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/status",
      expect.any(Object),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/auth/me",
      expect.any(Object),
    );
  });

  it("recovers from a database connectivity failure when retried", async () => {
    const browser = userEvent.setup();
    let statusRequests = 0;
    mockApi((url) => {
      if (url === "/api/v1/status") {
        statusRequests += 1;
        return statusRequests === 1
          ? json({ database: "DOWN" }, 503)
          : json({ database: "UP" });
      }
    });

    render(<App />);
    expect(
      await screen.findByText("Database connection unavailable"),
    ).toBeInTheDocument();
    await browser.click(
      screen.getByRole("button", { name: "Retry connection" }),
    );
    expect(
      await screen.findByText("Backend and database connected"),
    ).toBeInTheDocument();
    expect(statusRequests).toBe(2);
  });
});
