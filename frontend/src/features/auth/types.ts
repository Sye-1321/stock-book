import { isRecord } from "../../app/api";

export type Role = "ADMIN" | "STAFF";

export type User = {
  id: string;
  loginIdentifier: string;
  displayName: string;
  role: Role;
};

export type AuthState =
  | { kind: "loading" }
  | { kind: "anonymous" }
  | { kind: "authenticated"; user: User }
  | { kind: "unavailable"; message: string };

export function isUser(value: unknown): value is User {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    typeof value.loginIdentifier === "string" &&
    typeof value.displayName === "string" &&
    ["ADMIN", "STAFF"].includes(String(value.role))
  );
}
