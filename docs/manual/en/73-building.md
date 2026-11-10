\newpage

# Building

## Development workflow

[**cisys**][cisys] uses [Quarkus][quarkus] for the backend and a [Vite][vite] dev server for the React frontend. They are started separately.

Start the support services from the repository root

```sh
make platform-start
```

then the application stack

```sh
make run
```

To stop the application press Ctrl+c in the application window, and

```sh
make platform-stop
```

Use

```sh
make clean
```

when you need to clear `target/` and force a fresh rebuild.

## Tests and checks

```sh
make test
```

The backend tests use an in-memory H2 database and do not need Keycloak or PostgreSQL. The Husky `pre-commit` hook runs ESLint on staged frontend files, and the `pre-push` hook runs `npm run lint`. Run the tests explicitly with `make test`. Bypass a hook once with `--no-verify`.

## Production build

```sh
make package
```

Production deployment (TLS, secrets, hardened Keycloak, migrations) are not supported yet.

## Makefile

The targets are

* `all` - Clean, lint and run (Default)
* `setup` - Install frontend dependencies and the Git hooks
* `clean` - Clean
* `lint` - Run the frontend ESLint check
* `check` - Run the frontend checks (lint and build), as CI does
* `build` - Build the frontend
* `run` - Run the backend and the frontend in development mode
* `test` - Run the backend test cases
* `docs` - Generate the manual
* `platform-start` - Start compose services (PostgreSQL + Keycloak)
* `platform-stop` - Stop the compose services
* `reset` - Stop the compose services and delete their data volumes, after confirmation
* `full` - Run everything (platform-start, clean, frontend, lint, test, build, docs, run)

## Manual

The manual is built with [Pandoc][pandoc] and the Eisvogel template:

```sh
cd docs/manual/en
./generate-manual.sh
```

This creates `target/cisys-en.html` and `target/cisys-en.pdf`.
