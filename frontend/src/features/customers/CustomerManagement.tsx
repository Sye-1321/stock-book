import { FormEvent, useCallback, useEffect, useState } from "react";
import { ApiError, isRecord, readJson } from "../../app/api";
import { CsrfRequestError, fetchCsrf } from "../auth/csrf";

export type Customer = {
  id: string;
  partyId: string;
  code: string;
  name: string;
  phone: string | null;
  email: string | null;
  paymentTermsDays: number;
  active: boolean;
  partyActive: boolean;
  partyUpdatedAt: string;
  createdAt: string;
  updatedAt: string;
};

type Party = {
  id: string;
  name: string;
  phone: string | null;
  email: string | null;
  active: boolean;
  supplier: boolean;
  customer: boolean;
  createdAt: string;
  updatedAt: string;
};

type CustomerDraft = Omit<
  Customer,
  | "id"
  | "partyId"
  | "partyUpdatedAt"
  | "createdAt"
  | "updatedAt"
  | "paymentTermsDays"
> & { paymentTermsDays: string };

type Props = {
  isAdmin: boolean;
  onSessionExpired: (message?: string) => void;
};

function isCustomer(value: unknown): value is Customer {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    typeof value.partyId === "string" &&
    typeof value.code === "string" &&
    typeof value.name === "string" &&
    (typeof value.phone === "string" || value.phone === null) &&
    (typeof value.email === "string" || value.email === null) &&
    typeof value.paymentTermsDays === "number" &&
    typeof value.active === "boolean" &&
    typeof value.partyActive === "boolean" &&
    typeof value.partyUpdatedAt === "string" &&
    typeof value.createdAt === "string" &&
    typeof value.updatedAt === "string"
  );
}

function isParty(value: unknown): value is Party {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    typeof value.name === "string" &&
    (typeof value.phone === "string" || value.phone === null) &&
    (typeof value.email === "string" || value.email === null) &&
    typeof value.active === "boolean" &&
    typeof value.supplier === "boolean" &&
    typeof value.customer === "boolean" &&
    typeof value.createdAt === "string" &&
    typeof value.updatedAt === "string"
  );
}

function draftFor(customer: Customer): CustomerDraft {
  return {
    code: customer.code,
    name: customer.name,
    phone: customer.phone,
    email: customer.email,
    paymentTermsDays: String(customer.paymentTermsDays),
    active: customer.active,
    partyActive: customer.partyActive,
  };
}

function modified(customer: Customer, draft: CustomerDraft | undefined) {
  return (
    draft !== undefined &&
    (draft.code !== customer.code ||
      draft.name !== customer.name ||
      draft.phone !== customer.phone ||
      draft.email !== customer.email ||
      draft.paymentTermsDays !== String(customer.paymentTermsDays) ||
      draft.active !== customer.active ||
      draft.partyActive !== customer.partyActive)
  );
}

function sharedModified(customer: Customer, draft: CustomerDraft) {
  return (
    draft.name !== customer.name ||
    draft.phone !== customer.phone ||
    draft.email !== customer.email ||
    draft.partyActive !== customer.partyActive
  );
}

