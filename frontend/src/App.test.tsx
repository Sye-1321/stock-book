import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "./App";

afterEach(() => {
  vi.restoreAllMocks();
});

describe("App", () => {
  it("shows loading and then a successful database connection", async () => {
    let resolveFetch!: (response: Response) => void;
    vi.spyOn(globalThis, "fetch").mockReturnValue(
      new Promise<Response>((resolve) => {
        resolveFetch = resolve;
      }),
    );

    render(<App />);
    expect(
      screen.getByText("Checking backend and database…"),
    ).toBeInTheDocument();

    resolveFetch(
      new Response(JSON.stringify({ database: "UP" }), { status: 200 }),
    );
    expect(
      await screen.findByText("Backend and database connected"),
    ).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledWith("/api/v1/status", expect.any(Object));
  });

  it("shows the database failure returned by the backend", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ database: "DOWN" }), { status: 503 }),
    );

    render(<App />);
    expect(
      await screen.findByText("Database connection unavailable"),
    ).toBeInTheDocument();
  });

  it("shows a backend failure when the request cannot be completed", async () => {
    vi.spyOn(globalThis, "fetch").mockRejectedValue(
      new TypeError("Failed to fetch"),
    );

    render(<App />);
    expect(
      await screen.findByText("Backend service unavailable"),
    ).toBeInTheDocument();
  });
});
