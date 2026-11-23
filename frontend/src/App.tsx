import { useEffect, useState } from "react";

type ViewState =
  | { kind: "loading" }
  | { kind: "healthy" }
  | { kind: "unhealthy"; message: string };

type StatusResponse = {
  database: "UP" | "DOWN";
};

function isStatusResponse(value: unknown): value is StatusResponse {
  if (typeof value !== "object" || value === null || !("database" in value)) {
    return false;
  }

  return value.database === "UP" || value.database === "DOWN";
}

export default function App() {
  const [state, setState] = useState<ViewState>({ kind: "loading" });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();

    async function checkStatus() {
      try {
        const response = await fetch("/api/v1/status", {
          headers: { Accept: "application/json" },
          signal: controller.signal,
        });
        const status: unknown = await response.json();

        if (!isStatusResponse(status)) {
          setState({
            kind: "unhealthy",
            message: "Backend service returned an invalid response",
          });
          return;
        }

        if (response.ok && status.database === "UP") {
          setState({ kind: "healthy" });
        } else if (status.database === "DOWN") {
          setState({
            kind: "unhealthy",
            message: "Database connection unavailable",
          });
        } else {
          setState({
            kind: "unhealthy",
            message: "Backend service unavailable",
          });
        }
      } catch (error) {
        if (error instanceof DOMException && error.name === "AbortError")
          return;
        setState({
          kind: "unhealthy",
          message:
            error instanceof SyntaxError
              ? "Backend service returned an invalid response"
              : "Backend service unavailable",
        });
      }
    }

    void checkStatus();
    return () => controller.abort();
  }, [attempt]);

  function retryConnection() {
    setState({ kind: "loading" });
    setAttempt((currentAttempt) => currentAttempt + 1);
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
          className={`status-card status-card--${state.kind}`}
          role="status"
          aria-live="polite"
        >
          <span className="status-indicator" aria-hidden="true" />
          <div>
            <p className="status-label">System connectivity</p>
            {state.kind === "loading" && (
              <p className="status-value">Checking backend and database…</p>
            )}
            {state.kind === "healthy" && (
              <p className="status-value">Backend and database connected</p>
            )}
            {state.kind === "unhealthy" && (
              <>
                <p className="status-value">{state.message}</p>
                <button
                  className="retry-button"
                  type="button"
                  onClick={retryConnection}
                >
                  Retry connection
                </button>
              </>
            )}
          </div>
        </div>
      </section>
    </main>
  );
}
