import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { ApiError, isRecord, readJson } from "../../app/api";
import { CsrfRequestError, fetchCsrf } from "./csrf";
import { AuthState, isUser } from "./types";

export function useAuthentication() {
  const [auth, setAuth] = useState<AuthState>({ kind: "loading" });
  const [attempt, setAttempt] = useState(0);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string>();
  const sessionRevalidationInFlight = useRef(false);
  const sessionRequestGeneration = useRef(0);

  const invalidateSessionRequests = useCallback(() => {
    sessionRequestGeneration.current += 1;
  }, []);

  const expireSession = useCallback(
    (sessionMessage = "Your session has expired. Please sign in again.") => {
      invalidateSessionRequests();
      setAuth({ kind: "anonymous" });
      setMessage(sessionMessage);
    },
    [invalidateSessionRequests],
  );

  useEffect(() => {
    const controller = new AbortController();
    const requestGeneration = sessionRequestGeneration.current;
    void (async () => {
      try {
        const response = await fetch("/api/v1/auth/me", {
          headers: { Accept: "application/json" },
          signal: controller.signal,
        });
        if (requestGeneration !== sessionRequestGeneration.current) return;
        if (response.status === 401) {
          setAuth({ kind: "anonymous" });
          return;
        }
        const body = await readJson(response);
        if (requestGeneration !== sessionRequestGeneration.current) return;
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
        if (requestGeneration !== sessionRequestGeneration.current) return;
        setAuth({
          kind: "unavailable",
          message: "Authentication service unavailable",
        });
      }
    })();
    return () => controller.abort();
  }, [attempt]);

  useEffect(() => {
    if (auth.kind !== "authenticated") return;

    const controller = new AbortController();
    async function revalidateSession() {
      if (sessionRevalidationInFlight.current) return;
      sessionRevalidationInFlight.current = true;
      const requestGeneration = sessionRequestGeneration.current;
      try {
        const response = await fetch("/api/v1/auth/me", {
          headers: { Accept: "application/json" },
          signal: controller.signal,
        });
        if (requestGeneration !== sessionRequestGeneration.current) return;
        if (response.status === 401) {
          expireSession();
          return;
        }
        const body = await readJson(response);
        if (requestGeneration !== sessionRequestGeneration.current) return;
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
        if (requestGeneration !== sessionRequestGeneration.current) return;
        setAuth({
          kind: "unavailable",
          message: "Authentication service unavailable",
        });
      } finally {
        sessionRevalidationInFlight.current = false;
      }
    }

    function revalidateWhenActive() {
      if (document.visibilityState === "visible") void revalidateSession();
    }

    window.addEventListener("focus", revalidateWhenActive);
    document.addEventListener("visibilitychange", revalidateWhenActive);
    return () => {
      window.removeEventListener("focus", revalidateWhenActive);
      document.removeEventListener("visibilitychange", revalidateWhenActive);
      controller.abort();
    };
  }, [auth.kind, expireSession]);

  function retry() {
    invalidateSessionRequests();
    setAuth({ kind: "loading" });
    setMessage(undefined);
    setAttempt((current) => current + 1);
  }

  function handleCsrfFailure(error: unknown) {
    if (error instanceof CsrfRequestError && error.status === 401) {
      expireSession();
    } else if (error instanceof CsrfRequestError && error.status === 403) {
      setMessage("The secure request could not be verified. Please try again.");
    } else {
      setMessage("Authentication service unavailable");
    }
  }

  async function login(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setBusy(true);
    setMessage(undefined);
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
        invalidateSessionRequests();
        setAuth({ kind: "authenticated", user: body });
      } else {
        const error = isRecord(body) ? (body as ApiError) : undefined;
        setMessage(
          response.status === 429
            ? "Too many sign-in attempts. Try again later."
            : (error?.message ?? "Unable to sign in. Please try again."),
        );
      }
    } catch {
      setMessage("Authentication service unavailable");
    } finally {
      setBusy(false);
    }
  }

  async function logout() {
    setBusy(true);
    setMessage(undefined);
    try {
      const csrf = await fetchCsrf();
      const response = await fetch("/api/v1/auth/logout", {
        method: "POST",
        headers: { [csrf.headerName]: csrf.token },
      });
      if (response.ok || response.status === 401) {
        invalidateSessionRequests();
        setAuth({ kind: "anonymous" });
      } else {
        setMessage(
          response.status === 403
            ? "The sign-out request could not be verified. Please try again."
            : "Unable to sign out. Please try again.",
        );
      }
    } catch (error) {
      handleCsrfFailure(error);
    } finally {
      setBusy(false);
    }
  }

  async function changePassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setBusy(true);
    setMessage(undefined);
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
        invalidateSessionRequests();
        setAuth({ kind: "anonymous" });
        setMessage("Password changed. Sign in with your new password.");
        return;
      }
      const body = await readJson(response);
      const error = isRecord(body) ? (body as ApiError) : undefined;
      if (
        response.status === 401 &&
        error?.code !== "invalid_current_password"
      ) {
        expireSession();
      } else {
        setMessage(
          response.status === 403
            ? "The password request could not be verified. Please try again."
            : (error?.message ?? "Unable to change the password."),
        );
      }
    } catch (error) {
      handleCsrfFailure(error);
    } finally {
      setBusy(false);
    }
  }

  return {
    auth,
    busy,
    message,
    retry,
    login,
    logout,
    changePassword,
    expireSession,
  };
}
