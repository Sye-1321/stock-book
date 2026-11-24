import { isRecord, readJson } from "../../app/api";

export type Csrf = { headerName: string; token: string };

export class CsrfRequestError extends Error {
  constructor(readonly status: number) {
    super(`CSRF request failed with status ${status}`);
  }
}

export async function fetchCsrf(): Promise<Csrf> {
  const response = await fetch("/api/v1/auth/csrf", {
    headers: { Accept: "application/json" },
  });
  const body = await readJson(response);
  if (!response.ok) {
    throw new CsrfRequestError(response.status);
  }
  if (
    !isRecord(body) ||
    typeof body.headerName !== "string" ||
    typeof body.token !== "string"
  ) {
    throw new Error("Unable to prepare a secure request");
  }
  return { headerName: body.headerName, token: body.token };
}