export default function CustomerManagement({
  isAdmin,
  onSessionExpired,
}: Props) {
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [parties, setParties] = useState<Party[]>([]);
  const [partyQuery, setPartyQuery] = useState("");
  const [partySearchState, setPartySearchState] = useState<
    "idle" | "loading" | "loaded" | "failed"
  >("idle");
  const [drafts, setDrafts] = useState<Record<string, CustomerDraft>>({});
  const [linkExisting, setLinkExisting] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string>();

  const handleFailure = useCallback(
    (error: unknown) => {
      if (error instanceof CsrfRequestError && error.status === 401)
        onSessionExpired();
      else setMessage("Customer service unavailable.");
    },
    [onSessionExpired],
  );

  useEffect(() => {
    const controller = new AbortController();
    void (async () => {
      try {
        const response = await fetch("/api/v1/customers", {
          headers: { Accept: "application/json" },
          signal: controller.signal,
        });
        if (response.status === 401) {
          onSessionExpired();
          return;
        }
        const customerBody = await readJson(response);
        if (
          response.ok &&
          Array.isArray(customerBody) &&
          customerBody.every(isCustomer)
        ) {
          setCustomers(customerBody);
          setLoaded(true);
        } else setMessage("Unable to load customers.");
      } catch (error) {
        if (!(error instanceof DOMException && error.name === "AbortError"))
          setMessage("Customer service unavailable.");
      } finally {
        setLoading(false);
      }
    })();
    return () => controller.abort();
  }, [onSessionExpired]);

  async function searchParties() {
    setPartySearchState("loading");
    setParties([]);
    try {
      const response = await fetch(
        `/api/v1/parties?query=${encodeURIComponent(partyQuery.trim())}`,
        { headers: { Accept: "application/json" } },
      );
      if (response.status === 401) {
        onSessionExpired();
        return;
      }
      const body = await readJson(response);
      if (response.ok && Array.isArray(body) && body.every(isParty)) {
        setParties(body);
        setPartySearchState("loaded");
      } else setPartySearchState("failed");
    } catch {
      setPartySearchState("failed");
    }
  }

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setBusy(true);
    setMessage(undefined);
    try {
      const csrf = await fetchCsrf();
      const response = await fetch("/api/v1/customers", {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          [csrf.headerName]: csrf.token,
        },
        body: JSON.stringify({
          partyId: linkExisting ? data.get("customerPartyId") : null,
          code: data.get("customerCode"),
          name: linkExisting ? null : data.get("customerName"),
          phone: linkExisting ? null : data.get("customerPhone") || null,
          email: linkExisting ? null : data.get("customerEmail") || null,
          paymentTermsDays: Number(data.get("customerPaymentTermsDays")),
        }),
      });
      const body = await readJson(response);
      if (response.status === 401) onSessionExpired();
      else if (response.ok && isCustomer(body)) {
        setCustomers((current) =>
          [...current, body].sort((left, right) =>
            left.code.localeCompare(right.code),
          ),
        );
        setParties((current) =>
          current.map((party) =>
            party.id === body.partyId ? { ...party, customer: true } : party,
          ),
        );
        form.reset();
        setLinkExisting(false);
        setMessage("Customer created.");
      } else {
        const error = isRecord(body) ? (body as ApiError) : undefined;
        setMessage(error?.message ?? "Unable to create customer.");
      }
    } catch (error) {
      handleFailure(error);
    } finally {
      setBusy(false);
    }
  }

  function edit(customer: Customer, changes: Partial<CustomerDraft>) {
    setDrafts((current) => {
      const draft = {
        ...(current[customer.id] ?? draftFor(customer)),
        ...changes,
      };
      const next = { ...current };
      if (modified(customer, draft)) next[customer.id] = draft;
      else delete next[customer.id];
      return next;
    });
  }

  async function save(customer: Customer, draft: CustomerDraft) {
    setBusy(true);
    setMessage(undefined);
    try {
      const csrf = await fetchCsrf();
      const response = await fetch(`/api/v1/customers/${customer.id}`, {
        method: "PUT",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          [csrf.headerName]: csrf.token,
        },
        body: JSON.stringify({
          ...draft,
          paymentTermsDays: Number(draft.paymentTermsDays),
          updateParty: sharedModified(customer, draft),
          partyUpdatedAt: customer.partyUpdatedAt,
        }),
      });
      const body = await readJson(response);
      if (response.status === 401) onSessionExpired();
      else if (response.ok && isCustomer(body)) {
        setCustomers((current) =>
          current.map((item) => (item.id === body.id ? body : item)),
        );
        setDrafts((current) => {
          const next = { ...current };
          delete next[body.id];
          return next;
        });
        setMessage("Customer updated.");
      } else {
        const error = isRecord(body) ? (body as ApiError) : undefined;
        setMessage(error?.message ?? "Unable to update customer.");
      }
    } catch (error) {
      handleFailure(error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="management-panel" aria-labelledby="customers-heading">
      <h2 id="customers-heading">Customers</h2>
      {isAdmin && (
        <form className="management-form" onSubmit={create}>
          <h3>Create customer</h3>
          <label className="checkbox-label">
            <input
              type="checkbox"
              checked={linkExisting}
              onChange={(event) => {
                setLinkExisting(event.target.checked);
                setParties([]);
                setPartySearchState("idle");
              }}
            />
            Link an existing party
          </label>
          {linkExisting ? (
            <>
              <label>
                Find existing party
                <input
                  value={partyQuery}
                  onChange={(event) => setPartyQuery(event.target.value)}
                  maxLength={200}
                />
              </label>
              <button
                type="button"
                disabled={partySearchState === "loading"}
                onClick={() => void searchParties()}
              >
                Search parties
              </button>
              {partySearchState === "loading" && <p>Loading parties…</p>}
              {partySearchState === "failed" && (
                <p role="status">Unable to search parties.</p>
              )}
              {partySearchState === "loaded" &&
                parties.filter((party) => !party.customer).length === 0 && (
                  <p>No matching parties are available.</p>
                )}
              {parties.some((party) => !party.customer) && (
                <label>
                  Existing party
                  <select name="customerPartyId" required defaultValue="">
                    <option value="">Select a party</option>
                    {parties
                      .filter((party) => !party.customer)
                      .map((party) => (
                        <option key={party.id} value={party.id}>
                          {party.name}
                        </option>
                      ))}
                  </select>
                </label>
              )}
            </>
          ) : (
            <>
              <label>
                Customer name
                <input name="customerName" required maxLength={200} />
              </label>
              <label>
                Customer phone
                <input name="customerPhone" maxLength={100} />
              </label>
              <label>
                Customer email
                <input name="customerEmail" type="email" maxLength={254} />
              </label>
            </>
          )}
          <label>
            Customer code
            <input name="customerCode" required maxLength={50} />
          </label>
          <label>
            Payment terms (days)
            <input
              name="customerPaymentTermsDays"
              type="number"
              required
              min={0}
              max={3650}
              defaultValue={0}
            />
          </label>
          <button
            type="submit"
            disabled={
              busy ||
              (linkExisting && !parties.some((party) => !party.customer))
            }
          >
            Create customer
          </button>
        </form>
      )}
      <div className="management-list" aria-live="polite">
        {loading && <p>Loading customers…</p>}
        {!loading && loaded && customers.length === 0 && (
          <p>No customers are available.</p>
        )}
        {customers.map((customer) => {
          const draft = drafts[customer.id] ?? draftFor(customer);
          const changed = modified(customer, drafts[customer.id]);
          return (
            <article className="management-item" key={customer.id}>
              {isAdmin ? (
                <>
                  <label>
                    Code for {customer.code}
                    <input
                      value={draft.code}
                      maxLength={50}
                      onChange={(event) =>
                        edit(customer, { code: event.target.value })
                      }
                    />
                  </label>
                  <label>
                    Name for {customer.code}
                    <input
                      value={draft.name}
                      maxLength={200}
                      onChange={(event) =>
                        edit(customer, { name: event.target.value })
                      }
                    />
                  </label>
                  <label>
                    Phone for {customer.code}
                    <input
                      value={draft.phone ?? ""}
                      maxLength={100}
                      onChange={(event) =>
                        edit(customer, { phone: event.target.value || null })
                      }
                    />
                  </label>
                  <label>
                    Email for {customer.code}
                    <input
                      value={draft.email ?? ""}
                      type="email"
                      maxLength={254}
                      onChange={(event) =>
                        edit(customer, { email: event.target.value || null })
                      }
                    />
                  </label>
                  <label>
                    Payment terms for {customer.code}
                    <input
                      type="number"
                      min={0}
                      max={3650}
                      value={draft.paymentTermsDays}
                      onChange={(event) =>
                        edit(customer, { paymentTermsDays: event.target.value })
                      }
                    />
                  </label>
                  <label className="checkbox-label">
                    <input
                      type="checkbox"
                      checked={draft.active}
                      onChange={(event) =>
                        edit(customer, { active: event.target.checked })
                      }
                    />
                    Customer profile active
                  </label>
                  <label className="checkbox-label">
                    <input
                      type="checkbox"
                      checked={draft.partyActive}
                      onChange={(event) =>
                        edit(customer, { partyActive: event.target.checked })
                      }
                    />
                    Shared party active
                  </label>
                  {changed && <p>Unsaved changes</p>}
                  <button
                    type="button"
                    disabled={busy || !changed}
                    onClick={() => save(customer, draft)}
                  >
                    Save {customer.code}
                  </button>
                </>
              ) : (
                <>
                  <h3>{customer.name}</h3>
                  <p>{customer.code}</p>
                  <p>Payment terms: {customer.paymentTermsDays} days</p>
                  <p>{customer.active ? "Active" : "Inactive"}</p>
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
