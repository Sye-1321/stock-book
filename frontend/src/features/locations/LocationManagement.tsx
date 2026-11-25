import { FormEvent, useCallback, useEffect, useState } from "react";
import { ApiError, isRecord, readJson } from "../../app/api";
import { CsrfRequestError, fetchCsrf } from "../auth/csrf";

export type Location = {
  id: string;
  code: string;
  name: string;
  active: boolean;
  createdAt: string;
  updatedAt: string;
};

type Draft = Pick<Location, "code" | "name" | "active">;

type Props = {
  isAdmin: boolean;
  onSessionExpired: (message?: string) => void;
  onLocationsChanged: () => void;
};

export function isLocation(value: unknown): value is Location {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    typeof value.code === "string" &&
    typeof value.name === "string" &&
    typeof value.active === "boolean" &&
    typeof value.createdAt === "string" &&
    typeof value.updatedAt === "string"
  );
}

export default function LocationManagement({
  isAdmin,
  onSessionExpired,
  onLocationsChanged,
}: Props) {
  const [locations, setLocations] = useState<Location[]>([]);
  const [drafts, setDrafts] = useState<Record<string, Draft>>({});
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string>();

  const handleFailure = useCallback(
    (error: unknown) => {
      if (error instanceof CsrfRequestError && error.status === 401) {
        onSessionExpired();
      } else {
        setMessage("Location service unavailable.");
      }
    },
    [onSessionExpired],
  );

  useEffect(() => {
    const controller = new AbortController();
    void (async () => {
      try {
        const response = await fetch("/api/v1/locations", {
          headers: { Accept: "application/json" },
          signal: controller.signal,
        });
        if (response.status === 401) {
          onSessionExpired();
          return;
        }
        const body = await readJson(response);
        if (response.ok && Array.isArray(body) && body.every(isLocation)) {
          setLocations(body);
        } else {
          setMessage("Unable to load locations.");
        }
      } catch (error) {
        if (error instanceof DOMException && error.name === "AbortError")
          return;
        setMessage("Location service unavailable.");
      } finally {
        setLoading(false);
      }
    })();
    return () => controller.abort();
  }, [onSessionExpired]);

  async function createLocation(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setBusy(true);
    setMessage(undefined);
    try {
      const csrf = await fetchCsrf();
      const response = await fetch("/api/v1/locations", {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          [csrf.headerName]: csrf.token,
        },
        body: JSON.stringify({
          code: data.get("locationCode"),
          name: data.get("locationName"),
        }),
      });
      const body = await readJson(response);
      if (response.status === 401) {
        onSessionExpired();
      } else if (response.ok && isLocation(body)) {
        setLocations((current) =>
          [...current, body].sort((left, right) =>
            left.code.localeCompare(right.code),
          ),
        );
        form.reset();
        setMessage("Location created.");
        onLocationsChanged();
      } else {
        const error = isRecord(body) ? (body as ApiError) : undefined;
        setMessage(error?.message ?? "Unable to create location.");
      }
    } catch (error) {
      handleFailure(error);
    } finally {
      setBusy(false);
    }
  }

  function edit(location: Location, changes: Partial<Draft>) {
    setDrafts((current) => ({
      ...current,
      [location.id]: {
        ...(current[location.id] ?? {
          code: location.code,
          name: location.name,
          active: location.active,
        }),
        ...changes,
      },
    }));
  }

  async function save(location: Location, draft: Draft) {
    setBusy(true);
    setMessage(undefined);
    try {
      const csrf = await fetchCsrf();
      const response = await fetch(`/api/v1/locations/${location.id}`, {
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
      } else if (response.ok && isLocation(body)) {
        setLocations((current) =>
          current.map((item) => (item.id === body.id ? body : item)),
        );
        setDrafts((current) => {
          const next = { ...current };
          delete next[body.id];
          return next;
        });
        setMessage("Location updated.");
        onLocationsChanged();
      } else {
        const error = isRecord(body) ? (body as ApiError) : undefined;
        setMessage(error?.message ?? "Unable to update location.");
      }
    } catch (error) {
      handleFailure(error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="management-panel" aria-labelledby="locations-heading">
      <h2 id="locations-heading">Locations</h2>
      {isAdmin && (
        <form className="management-form" onSubmit={createLocation}>
          <h3>Create location</h3>
          <label>
            Location code
            <input name="locationCode" required maxLength={50} />
          </label>
          <label>
            Location name
            <input name="locationName" required maxLength={200} />
          </label>
          <button type="submit" disabled={busy}>
            Create location
          </button>
        </form>
      )}
      <div className="management-list" aria-live="polite">
        {loading && <p>Loading locations…</p>}
        {!loading && locations.length === 0 && (
          <p>No locations are available.</p>
        )}
        {locations.map((location) => {
          const draft = drafts[location.id] ?? location;
          return (
            <article className="management-item" key={location.id}>
              {isAdmin ? (
                <>
                  <label>
                    Code for {location.code}
                    <input
                      value={draft.code}
                      maxLength={50}
                      onChange={(event) =>
                        edit(location, { code: event.target.value })
                      }
                    />
                  </label>
                  <label>
                    Name for {location.code}
                    <input
                      value={draft.name}
                      maxLength={200}
                      onChange={(event) =>
                        edit(location, { name: event.target.value })
                      }
                    />
                  </label>
                  <label className="checkbox-label">
                    <input
                      type="checkbox"
                      checked={draft.active}
                      onChange={(event) =>
                        edit(location, { active: event.target.checked })
                      }
                    />
                    Active
                  </label>
                  <button
                    type="button"
                    disabled={busy || !drafts[location.id]}
                    onClick={() => save(location, draft)}
                  >
                    Save {location.code}
                  </button>
                </>
              ) : (
                <>
                  <h3>{location.name}</h3>
                  <p>{location.code}</p>
                  <p>{location.active ? "Active" : "Inactive"}</p>
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
