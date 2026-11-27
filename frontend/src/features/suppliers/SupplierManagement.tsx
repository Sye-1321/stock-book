import { FormEvent, useCallback, useEffect, useState } from "react";
import { ApiError, isRecord, readJson } from "../../app/api";
import { CsrfRequestError, fetchCsrf } from "../auth/csrf";

export type Supplier = {
  id: string;
  partyId?: string;
  code: string;
  name: string;
  phone: string | null;
  email: string | null;
  active: boolean;
  partyActive?: boolean;
  partyUpdatedAt?: string;
  createdAt: string;
  updatedAt: string;
};

type SupplierDraft = Pick<
  Supplier,
  "code" | "name" | "phone" | "email" | "active"
> & { partyActive: boolean };

type Props = {
  isAdmin: boolean;
  onSessionExpired: (message?: string) => void;
  onSuppliersChanged: () => void;
};

export function isSupplier(value: unknown): value is Supplier {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    typeof value.code === "string" &&
    typeof value.name === "string" &&
    (typeof value.phone === "string" || value.phone === null) &&
    (typeof value.email === "string" || value.email === null) &&
    typeof value.active === "boolean" &&
    typeof value.createdAt === "string" &&
    typeof value.updatedAt === "string"
  );
}

function modified(supplier: Supplier, draft: SupplierDraft | undefined) {
  return (
    draft !== undefined &&
    (draft.code !== supplier.code ||
      draft.name !== supplier.name ||
      draft.phone !== supplier.phone ||
      draft.email !== supplier.email ||
      draft.active !== supplier.active ||
      draft.partyActive !== (supplier.partyActive ?? true))
  );
}

function sharedModified(supplier: Supplier, draft: SupplierDraft) {
  return (
    draft.name !== supplier.name ||
    draft.phone !== supplier.phone ||
    draft.email !== supplier.email ||
    draft.partyActive !== (supplier.partyActive ?? true)
  );
}

