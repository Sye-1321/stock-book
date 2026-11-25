import { FormEvent, useCallback, useEffect, useState } from "react";
import { ApiError, isRecord, readJson } from "../../app/api";
import { CsrfRequestError, fetchCsrf } from "../auth/csrf";
import { isLocation, Location } from "../locations/LocationManagement";

type Warehouse = {
  id: string;
  code: string;
  name: string;
  locationId: string;
  locationCode: string;
  locationName: string;
  active: boolean;
  createdAt: string;
  updatedAt: string;
};

type Draft = Pick<Warehouse, "code" | "name" | "active">;

type Props = {
  isAdmin: boolean;
  locationsRevision: number;
  onSessionExpired: (message?: string) => void;
};

function isWarehouse(value: unknown): value is Warehouse {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    typeof value.code === "string" &&
    typeof value.name === "string" &&
    typeof value.locationId === "string" &&
    typeof value.locationCode === "string" &&
    typeof value.locationName === "string" &&
    typeof value.active === "boolean" &&
    typeof value.createdAt === "string" &&
    typeof value.updatedAt === "string"
  );
}

export default function WarehouseManagement({
  isAdmin,
  locationsRevision,
  onSessionExpired,
}: Props) {
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
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
        setMessage("Warehouse service unavailable.");
      }
    },
    [onSessionExpired],
  );

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    void (async () => {
      try {
        const [warehouseResponse, locationResponse] = await Promise.all([
          fetch("/api/v1/warehouses", {
            headers: { Accept: "application/json" },
            signal: controller.signal,
          }),
          fetch("/api/v1/locations", {
            headers: { Accept: "application/json" },
            signal: controller.signal,
          }),
        ]);
        if (
          warehouseResponse.status === 401 ||
          locationResponse.status === 401
        ) {
          onSessionExpired();
          return;
        }
        const [warehouseBody, locationBody] = await Promise.all([
          readJson(warehouseResponse),
          readJson(locationResponse),
        ]);
        if (
          warehouseResponse.ok &&
          locationResponse.ok &&
          Array.isArray(warehouseBody) &&
          warehouseBody.every(isWarehouse) &&
          Array.isArray(locationBody) &&
          locationBody.every(isLocation)
        ) {
          setWarehouses(warehouseBody);
          setLocations(locationBody);
        } else {
          setMessage("Unable to load warehouses.");
        }
      } catch (error) {
        if (error instanceof DOMException && error.name === "AbortError")
          return;
        setMessage("Warehouse service unavailable.");
      } finally {
        setLoading(false);
      }
    })();
    return () => controller.abort();
  }, [locationsRevision, onSessionExpired]);

  async function createWarehouse(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setBusy(true);
    setMessage(undefined);
    try {
      const csrf = await fetchCsrf();
      const response = await fetch("/api/v1/warehouses", {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          [csrf.headerName]: csrf.token,
        },
        body: JSON.stringify({
          code: data.get("warehouseCode"),
          name: data.get("warehouseName"),
          locationId: data.get("warehouseLocationId"),
        }),
      });
      const body = await readJson(response);
      if (response.status === 401) {
        onSessionExpired();
      } else if (response.ok && isWarehouse(body)) {
        setWarehouses((current) =>
          [...current, body].sort((left, right) =>
            left.code.localeCompare(right.code),
          ),
        );
        form.reset();
        setMessage("Warehouse created.");
      } else {
        const error = isRecord(body) ? (body as ApiError) : undefined;
        setMessage(error?.message ?? "Unable to create warehouse.");
      }
    } catch (error) {
      handleFailure(error);
    } finally {
      setBusy(false);
    }
  }

  function edit(warehouse: Warehouse, changes: Partial<Draft>) {
    setDrafts((current) => ({
      ...current,
      [warehouse.id]: {
        ...(current[warehouse.id] ?? {
          code: warehouse.code,
          name: warehouse.name,
          active: warehouse.active,
        }),
        ...changes,
      },
    }));
  }

  async function save(warehouse: Warehouse, draft: Draft) {
    setBusy(true);
    setMessage(undefined);
    try {
      const csrf = await fetchCsrf();
      const response = await fetch(`/api/v1/warehouses/${warehouse.id}`, {
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
      } else if (response.ok && isWarehouse(body)) {
        setWarehouses((current) =>
          current.map((item) => (item.id === body.id ? body : item)),
        );
        setDrafts((current) => {
          const next = { ...current };
          delete next[body.id];
          return next;
        });
        setMessage("Warehouse updated.");
      } else {
        const error = isRecord(body) ? (body as ApiError) : undefined;
        setMessage(error?.message ?? "Unable to update warehouse.");
      }
    } catch (error) {
      handleFailure(error);
    } finally {
      setBusy(false);
    }
  }

  const activeLocations = locations.filter((location) => location.active);

  return (
    <section className="management-panel" aria-labelledby="warehouses-heading">
      <h2 id="warehouses-heading">Warehouses</h2>
      {isAdmin && (
        <form className="management-form" onSubmit={createWarehouse}>
          <h3>Create warehouse</h3>
          <label>
            Warehouse code
            <input name="warehouseCode" required maxLength={50} />
          </label>
          <label>
            Warehouse name
            <input name="warehouseName" required maxLength={200} />
          </label>
          <label>
            Warehouse location
            <select name="warehouseLocationId" required defaultValue="">
              <option value="" disabled>
                Select a location
              </option>
              {activeLocations.map((location) => (
                <option key={location.id} value={location.id}>
                  {location.code} — {location.name}
                </option>
              ))}
            </select>
          </label>
          <button type="submit" disabled={busy || activeLocations.length === 0}>
            Create warehouse
          </button>
        </form>
      )}
      <div className="management-list" aria-live="polite">
        {loading && <p>Loading warehouses…</p>}
        {!loading && warehouses.length === 0 && (
          <p>No warehouses are available.</p>
        )}
        {warehouses.map((warehouse) => {
          const draft = drafts[warehouse.id] ?? warehouse;
          return (
            <article className="management-item" key={warehouse.id}>
              {isAdmin ? (
                <>
                  <p className="account-identifier">
                    Location: {warehouse.locationCode} —{" "}
                    {warehouse.locationName}
                  </p>
                  <label>
                    Code for {warehouse.code}
                    <input
                      value={draft.code}
                      maxLength={50}
                      onChange={(event) =>
                        edit(warehouse, { code: event.target.value })
                      }
                    />
                  </label>
                  <label>
                    Name for {warehouse.code}
                    <input
                      value={draft.name}
                      maxLength={200}
                      onChange={(event) =>
                        edit(warehouse, { name: event.target.value })
                      }
                    />
                  </label>
                  <label className="checkbox-label">
                    <input
                      type="checkbox"
                      checked={draft.active}
                      onChange={(event) =>
                        edit(warehouse, { active: event.target.checked })
                      }
                    />
                    Active
                  </label>
                  <button
                    type="button"
                    disabled={busy || !drafts[warehouse.id]}
                    onClick={() => save(warehouse, draft)}
                  >
                    Save {warehouse.code}
                  </button>
                </>
              ) : (
                <>
                  <h3>{warehouse.name}</h3>
                  <p>{warehouse.code}</p>
                  <p>
                    {warehouse.locationCode} — {warehouse.locationName}
                  </p>
                  <p>{warehouse.active ? "Active" : "Inactive"}</p>
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
