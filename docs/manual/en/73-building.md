\newpage

# Building

## Development workflow

[**cisys**][cisys] uses [Quarkus][quarkus] for the backend and a [Vite][vite] dev server for the React frontend. They are started separately.

Start the support services from the repository root

```sh
docker compose up -d
```

then the backend

```sh
mvn quarkus:dev
```

and, in a second terminal, the frontend

```sh
cd src/frontend
npm install
npm run dev
```

Backend changes are handled by Quarkus dev mode and frontend changes are reloaded by Vite.

Use

```sh
mvn clean quarkus:dev
```

only when you need to clear `target/` and force a fresh rebuild.

## Tests and checks

```sh
mvn test
cd src/frontend
npm run lint
npm run build
```

The backend tests use an in-memory H2 database and do not need Keycloak or PostgreSQL. The Husky `pre-commit` hook runs ESLint on staged frontend files, and the `pre-push` hook runs `npm run lint` and `mvn -B test`. Bypass a hook once with `--no-verify`.

## Production build

```sh
mvn package
cd src/frontend
npm run build
```

The backend is packaged in `target/`; the frontend is written to `src/frontend/dist`. The backend does not serve the frontend. Production deployment (TLS, secrets, hardened Keycloak, migrations) is not supported yet.

## Makefile

The targets are

* `all` - Clean, lint and run (Default)
* `setup` - Install frontend dependencies and the Git hooks
* `clean` - Clean
* `lint` - Run the frontend ESLint check
* `build` - Build the frontend
* `run` - Run the backend in development mode
* `test` - Run the backend test cases
* `docs` - Generate the manual
* `platform` - Start compose services (PostgreSQL + Keycloak)
* `platform-down` - Stop the compose services
* `platform-reset` - Stop the compose services and delete their data volumes
* `full` - Run everything (platform, clean, frontend, lint, test, build, docs, run)

## Manual

The manual is built with [Pandoc][pandoc] and the Eisvogel template:

```sh
mvn -Pmanual-docs generate-resources
```

or run the script directly:

```sh
cd docs/manual/en
./generate-manual.sh
```

This creates `target/cisys-en.html` and `target/cisys-en.pdf`.
