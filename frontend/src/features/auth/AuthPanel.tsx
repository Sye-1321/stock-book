import { FormEventHandler } from "react";
import { AuthState } from "./types";

type Props = {
  auth: AuthState;
  busy: boolean;
  message?: string;
  onRetry: () => void;
  onLogin: FormEventHandler<HTMLFormElement>;
  onLogout: () => void;
  onChangePassword: FormEventHandler<HTMLFormElement>;
};

export default function AuthPanel({
  auth,
  busy,
  message,
  onRetry,
  onLogin,
  onLogout,
  onChangePassword,
}: Props) {
  return (
    <section className="auth-panel" aria-label="Account">
      {auth.kind === "loading" && <p>Checking your sessionâ€¦</p>}
      {auth.kind === "unavailable" && (
        <>
          <p role="alert">{auth.message}</p>
          <button type="button" onClick={onRetry}>
            Retry authentication
          </button>
        </>
      )}
      {auth.kind === "anonymous" && (
        <form onSubmit={onLogin}>
          <h2>Sign in</h2>
          <label>
            Login identifier
            <input name="loginIdentifier" autoComplete="username" required />
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
          <button type="submit" disabled={busy}>
            {busy ? "Signing inâ€¦" : "Sign in"}
          </button>
        </form>
      )}
      {auth.kind === "authenticated" && (
        <div>
          <div className="account-heading">
            <div>
              <p className="status-label">Signed in as</p>
              <h2>{auth.user.displayName}</h2>
              <p className="account-identifier">{auth.user.loginIdentifier}</p>
            </div>
            <button type="button" onClick={onLogout} disabled={busy}>
              Sign out
            </button>
          </div>
          <form className="password-form" onSubmit={onChangePassword}>
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
            <button type="submit" disabled={busy}>
              {busy ? "Savingâ€¦" : "Change password"}
            </button>
          </form>
        </div>
      )}
      {message && (
        <p className="form-message" role="alert">
          {message}
        </p>
      )}
    </section>
  );
}
