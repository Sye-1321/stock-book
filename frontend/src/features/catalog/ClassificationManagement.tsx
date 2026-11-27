import { FormEvent, useCallback, useEffect, useState } from "react";
import { ApiError, isRecord, readJson } from "../../app/api";
import { CsrfRequestError, fetchCsrf } from "../auth/csrf";
import { Classification, isClassification } from "./types";

const groups = ["categories", "brands", "families"] as const;
type Group = (typeof groups)[number];

type Props = {
  isAdmin: boolean;
  onSessionExpired: (message?: string) => void;
  onChanged: () => void;
};

export default function ClassificationManagement({
  isAdmin,
  onSessionExpired,
  onChanged,
}: Props) {
  const [values, setValues] = useState<Record<Group, Classification[]>>({
    categories: [],
    brands: [],
    families: [],
  });
  const [loaded, setLoaded] = useState(false);
  const [message, setMessage] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [drafts, setDrafts] = useState<
    Record<string, Pick<Classification, "code" | "name" | "active">>
  >({});

  const load = useCallback(async () => {
    setLoaded(false);
    try {
      const responses = await Promise.all(
        groups.map((group) =>
          fetch(`/api/v1/catalog/${group}`, {
            headers: { Accept: "application/json" },
          }),
        ),
      );
      if (responses.some((response) => response.status === 401)) {
        onSessionExpired();
        return;
      }
      const bodies = await Promise.all(responses.map(readJson));
      if (
        responses.some((response) => !response.ok) ||
        bodies.some(
          (body) => !Array.isArray(body) || !body.every(isClassification),
        )
      ) {
        setMessage("Unable to load product classifications.");
        return;
      }
      setValues({
        categories: bodies[0] as Classification[],
        brands: bodies[1] as Classification[],
        families: bodies[2] as Classification[],
      });
      setLoaded(true);
    } catch {
      setMessage("Product classification service unavailable.");
    }
  }, [onSessionExpired]);

  useEffect(() => void load(), [load]);

  function edit(
    value: Classification,
    changes: Partial<Pick<Classification, "code" | "name" | "active">>,
  ) {
    setDrafts((current) => {
      const draft = {
        ...(current[value.id] ?? {
          code: value.code,
          name: value.name,
          active: value.active,
        }),
        ...changes,
      };
      const next = { ...current };
      if (
        draft.code === value.code &&
        draft.name === value.name &&
        draft.active === value.active
      )
        delete next[value.id];
      else next[value.id] = draft;
      return next;
    });
  }

  async function create(event: FormEvent<HTMLFormElement>, group: Group) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setBusy(true);
    setMessage(undefined);
    try {
      const csrf = await fetchCsrf();
      const response = await fetch(`/api/v1/catalog/${group}`, {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          [csrf.headerName]: csrf.token,
        },
        body: JSON.stringify({
          code: data.get("code"),
          name: data.get("name"),
        }),
      });
      const body = await readJson(response);
      if (response.status === 401) onSessionExpired();
      else if (response.ok && isClassification(body)) {
        setValues((current) => ({
          ...current,
          [group]: [...current[group], body].sort((a, b) =>
            a.code.localeCompare(b.code),
          ),
        }));
        form.reset();
        setMessage("Classification created.");
        onChanged();
      } else {
        setMessage(
          (isRecord(body) ? (body as ApiError).message : undefined) ??
            "Unable to create classification.",
        );
      }
    } catch (error) {
      if (error instanceof CsrfRequestError && error.status === 401)
        onSessionExpired();
      else setMessage("Product classification service unavailable.");
    } finally {
      setBusy(false);
    }
  }

  async function save(group: Group, value: Classification) {
    const draft = drafts[value.id];
    if (!draft) return;
    setBusy(true);
    setMessage(undefined);
    try {
      const csrf = await fetchCsrf();
      const response = await fetch(`/api/v1/catalog/${group}/${value.id}`, {
        method: "PUT",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          [csrf.headerName]: csrf.token,
        },
        body: JSON.stringify({ ...draft, expectedUpdatedAt: value.updatedAt }),
      });
      const body = await readJson(response);
      if (response.status === 401) onSessionExpired();
      else if (response.ok && isClassification(body)) {
        setValues((current) => ({
          ...current,
          [group]: current[group].map((item) =>
            item.id === body.id ? body : item,
          ),
        }));
        setDrafts((current) => {
          const next = { ...current };
          delete next[value.id];
          return next;
        });
        setMessage("Classification updated.");
        onChanged();
      } else {
        setMessage(
          (isRecord(body) ? (body as ApiError).message : undefined) ??
            "Unable to update classification.",
        );
      }
    } catch (error) {
      if (error instanceof CsrfRequestError && error.status === 401)
        onSessionExpired();
      else setMessage("Product classification service unavailable.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section
      className="management-panel"
      aria-labelledby="classifications-heading"
    >
      <h2 id="classifications-heading">Product classifications</h2>
      {!loaded && !message && <p>Loading classifications…</p>}
      {groups.map((group) => (
        <div key={group}>
          <h3>{group[0].toUpperCase() + group.slice(1)}</h3>
          {loaded && values[group].length === 0 && (
            <p>No {group} are available.</p>
          )}
          {values[group].map((value) => {
            const draft = drafts[value.id] ?? value;
            return isAdmin ? (
              <div className="management-item" key={value.id}>
                <label>
                  Code for {value.code}
                  <input
                    value={draft.code}
                    onChange={(event) =>
                      edit(value, { code: event.target.value })
                    }
                  />
                </label>
                <label>
                  Name for {value.code}
                  <input
                    value={draft.name}
                    onChange={(event) =>
                      edit(value, { name: event.target.value })
                    }
                  />
                </label>
                <label className="checkbox-label">
                  <input
                    type="checkbox"
                    checked={draft.active}
                    onChange={(event) =>
                      edit(value, { active: event.target.checked })
                    }
                  />
                  Active
                </label>
                {drafts[value.id] && <p>Unsaved changes</p>}
                <button
                  type="button"
                  disabled={busy || !drafts[value.id]}
                  onClick={() => void save(group, value)}
                >
                  Save {value.code}
                </button>
              </div>
            ) : (
              <p key={value.id}>
                {value.code} — {value.name} (
                {value.active ? "Active" : "Inactive"})
              </p>
            );
          })}
          {isAdmin && (
            <form
              className="management-form"
              onSubmit={(event) => create(event, group)}
            >
              <label>
                {group === "categories"
                  ? "Category"
                  : group === "families"
                    ? "Family"
                    : "Brand"}{" "}
                code
                <input name="code" required maxLength={50} />
              </label>
              <label>
                {group === "categories"
                  ? "Category"
                  : group === "families"
                    ? "Family"
                    : "Brand"}{" "}
                name
                <input name="name" required maxLength={200} />
              </label>
              <button disabled={busy} type="submit">
                Create{" "}
                {group === "categories"
                  ? "category"
                  : group === "families"
                    ? "family"
                    : "brand"}
              </button>
            </form>
          )}
        </div>
      ))}
      {message && <p role="status">{message}</p>}
    </section>
  );
}
