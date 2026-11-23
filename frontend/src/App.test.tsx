import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "./App";

function deferredResponse() {
  let resolve!: (response: Response) => void;
  const promise = new Promise<Response>((promiseResolve) => {
    resolve = promiseResolve;
  });

  return { promise, resolve };
}

function healthyResponse() {
  return new Response(JSON.stringify({ database: "UP" }), { status: 200 });
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("App", () => {
  it("shows loading and then a successful database connection", async () => {
    const request = deferredResponse();
    vi.spyOn(globalThis, "fetch").mockReturnValue(request.promise);

    render(<App />);
    expect(
      screen.getByText("Checking backend and database…"),
    ).toBeInTheDocument();

    request.resolve(healthyResponse());
    expect(
      await screen.findByText("Backend and database connected"),
    ).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledWith("/api/v1/status", expect.any(Object));
  });

  it("recovers from a database failure when retried", async () => {
    const user = userEvent.setup();
    const retry = deferredResponse();
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ database: "DOWN" }), { status: 503 }),
      )
      .mockReturnValueOnce(retry.promise);

    render(<App />);
    expect(
      await screen.findByText("Database connection unavailable"),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Retry connection" }));
    expect(
      screen.getByText(/Checking backend and database/),
    ).toBeInTheDocument();

    retry.resolve(healthyResponse());
    expect(
      await screen.findByText("Backend and database connected"),
    ).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("recovers from a backend failure when retried", async () => {
    const user = userEvent.setup();
    const retry = deferredResponse();
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockRejectedValueOnce(new TypeError("Failed to fetch"))
      .mockReturnValueOnce(retry.promise);

    render(<App />);
    expect(
      await screen.findByText("Backend service unavailable"),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Retry connection" }));
    expect(
      screen.getByText(/Checking backend and database/),
    ).toBeInTheDocument();

    retry.resolve(healthyResponse());
    expect(
      await screen.findByText("Backend and database connected"),
    ).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it.each([
    ["invalid JSON", new Response("not JSON", { status: 200 })],
    [
      "an unexpected response structure",
      new Response(JSON.stringify({ status: "UP" }), { status: 200 }),
    ],
  ])("handles %s gracefully", async (_description, response) => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(response);

    render(<App />);
    expect(
      await screen.findByText("Backend service returned an invalid response"),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Retry connection" }),
    ).toBeInTheDocument();
  });
});
