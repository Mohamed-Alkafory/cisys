.PHONY: all clean run test lint check build docs platform-start platform-stop reset frontend setup full

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

check: frontend
	@npm --prefix src/frontend run check

build: frontend
	@npm --prefix src/frontend run build

clean:
	@mvn clean

# Runs the frontend and the backend together; stopping either way stops both.
# The shell waits on the backend so it can act on signals right away, and the
# backend keeps the terminal's input (fd 3) for the Quarkus console keys.
run: frontend
	@exec 3<&0; \
	npm --prefix src/frontend run dev </dev/null & \
	frontend_pid=$$!; \
	mvn quarkus:dev -Dmaven.test.skip=true -Dquarkus.test.continuous-testing=disabled <&3 3<&- & \
	backend_pid=$$!; \
	trap 'kill $$frontend_pid $$backend_pid 2>/dev/null; wait' EXIT INT TERM; \
	wait $$backend_pid

test:
	@mvn test

docs:
	@mvn -Pmanual-docs generate-resources

platform-start:
	@if command -v podman-compose >/dev/null 2>&1; then \
		podman-compose up -d; \
	elif command -v docker >/dev/null 2>&1; then \
		docker compose up -d; \
	else \
		echo "No container runtime found (podman-compose or docker). Skipping compose services."; \
	fi

platform-stop:
	@if command -v podman-compose >/dev/null 2>&1; then \
		podman-compose down; \
	elif command -v docker >/dev/null 2>&1; then \
		docker compose down; \
	else \
		echo "No container runtime found (podman-compose or docker). Nothing to stop."; \
	fi

# Deletes the local PostgreSQL and Keycloak data volumes, after confirmation.
reset:
	@printf 'This deletes the local PostgreSQL and Keycloak data. Continue? [y/N] '; \
	read -r answer || answer=; \
	case "$$answer" in \
		[yY]|[yY][eE][sS]) ;; \
		*) echo "Aborted. Nothing was deleted."; exit 1 ;; \
	esac; \
	if command -v podman-compose >/dev/null 2>&1; then \
		podman-compose down -v; \
	elif command -v docker >/dev/null 2>&1; then \
		docker compose down -v; \
	else \
		echo "No container runtime found (podman-compose or docker). Nothing to reset."; \
	fi

full: platform-start clean frontend lint test build docs run
