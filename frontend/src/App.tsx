import { FormEvent, useEffect, useState } from "react";

type ConnectivityState =
  | { kind: "loading" }
  | { kind: "healthy" }
  | { kind: "unhealthy"; message: string };

type User = { id: string; loginIdentifier: string; displayName: string };
type AuthState =
  | { kind: "loading" }
  | { kind: "anonymous" }
  | { kind: "authenticated"; user: User }
  | { kind: "unavailable"; message: string };

type Csrf = { headerName: string; token: string };
type ApiError = { code?: string; message?: string };

class CsrfRequestError extends Error {
  constructor(readonly status: number) {
    super(`CSRF request failed with status ${status}`);
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isUser(value: unknown): value is User {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    typeof value.loginIdentifier === "string" &&
    typeof value.displayName === "string"
  );
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json();
  } catch {
    return undefined;
  }
}

async function fetchCsrf(): Promise<Csrf> {
  const response = await fetch("/api/v1/auth/csrf", {
    headers: { Accept: "application/json" },
  });
  const body = await readJson(response);
  if (!response.ok) {
    throw new CsrfRequestError(response.status);
  }
  if (
    !isRecord(body) ||
    typeof body.headerName !== "string" ||
    typeof body.token !== "string"
  ) {
    throw new Error("Unable to prepare a secure request");
  }
  return { headerName: body.headerName, token: body.token };
}

