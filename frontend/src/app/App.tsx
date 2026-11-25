import AuthPanel from "../features/auth/AuthPanel";
import { useAuthentication } from "../features/auth/useAuthentication";
import ConnectivityPanel from "../features/system/ConnectivityPanel";
import AuthenticatedWorkspace from "./AuthenticatedWorkspace";

export default function App() {
  const authentication = useAuthentication();

  return (
    <main>
      <section className="shell" aria-labelledby="page-title">
        <p className="eyebrow">Wholesale operations</p>
        <h1 id="page-title">Stock Book</h1>
        <p className="intro">
          A dependable foundation for purchasing, inventory, sales, and finance.
        </p>

        <ConnectivityPanel />
        <AuthPanel
          auth={authentication.auth}
          busy={authentication.busy}
          message={authentication.message}
          onRetry={authentication.retry}
          onLogin={authentication.login}
          onLogout={authentication.logout}
          onChangePassword={authentication.changePassword}
        />
        {authentication.auth.kind === "authenticated" && (
          <AuthenticatedWorkspace
            user={authentication.auth.user}
            onSessionExpired={authentication.expireSession}
          />
        )}
      </section>
    </main>
  );
}