export default function SupplierManagement({
  isAdmin,
  onSessionExpired,
  onSuppliersChanged,
}: Props) {
  const [suppliers, setSuppliers] = useState<Supplier[]>([]);
  const [drafts, setDrafts] = useState<Record<string, SupplierDraft>>({});
  const [loading, setLoading] = useState(true);
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string>();

  const handleFailure = useCallback(
    (error: unknown) => {
      if (error instanceof CsrfRequestError && error.status === 401)
        onSessionExpired();
      else setMessage("Supplier service unavailable.");
    },
    [onSessionExpired],
  );

  useEffect(() => {
    const controller = new AbortController();
    void (async () => {
      try {
        const response = await fetch("/api/v1/suppliers", {
          headers: { Accept: "application/json" },
          signal: controller.signal,
        });
        if (response.status === 401) {
          onSessionExpired();
          return;
        }
        const body = await readJson(response);
        if (response.ok && Array.isArray(body) && body.every(isSupplier)) {
          setSuppliers(body);
          setLoaded(true);
        } else setMessage("Unable to load suppliers.");
      } catch (error) {
        if (!(error instanceof DOMException && error.name === "AbortError"))
          setMessage("Supplier service unavailable.");
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
      const response = await fetch("/api/v1/suppliers", {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          [csrf.headerName]: csrf.token,
        },
        body: JSON.stringify({
          code: data.get("supplierCode"),
          name: data.get("supplierName"),
          phone: data.get("supplierPhone") || null,
          email: data.get("supplierEmail") || null,
        }),
      });
      const body = await readJson(response);
      if (response.status === 401) onSessionExpired();
      else if (response.ok && isSupplier(body)) {
        setSuppliers((current) =>
          [...current, body].sort((left, right) =>
            left.code.localeCompare(right.code),
          ),
        );
        form.reset();
        setMessage("Supplier created.");
        onSuppliersChanged();
      } else {
        const error = isRecord(body) ? (body as ApiError) : undefined;
        setMessage(error?.message ?? "Unable to create supplier.");
      }
    } catch (error) {
      handleFailure(error);
    } finally {
      setBusy(false);
    }
  }

  function edit(supplier: Supplier, changes: Partial<SupplierDraft>) {
    setDrafts((current) => {
      const draft = {
        ...(current[supplier.id] ?? {
          code: supplier.code,
          name: supplier.name,
          phone: supplier.phone,
          email: supplier.email,
          active: supplier.active,
          partyActive: supplier.partyActive ?? true,
        }),
        ...changes,
      };
      const next = { ...current };
      if (modified(supplier, draft)) next[supplier.id] = draft;
      else delete next[supplier.id];
      return next;
    });
  }

  async function save(supplier: Supplier, draft: SupplierDraft) {
    setBusy(true);
    setMessage(undefined);
    try {
      const csrf = await fetchCsrf();
      const response = await fetch(`/api/v1/suppliers/${supplier.id}`, {
        method: "PUT",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          [csrf.headerName]: csrf.token,
        },
        body: JSON.stringify({
          ...draft,
          updateParty: sharedModified(supplier, draft),
          partyUpdatedAt: supplier.partyUpdatedAt,
        }),
      });
      const body = await readJson(response);
      if (response.status === 401) onSessionExpired();
      else if (response.ok && isSupplier(body)) {
        setSuppliers((current) =>
          current.map((item) => (item.id === body.id ? body : item)),
        );
        setDrafts((current) => {
          const next = { ...current };
          delete next[body.id];
          return next;
        });
        setMessage("Supplier updated.");
        onSuppliersChanged();
      } else {
        const error = isRecord(body) ? (body as ApiError) : undefined;
        setMessage(error?.message ?? "Unable to update supplier.");
      }
    } catch (error) {
      handleFailure(error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="management-panel" aria-labelledby="suppliers-heading">
      <h2 id="suppliers-heading">Suppliers</h2>
      {isAdmin && (
        <form className="management-form" onSubmit={create}>
          <h3>Create supplier</h3>
          <label>
            Supplier code
            <input name="supplierCode" required maxLength={50} />
          </label>
          <label>
            Supplier name
            <input name="supplierName" required maxLength={200} />
          </label>
          <label>
            Supplier phone
            <input name="supplierPhone" maxLength={100} />
          </label>
          <label>
            Supplier email
            <input name="supplierEmail" type="email" maxLength={254} />
          </label>
          <button type="submit" disabled={busy}>
            Create supplier
          </button>
        </form>
      )}
      <div className="management-list" aria-live="polite">
        {loading && <p>Loading suppliers…</p>}
        {!loading && loaded && suppliers.length === 0 && (
          <p>No suppliers are available.</p>
        )}
        {suppliers.map((supplier) => {
          const draft = drafts[supplier.id] ?? supplier;
          const changed = modified(supplier, drafts[supplier.id]);
          return (
            <article className="management-item" key={supplier.id}>
              {isAdmin ? (
                <>
                  <label>
                    Code for {supplier.code}
                    <input
                      value={draft.code}
                      maxLength={50}
                      onChange={(event) =>
                        edit(supplier, { code: event.target.value })
                      }
                    />
                  </label>
                  <label>
                    Name for {supplier.code}
                    <input
                      value={draft.name}
                      maxLength={200}
                      onChange={(event) =>
                        edit(supplier, { name: event.target.value })
                      }
                    />
                  </label>
                  <label>
                    Phone for {supplier.code}
                    <input
                      value={draft.phone ?? ""}
                      maxLength={100}
                      onChange={(event) =>
                        edit(supplier, { phone: event.target.value || null })
                      }
                    />
                  </label>
                  <label>
                    Email for {supplier.code}
                    <input
                      value={draft.email ?? ""}
                      type="email"
                      maxLength={254}
                      onChange={(event) =>
                        edit(supplier, { email: event.target.value || null })
                      }
                    />
                  </label>
                  <label className="checkbox-label">
                    <input
                      type="checkbox"
                      checked={draft.active}
                      onChange={(event) =>
                        edit(supplier, { active: event.target.checked })
                      }
                    />
                    Active
                  </label>
                  <label className="checkbox-label">
                    <input
                      type="checkbox"
                      checked={draft.partyActive}
                      onChange={(event) =>
                        edit(supplier, { partyActive: event.target.checked })
                      }
                    />
                    Shared party active
                  </label>
                  {changed && <p>Unsaved changes</p>}
                  <button
                    type="button"
                    disabled={busy || !changed}
                    onClick={() => save(supplier, draft)}
                  >
                    Save {supplier.code}
                  </button>
                </>
              ) : (
                <>
                  <h3>{supplier.name}</h3>
                  <p>{supplier.code}</p>
                  <p>{supplier.active ? "Active" : "Inactive"}</p>
                  {supplier.partyActive === false && (
                    <p>Shared party inactive</p>
                  )}
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
