import { useEffect, useState } from "react";

type ViewState =
  | { kind: "loading" }
  | { kind: "healthy" }
  | { kind: "unhealthy"; message: string };

type StatusResponse = {
  database: "UP" | "DOWN";
};

export default function App() {
  const [state, setState] = useState<ViewState>({ kind: "loading" });

  useEffect(() => {
    const controller = new AbortController();

    async function checkStatus() {
      try {
        const response = await fetch("/api/v1/status", {
          headers: { Accept: "application/json" },
          signal: controller.signal,
        });
        const status = (await response.json()) as StatusResponse;

        if (response.ok && status.database === "UP") {
          setState({ kind: "healthy" });
        } else {
          setState({
            kind: "unhealthy",
            message: "Database connection unavailable",
          });
        }
      } catch (error) {
        if (error instanceof DOMException && error.name === "AbortError")
          return;
        setState({ kind: "unhealthy", message: "Backend service unavailable" });
      }
    }

    void checkStatus();
    return () => controller.abort();
  }, []);

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
              <p className="status-value">{state.message}</p>
            )}
          </div>
        </div>
      </section>
    </main>
  );
}
