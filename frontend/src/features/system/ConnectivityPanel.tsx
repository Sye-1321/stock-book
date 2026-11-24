import { useEffect, useState } from "react";
import { isRecord, readJson } from "../../app/api";

type ConnectivityState =
  | { kind: "loading" }
  | { kind: "healthy" }
  | { kind: "unhealthy"; message: string };

export default function ConnectivityPanel() {
  const [connectivity, setConnectivity] = useState<ConnectivityState>({
    kind: "loading",
  });
  const [attempt, setAttempt] = useState(0);

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
  }, [attempt]);

  function retry() {
    setConnectivity({ kind: "loading" });
    setAttempt((current) => current + 1);
  }

  return (
    <div
      className={`status-card status-card--${connectivity.kind}`}
      role="status"
      aria-live="polite"
    >
      <span className="status-indicator" aria-hidden="true" />
      <div>
        <p className="status-label">System connectivity</p>
        {connectivity.kind === "loading" && (
          <p className="status-value">Checking backend and databaseâ€¦</p>
        )}
        {connectivity.kind === "healthy" && (
          <p className="status-value">Backend and database connected</p>
        )}
        {connectivity.kind === "unhealthy" && (
          <>
            <p className="status-value">{connectivity.message}</p>
            <button className="link-button" type="button" onClick={retry}>
              Retry connection
            </button>
          </>
        )}
      </div>
    </div>
  );
}
