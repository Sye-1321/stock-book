# Stock Book

Stock Book is the foundation for a wholesale and distribution management system. This repository currently contains a Spring Boot API, a React frontend, and local PostgreSQL infrastructure. Business modules will be added as their requirements become concrete.

## Prerequisites

- Java 21
- Docker with Docker Compose
- Node.js 22 or newer
- pnpm 9.15.9 (Corepack can install it)

The Gradle wrapper is included, so a system Gradle installation is not required.

## Run locally

Start PostgreSQL:

```bash
docker compose up -d postgres
```

Start the backend from the repository root:

```bash
./gradlew :backend:bootRun
```

In a second terminal, start the frontend:

```bash
cd frontend
pnpm install
pnpm dev
```

Open `http://localhost:5173`. Vite proxies relative `/api` requests to the backend. Database settings default to the Compose values and can be overridden with `DB_URL`, `DB_USER`, and `DB_PASSWORD`.

The connectivity endpoint is `GET /api/v1/status`; it returns 200 only when PostgreSQL responds. The independent process liveness endpoint is `GET /api/v1/liveness`.

## Tests and checks

Backend tests use Testcontainers and therefore require a running Docker daemon:

```bash
./gradlew :backend:check
```

Run the frontend checks:

```bash
cd frontend
pnpm format
pnpm test
pnpm build
```

CI runs the same backend and frontend checks on every push and pull request.
