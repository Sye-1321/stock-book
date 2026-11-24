import { FormEvent, useEffect, useState } from "react";
import { ApiError, isRecord, readJson } from "../../app/api";
import { CsrfRequestError, fetchCsrf } from "../auth/csrf";
import { isUser, Role, User } from "../auth/types";

type StaffAccount = User & { active: boolean };
type AccountDraft = Pick<StaffAccount, "displayName" | "role" | "active">;

type Props = {
  currentUser: User;
  onSessionExpired: (message?: string) => void;
};

function isStaffAccount(value: unknown): value is StaffAccount {
  if (!isRecord(value) || typeof value.active !== "boolean") return false;
  return isUser(value);
}

export default function StaffManagement({
  currentUser,
  onSessionExpired,
}: Props) {
  const [accounts, setAccounts] = useState<StaffAccount[]>([]);
  const [drafts, setDrafts] = useState<Record<string, AccountDraft>>({});
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string>();

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    void (async () => {
      try {
        const response = await fetch("/api/v1/staff", {
          headers: { Accept: "application/json" },
          signal: controller.signal,
        });
        if (response.status === 401) {
          onSessionExpired();
          return;
        }
        const body = await readJson(response);
        if (response.ok && Array.isArray(body) && body.every(isStaffAccount)) {
          setAccounts(body);
          setDrafts({});
          setMessage(undefined);
        } else {
          setMessage(
            response.status === 403
              ? "Administrator access is required."
              : "Unable to load staff accounts.",
          );
        }
      } catch (error) {
        if (error instanceof DOMException && error.name === "AbortError")
          return;
        setMessage("Staff management service unavailable.");
      } finally {
        setLoading(false);
      }
    })();
    return () => controller.abort();
  }, [onSessionExpired]);

  async function createAccount(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setBusy(true);
    setMessage(undefined);
    try {
      const csrf = await fetchCsrf();
      const response = await fetch("/api/v1/staff", {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          [csrf.headerName]: csrf.token,
        },
        body: JSON.stringify({
          loginIdentifier: data.get("staffLoginIdentifier"),
          displayName: data.get("staffDisplayName"),
          initialPassword: data.get("staffInitialPassword"),
          role: data.get("staffRole"),
        }),
      });
      const body = await readJson(response);
      if (response.status === 401) {
        onSessionExpired();
      } else if (response.ok && isStaffAccount(body)) {
        setAccounts((current) =>
          [...current, body].sort((left, right) =>
            left.displayName.localeCompare(right.displayName),
          ),
        );
        setMessage("Staff account created.");
        form.reset();
      } else {
        const error = isRecord(body) ? (body as ApiError) : undefined;
        setMessage(error?.message ?? "Unable to create staff account.");
      }
    } catch (error) {
      handleRequestFailure(error);
    } finally {
      setBusy(false);
    }
  }

  function editAccount(
    id: string,
    changes: Partial<Pick<StaffAccount, "displayName" | "role" | "active">>,
  ) {
    const account = accounts.find((candidate) => candidate.id === id);
    if (!account) return;
    setDrafts((current) => ({
      ...current,
      [id]: {
        ...(current[id] ?? {
          displayName: account.displayName,
          role: account.role,
          active: account.active,
        }),
        ...changes,
      },
    }));
  }

  async function saveAccount(account: StaffAccount, draft: AccountDraft) {
    setBusy(true);
    setMessage(undefined);
    try {
      const csrf = await fetchCsrf();
      const response = await fetch(`/api/v1/staff/${account.id}`, {
        method: "PUT",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          [csrf.headerName]: csrf.token,
        },
        body: JSON.stringify({
          displayName: draft.displayName,
          role: draft.role,
          active: draft.active,
        }),
      });
      const body = await readJson(response);
      if (response.status === 401) {
        onSessionExpired();
      } else if (response.ok && isStaffAccount(body)) {
        setAccounts((current) =>
          current.map((existing) =>
            existing.id === body.id ? body : existing,
          ),
        );
        setDrafts((current) => {
          const next = { ...current };
          delete next[body.id];
          return next;
        });
        if (
          body.id === currentUser.id &&
          (!body.active || body.role !== "ADMIN")
        ) {
          onSessionExpired(
            "Your access changed. Sign in again to continue with your updated account.",
          );
        } else {
          setMessage("Staff account updated.");
        }
      } else {
        const error = isRecord(body) ? (body as ApiError) : undefined;
        setMessage(error?.message ?? "Unable to update staff account.");
      }
    } catch (error) {
      handleRequestFailure(error);
    } finally {
      setBusy(false);
    }
  }

  function handleRequestFailure(error: unknown) {
    if (error instanceof CsrfRequestError && error.status === 401) {
      onSessionExpired();
    } else {
      setMessage("Staff management service unavailable.");
    }
  }

  return (
    <section className="staff-panel" aria-labelledby="staff-heading">
      <h2 id="staff-heading">Staff management</h2>
      <form className="staff-create-form" onSubmit={createAccount}>
        <h3>Create account</h3>
        <label>
          Login identifier
          <input name="staffLoginIdentifier" required maxLength={190} />
        </label>
        <label>
          Display name
          <input name="staffDisplayName" required maxLength={200} />
        </label>
        <label>
          Initial password
          <input
            name="staffInitialPassword"
            type="password"
            minLength={12}
            maxLength={128}
            required
          />
        </label>
        <label>
          Role
          <select name="staffRole" defaultValue="STAFF">
            <option value="STAFF">Staff</option>
            <option value="ADMIN">Administrator</option>
          </select>
        </label>
        <button type="submit" disabled={busy}>
          Create staff account
        </button>
      </form>

      <div className="staff-list" aria-live="polite">
        <h3>Accounts</h3>
        {loading && <p>Loading staff accounts…</p>}
        {!loading && accounts.length === 0 && (
          <p>No staff accounts are available.</p>
        )}
        {accounts.map((account) => {
          const draft = drafts[account.id] ?? account;
          return (
            <div className="staff-account" key={account.id}>
              <p className="account-identifier">{account.loginIdentifier}</p>
              <label>
                Display name for {account.loginIdentifier}
                <input
                  value={draft.displayName}
                  maxLength={200}
                  onChange={(event) =>
                    editAccount(account.id, {
                      displayName: event.target.value,
                    })
                  }
                />
              </label>
              <label>
                Role for {account.loginIdentifier}
                <select
                  value={draft.role}
                  onChange={(event) =>
                    editAccount(account.id, {
                      role: event.target.value as Role,
                    })
                  }
                >
                  <option value="STAFF">Staff</option>
                  <option value="ADMIN">Administrator</option>
                </select>
              </label>
              <label className="checkbox-label">
                <input
                  type="checkbox"
                  checked={draft.active}
                  onChange={(event) =>
                    editAccount(account.id, { active: event.target.checked })
                  }
                />
                Active
              </label>
              {drafts[account.id] && <p>Unsaved changes</p>}
              <button
                type="button"
                disabled={busy}
                onClick={() => saveAccount(account, draft)}
              >
                Save {account.loginIdentifier}
              </button>
            </div>
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
