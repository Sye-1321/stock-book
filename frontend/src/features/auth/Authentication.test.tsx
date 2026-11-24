import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "../../app/App";
import { json, mockApi, user } from "../../test/apiMock";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("authentication", () => {
  it("uses a CSRF token to sign in and shows the authenticated user", async () => {
    const browser = userEvent.setup();
    const fetchMock = mockApi((url) => {
      if (url === "/api/v1/auth/login") return json(user);
    });

    render(<App />);
    await browser.type(
      await screen.findByLabelText("Login identifier"),
      "admin@example.com",
    );
    await browser.type(screen.getByLabelText("Password"), "secret password");
    await browser.click(screen.getByRole("button", { name: "Sign in" }));

    expect(
      await screen.findByText("Initial Administrator"),
    ).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/auth/csrf",
      expect.any(Object),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/auth/login",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({ "X-CSRF-TOKEN": "csrf-token" }),
      }),
    );
  });

  it("reports rate limiting without treating it as an expired session", async () => {
    const browser = userEvent.setup();
    mockApi((url) => {
      if (url === "/api/v1/auth/login") {
        return json({ code: "too_many_attempts", message: "Try later" }, 429);
      }
    });

    render(<App />);
    await browser.type(
      await screen.findByLabelText("Login identifier"),
      "admin@example.com",
    );
    await browser.type(screen.getByLabelText("Password"), "wrong password");
    await browser.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Too many sign-in attempts. Try again later.",
    );
    expect(
      screen.getByRole("heading", { name: "Sign in" }),
    ).toBeInTheDocument();
  });

  it("signs out an existing session", async () => {
    const browser = userEvent.setup();
    mockApi((url) => {
      if (url === "/api/v1/auth/me") return json(user);
      if (url === "/api/v1/auth/logout")
        return new Response(null, { status: 204 });
    });

    render(<App />);
    expect(
      await screen.findByText("Initial Administrator"),
    ).toBeInTheDocument();
    await browser.click(screen.getByRole("button", { name: "Sign out" }));
    expect(
      await screen.findByRole("heading", { name: "Sign in" }),
    ).toBeInTheDocument();
  });

  it("revalidates an expired session when the user returns to the window", async () => {
    let sessionRequests = 0;
    mockApi((url) => {
      if (url === "/api/v1/auth/me") {
        sessionRequests += 1;
        return sessionRequests === 1 ? json(user) : json({}, 401);
      }
    });

    render(<App />);
    expect(
      await screen.findByText("Initial Administrator"),
    ).toBeInTheDocument();
    expect(sessionRequests).toBe(1);
    fireEvent.focus(window);
    expect(
      await screen.findByRole("heading", { name: "Sign in" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Your session has expired.",
    );
    expect(sessionRequests).toBe(2);
  });

  it("returns to sign-in after a password change revokes the session", async () => {
    const browser = userEvent.setup();
    mockApi((url) => {
      if (url === "/api/v1/auth/me") return json(user);
      if (url === "/api/v1/auth/password")
        return new Response(null, { status: 204 });
    });

    render(<App />);
    await browser.type(
      await screen.findByLabelText("Current password"),
      "old password value",
    );
    await browser.type(
      screen.getByLabelText("New password"),
      "new password value",
    );
    await browser.click(
      screen.getByRole("button", { name: "Change password" }),
    );
    expect(
      await screen.findByRole("heading", { name: "Sign in" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Password changed. Sign in with your new password.",
    );
  });

  it("clears authenticated state when a protected request reports an expired session", async () => {
    const browser = userEvent.setup();
    mockApi((url) => {
      if (url === "/api/v1/auth/me") return json(user);
      if (url === "/api/v1/auth/password") {
        return json(
          { code: "unauthenticated", message: "Authentication is required" },
          401,
        );
      }
    });

    render(<App />);
    await browser.type(
      await screen.findByLabelText("Current password"),
      "old password value",
    );
    await browser.type(
      screen.getByLabelText("New password"),
      "new password value",
    );
    await browser.click(
      screen.getByRole("button", { name: "Change password" }),
    );
    expect(
      await screen.findByRole("heading", { name: "Sign in" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Your session has expired.",
    );
  });

  it.each(["Sign out", "Change password"])(
    "returns to sign-in when CSRF acquisition expires before %s",
    async (action) => {
      const browser = userEvent.setup();
      mockApi((url) => {
        if (url === "/api/v1/auth/me") return json(user);
        if (url === "/api/v1/auth/csrf") {
          return json({ code: "unauthenticated" }, 401);
        }
      });

      render(<App />);
      await screen.findByText("Initial Administrator");
      if (action === "Change password") {
        await browser.type(
          screen.getByLabelText("Current password"),
          "old password value",
        );
        await browser.type(
          screen.getByLabelText("New password"),
          "new password value",
        );
      }
      await browser.click(screen.getByRole("button", { name: action }));
      expect(
        await screen.findByRole("heading", { name: "Sign in" }),
      ).toBeInTheDocument();
      expect(screen.getByRole("alert")).toHaveTextContent(
        "Your session has expired.",
      );
    },
  );

  it("keeps the session when CSRF acquisition is forbidden", async () => {
    const browser = userEvent.setup();
    mockApi((url) => {
      if (url === "/api/v1/auth/me") return json(user);
      if (url === "/api/v1/auth/csrf") return json({ code: "forbidden" }, 403);
    });

    render(<App />);
    await browser.type(
      await screen.findByLabelText("Current password"),
      "old password value",
    );
    await browser.type(
      screen.getByLabelText("New password"),
      "new password value",
    );
    await browser.click(
      screen.getByRole("button", { name: "Change password" }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The secure request could not be verified.",
    );
    await waitFor(() =>
      expect(screen.getByText("Initial Administrator")).toBeInTheDocument(),
    );
  });

  it("recovers from an unexpected session response without showing false authentication", async () => {
    const browser = userEvent.setup();
    let sessionRequests = 0;
    mockApi((url) => {
      if (url === "/api/v1/auth/me") {
        sessionRequests += 1;
        return sessionRequests === 1
          ? json({ displayName: "Incomplete" })
          : json({}, 401);
      }
    });

    render(<App />);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Authentication service unavailable",
    );
    expect(screen.queryByText("Signed in as")).not.toBeInTheDocument();
    await browser.click(
      screen.getByRole("button", { name: "Retry authentication" }),
    );
    expect(
      await screen.findByRole("heading", { name: "Sign in" }),
    ).toBeInTheDocument();
  });
});