export default function App() {
  const [connectivity, setConnectivity] = useState<ConnectivityState>({
    kind: "loading",
  });
  const [connectivityAttempt, setConnectivityAttempt] = useState(0);
  const [auth, setAuth] = useState<AuthState>({ kind: "loading" });
  const [authAttempt, setAuthAttempt] = useState(0);
  const [authBusy, setAuthBusy] = useState(false);
  const [authMessage, setAuthMessage] = useState<string>();

  useEffect(() => {
    const controller = new AbortController();
    void (async () => {
      try {
        const response = await fetch("/api/v1/status", {
          headers: { Accept: "application/json" },
          signal: controller.signal,
        });
        const status = await readJson(response);
        if (
          !isRecord(status) ||
          !["UP", "DOWN"].includes(String(status.database))
        ) {
          setConnectivity({
            kind: "unhealthy",
            message: "Backend service returned an invalid response",
          });
        } else if (response.ok && status.database === "UP") {
          setConnectivity({ kind: "healthy" });
        } else {
          setConnectivity({
            kind: "unhealthy",
            message:
              status.database === "DOWN"
                ? "Database connection unavailable"
                : "Backend service unavailable",
          });
        }
      } catch (error) {
        if (error instanceof DOMException && error.name === "AbortError")
          return;
        setConnectivity({
          kind: "unhealthy",
          message: "Backend service unavailable",
        });
      }
    })();
    return () => controller.abort();
  }, [connectivityAttempt]);

  useEffect(() => {
    const controller = new AbortController();
    void (async () => {
      try {
        const response = await fetch("/api/v1/auth/me", {
          headers: { Accept: "application/json" },
          signal: controller.signal,
        });
        if (response.status === 401) {
          setAuth({ kind: "anonymous" });
          return;
        }
        const body = await readJson(response);
        if (response.ok && isUser(body)) {
          setAuth({ kind: "authenticated", user: body });
        } else {
          setAuth({
            kind: "unavailable",
            message: "Authentication service unavailable",
          });
        }
      } catch (error) {
        if (error instanceof DOMException && error.name === "AbortError")
          return;
        setAuth({
          kind: "unavailable",
          message: "Authentication service unavailable",
        });
      }
    })();
    return () => controller.abort();
  }, [authAttempt]);

  function retryConnection() {
    setConnectivity({ kind: "loading" });
    setConnectivityAttempt((attempt) => attempt + 1);
  }

  function retryAuthentication() {
    setAuth({ kind: "loading" });
    setAuthMessage(undefined);
    setAuthAttempt((attempt) => attempt + 1);
  }

  function handleCsrfFailure(error: unknown) {
    if (error instanceof CsrfRequestError && error.status === 401) {
      setAuth({ kind: "anonymous" });
      setAuthMessage("Your session has expired. Please sign in again.");
    } else if (error instanceof CsrfRequestError && error.status === 403) {
      setAuthMessage(
        "The secure request could not be verified. Please try again.",
      );
    } else {
      setAuthMessage("Authentication service unavailable");
    }
  }

  async function login(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setAuthBusy(true);
    setAuthMessage(undefined);
    try {
      const csrf = await fetchCsrf();
      const response = await fetch("/api/v1/auth/login", {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          [csrf.headerName]: csrf.token,
        },
        body: JSON.stringify({
          loginIdentifier: data.get("loginIdentifier"),
          password: data.get("password"),
        }),
      });
      const body = await readJson(response);
      if (response.ok && isUser(body)) {
        setAuth({ kind: "authenticated", user: body });
      } else {
        const error = isRecord(body) ? (body as ApiError) : undefined;
        setAuthMessage(
          response.status === 429
            ? "Too many sign-in attempts. Try again later."
            : (error?.message ?? "Unable to sign in. Please try again."),
        );
      }
    } catch {
      setAuthMessage("Authentication service unavailable");
    } finally {
      setAuthBusy(false);
    }
  }

  async function logout() {
    setAuthBusy(true);
    setAuthMessage(undefined);
    try {
      const csrf = await fetchCsrf();
      const response = await fetch("/api/v1/auth/logout", {
        method: "POST",
        headers: { [csrf.headerName]: csrf.token },
      });
      if (response.ok || response.status === 401) {
        setAuth({ kind: "anonymous" });
      } else {
        setAuthMessage(
          response.status === 403
            ? "The sign-out request could not be verified. Please try again."
            : "Unable to sign out. Please try again.",
        );
      }
    } catch (error) {
      handleCsrfFailure(error);
    } finally {
      setAuthBusy(false);
    }
  }

  async function changePassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setAuthBusy(true);
    setAuthMessage(undefined);
    try {
      const csrf = await fetchCsrf();
      const response = await fetch("/api/v1/auth/password", {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          [csrf.headerName]: csrf.token,
        },
        body: JSON.stringify({
          currentPassword: data.get("currentPassword"),
          newPassword: data.get("newPassword"),
        }),
      });
      if (response.ok) {
        form.reset();
        setAuth({ kind: "anonymous" });
        setAuthMessage("Password changed. Sign in with your new password.");
        return;
      }
      const body = await readJson(response);
      const error = isRecord(body) ? (body as ApiError) : undefined;
      if (
        response.status === 401 &&
        error?.code !== "invalid_current_password"
      ) {
        setAuth({ kind: "anonymous" });
        setAuthMessage("Your session has expired. Please sign in again.");
      } else {
        setAuthMessage(
          response.status === 403
            ? "The password request could not be verified. Please try again."
            : (error?.message ?? "Unable to change the password."),
        );
      }
    } catch (error) {
      handleCsrfFailure(error);
    } finally {
      setAuthBusy(false);
    }
  }

  return (
    <main>
      <section className="shell" aria-labelledby="page-title">
        <p className="eyebrow">Wholesale operations</p>
        <h1 id="page-title">Stock Book</h1>
        <p className="intro">
          A dependable foundation for purchasing, inventory, sales, and finance.
        </p>

        <div
          className={`status-card status-card--${connectivity.kind}`}
          role="status"
          aria-live="polite"
        >
          <span className="status-indicator" aria-hidden="true" />
          <div>
            <p className="status-label">System connectivity</p>
            {connectivity.kind === "loading" && (
              <p className="status-value">Checking backend and database…</p>
            )}
            {connectivity.kind === "healthy" && (
              <p className="status-value">Backend and database connected</p>
            )}
            {connectivity.kind === "unhealthy" && (
              <>
                <p className="status-value">{connectivity.message}</p>
                <button
                  className="link-button"
                  type="button"
                  onClick={retryConnection}
                >
                  Retry connection
                </button>
              </>
            )}
          </div>
        </div>

        <section className="auth-panel" aria-label="Account">
          {auth.kind === "loading" && <p>Checking your session…</p>}
          {auth.kind === "unavailable" && (
            <>
              <p role="alert">{auth.message}</p>
              <button type="button" onClick={retryAuthentication}>
                Retry authentication
              </button>
            </>
          )}
          {auth.kind === "anonymous" && (
            <form onSubmit={login}>
              <h2>Sign in</h2>
              <label>
                Login identifier
                <input
                  name="loginIdentifier"
                  autoComplete="username"
                  required
                />
              </label>
              <label>
                Password
                <input
                  name="password"
                  type="password"
                  autoComplete="current-password"
                  required
                />
              </label>
              <button type="submit" disabled={authBusy}>
                {authBusy ? "Signing in…" : "Sign in"}
              </button>
            </form>
          )}
          {auth.kind === "authenticated" && (
            <div>
              <div className="account-heading">
                <div>
                  <p className="status-label">Signed in as</p>
                  <h2>{auth.user.displayName}</h2>
                  <p className="account-identifier">
                    {auth.user.loginIdentifier}
                  </p>
                </div>
                <button type="button" onClick={logout} disabled={authBusy}>
                  Sign out
                </button>
              </div>
              <form className="password-form" onSubmit={changePassword}>
                <h3>Change password</h3>
                <label>
                  Current password
                  <input
                    name="currentPassword"
                    type="password"
                    autoComplete="current-password"
                    required
                  />
                </label>
                <label>
                  New password
                  <input
                    name="newPassword"
                    type="password"
                    autoComplete="new-password"
                    minLength={12}
                    required
                  />
                </label>
                <button type="submit" disabled={authBusy}>
                  {authBusy ? "Saving…" : "Change password"}
                </button>
              </form>
            </div>
          )}
          {authMessage && (
            <p className="form-message" role="alert">
              {authMessage}
            </p>
          )}
        </section>
      </section>
    </main>
  );
}
