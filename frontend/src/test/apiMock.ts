import { vi } from "vitest";

export const user = {
  id: "fe1a09b9-ff0b-4ec7-87fb-c3a5a53b73dc",
  loginIdentifier: "admin@example.com",
  displayName: "Initial Administrator",
  role: "ADMIN",
} as const;

export function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

export function urlOf(input: RequestInfo | URL) {
  return typeof input === "string" ? input : input.toString();
}

export function mockApi(
  handler?: (
    url: string,
    init?: RequestInit,
  ) => Response | Promise<Response> | undefined,
) {
  return vi.spyOn(globalThis, "fetch").mockImplementation((input, init) => {
    const url = urlOf(input);
    const handled = handler?.(url, init);
    if (handled) return Promise.resolve(handled);
    if (url === "/api/v1/status")
      return Promise.resolve(json({ database: "UP" }));
    if (url === "/api/v1/auth/me") return Promise.resolve(json({}, 401));
    if (url === "/api/v1/staff") return Promise.resolve(json([]));
    if (url === "/api/v1/locations") return Promise.resolve(json([]));
    if (url === "/api/v1/warehouses") return Promise.resolve(json([]));
    if (url === "/api/v1/units") return Promise.resolve(json([]));
    if (url === "/api/v1/products") return Promise.resolve(json([]));
    if (url === "/api/v1/suppliers") return Promise.resolve(json([]));
    if (url === "/api/v1/inventory/balances") return Promise.resolve(json([]));
    if (url === "/api/v1/inventory/movements?page=0&size=50")
      return Promise.resolve(
        json({ items: [], page: 0, size: 50, hasMore: false }),
      );
    if (/^\/api\/v1\/products\/[^/]+\/conversions$/.test(url)) {
      return Promise.resolve(json([]));
    }
    if (url === "/api/v1/auth/csrf") {
      return Promise.resolve(
        json({ headerName: "X-CSRF-TOKEN", token: "csrf-token" }),
      );
    }
    throw new Error(`Unexpected request: ${url}`);
  });
}
