import { FormEvent, useCallback, useEffect, useState } from "react";
import { ApiError, isRecord, readJson } from "../../app/api";
import { CsrfRequestError, fetchCsrf } from "../auth/csrf";
import { isUnit, UnitOfMeasure } from "./types";

type Draft = Pick<UnitOfMeasure, "code" | "name">;

type Props = {
  isAdmin: boolean;
  onSessionExpired: (message?: string) => void;
  onUnitsChanged: () => void;
};

function isModified(unit: UnitOfMeasure, draft: Draft | undefined) {
  return (
    draft !== undefined &&
    (draft.code !== unit.code || draft.name !== unit.name)
  );
}

export default function UnitManagement({
  isAdmin,
  onSessionExpired,
  onUnitsChanged,
}: Props) {
  const [units, setUnits] = useState<UnitOfMeasure[]>([]);
  const [drafts, setDrafts] = useState<Record<string, Draft>>({});
  const [loading, setLoading] = useState(true);
  const [loadSucceeded, setLoadSucceeded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string>();

  const handleFailure = useCallback(
    (error: unknown) => {
      if (error instanceof CsrfRequestError && error.status === 401) {
        onSessionExpired();
      } else {
        setMessage("Unit service unavailable.");
      }
    },
    [onSessionExpired],
  );

  useEffect(() => {
    const controller = new AbortController();
    setLoadSucceeded(false);
    void (async () => {
      try {
        const response = await fetch("/api/v1/units", {
          headers: { Accept: "application/json" },
          signal: controller.signal,
        });
        if (response.status === 401) {
          onSessionExpired();
          return;
        }
        const body = await readJson(response);
        if (response.ok && Array.isArray(body) && body.every(isUnit)) {
          setUnits(body);
          setLoadSucceeded(true);
        } else {
          setMessage("Unable to load units.");
        }
      } catch (error) {
        if (error instanceof DOMException && error.name === "AbortError")
          return;
        setMessage("Unit service unavailable.");
      } finally {
        setLoading(false);
      }
    })();
    return () => controller.abort();
  }, [onSessionExpired]);

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setBusy(true);
    setMessage(undefined);
    try {
      const csrf = await fetchCsrf();
      const response = await fetch("/api/v1/units", {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          [csrf.headerName]: csrf.token,
        },
        body: JSON.stringify({
          code: data.get("unitCode"),
          name: data.get("unitName"),
        }),
      });
      const body = await readJson(response);
      if (response.status === 401) {
        onSessionExpired();
      } else if (response.ok && isUnit(body)) {
        setUnits((current) =>
          [...current, body].sort((left, right) =>
            left.code.localeCompare(right.code),
          ),
        );
        form.reset();
        setMessage("Unit created.");
        onUnitsChanged();
      } else {
        const error = isRecord(body) ? (body as ApiError) : undefined;
        setMessage(error?.message ?? "Unable to create unit.");
      }
    } catch (error) {
      handleFailure(error);
    } finally {
      setBusy(false);
    }
  }

  function edit(unit: UnitOfMeasure, changes: Partial<Draft>) {
    setDrafts((current) => {
      const draft = {
        ...(current[unit.id] ?? { code: unit.code, name: unit.name }),
        ...changes,
      };
      const next = { ...current };
      if (isModified(unit, draft)) next[unit.id] = draft;
      else delete next[unit.id];
      return next;
    });
  }

  async function save(unit: UnitOfMeasure, draft: Draft) {
    setBusy(true);
    setMessage(undefined);
    try {
      const csrf = await fetchCsrf();
      const response = await fetch(`/api/v1/units/${unit.id}`, {
        method: "PUT",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          [csrf.headerName]: csrf.token,
        },
        body: JSON.stringify(draft),
      });
      const body = await readJson(response);
      if (response.status === 401) {
        onSessionExpired();
      } else if (response.ok && isUnit(body)) {
        setUnits((current) =>
          current.map((item) => (item.id === body.id ? body : item)),
        );
        setDrafts((current) => {
          const next = { ...current };
          delete next[body.id];
          return next;
        });
        setMessage("Unit updated.");
        onUnitsChanged();
      } else {
        const error = isRecord(body) ? (body as ApiError) : undefined;
        setMessage(error?.message ?? "Unable to update unit.");
      }
    } catch (error) {
      handleFailure(error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="management-panel" aria-labelledby="units-heading">
      <h2 id="units-heading">Units of measure</h2>
      {isAdmin && (
        <form className="management-form" onSubmit={create}>
          <h3>Create unit</h3>
          <label>
            Unit code
            <input name="unitCode" required maxLength={50} />
          </label>
          <label>
            Unit name
            <input name="unitName" required maxLength={200} />
          </label>
          <button type="submit" disabled={busy}>
            Create unit
          </button>
        </form>
      )}
      <div className="management-list" aria-live="polite">
        {loading && <p>Loading units…</p>}
        {!loading && loadSucceeded && units.length === 0 && (
          <p>No units are available.</p>
        )}
        {units.map((unit) => {
          const draft = drafts[unit.id] ?? unit;
          const modified = isModified(unit, drafts[unit.id]);
          return (
            <article className="management-item" key={unit.id}>
              {isAdmin ? (
                <>
                  <label>
                    Code for {unit.code}
                    <input
                      value={draft.code}
                      maxLength={50}
                      onChange={(event) =>
                        edit(unit, { code: event.target.value })
                      }
                    />
                  </label>
                  <label>
                    Name for {unit.code}
                    <input
                      value={draft.name}
                      maxLength={200}
                      onChange={(event) =>
                        edit(unit, { name: event.target.value })
                      }
                    />
                  </label>
                  {modified && <p>Unsaved changes</p>}
                  <button
                    type="button"
                    disabled={busy || !modified}
                    onClick={() => save(unit, draft)}
                  >
                    Save {unit.code}
                  </button>
                </>
              ) : (
                <>
                  <h3>{unit.name}</h3>
                  <p>{unit.code}</p>
                </>
              )}
            </article>
          );
        })}
      </div>
      {message && (
        <p className="form-message" role="status">
          {message}
        </p>
      )}
    </section>
  );
}
