.PHONY: all clean run test lint build docs platform platform-down platform-reset frontend setup full

all: clean lint run

setup: frontend
	@npm --prefix src/frontend run prepare

frontend:
	@if [ ! -f src/frontend/node_modules/.package-lock.json ] || \
		[ src/frontend/package.json -nt src/frontend/node_modules/.package-lock.json ] || \
		[ src/frontend/package-lock.json -nt src/frontend/node_modules/.package-lock.json ]; then \
		npm --prefix src/frontend ci; \
	fi

lint: frontend
	@npm --prefix src/frontend run lint

build: frontend
	@npm --prefix src/frontend run build

clean:
	@mvn clean

run:
	@mvn quarkus:dev

test:
	@mvn test

docs:
	@mvn -Pmanual-docs generate-resources

platform:
	@if command -v podman-compose >/dev/null 2>&1; then \
		podman-compose up -d; \
	elif command -v docker >/dev/null 2>&1; then \
		docker compose up -d; \
	else \
		echo "No container runtime found (podman-compose or docker). Skipping compose services."; \
	fi

platform-down:
	@if command -v podman-compose >/dev/null 2>&1; then \
		podman-compose down; \
	elif command -v docker >/dev/null 2>&1; then \
		docker compose down; \
	else \
		echo "No container runtime found (podman-compose or docker). Nothing to stop."; \
	fi

# Deletes the local PostgreSQL and Keycloak data volumes.
platform-reset:
	@if command -v podman-compose >/dev/null 2>&1; then \
		podman-compose down -v; \
	elif command -v docker >/dev/null 2>&1; then \
		docker compose down -v; \
	else \
		echo "No container runtime found (podman-compose or docker). Nothing to reset."; \
	fi

full: platform clean frontend lint test build docs run
