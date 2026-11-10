# Security

This is an early-stage project. The default configuration is for a **trusted local machine only**: it uses development credentials, plain HTTP, Keycloak development mode and permissive database schema management. Do not expose it to the Internet.

To report a vulnerability, contact the maintainers privately (see [AUTHORS](../AUTHORS)) rather than opening a public issue.

## Roles

The Keycloak realm roles are `member`, `lead` and `admin`. A user without one of these roles is rejected with `403 Forbidden`.

The default users installed by `contrib/keycloak/realm.json` in the development environment are

* Member: `member` / `member`
* Lead: `lead` / `lead`
* Admin: `admin` / `admin`

The Keycloak admin console (<http://localhost:8180/admin>) uses `admin` / `admin`. The `cisys-backend` service account, which signs users out when cisys shuts down, uses the secret `cisys` and can manage the realm's users. Replace all of these credentials before any shared deployment.

### Role-to-endpoint mapping

| Endpoint                                  | Member                    | Lead                 | Admin |
| :---------------------------------------- | :------------------------ | :------------------- | :---- |
| `GET /api/session`                        | yes                       | yes                  | yes   |
| `GET /api/projects`                       | public projects           | public and own       | all   |
| `POST /api/projects`                      | no                        | yes                  | yes   |
| `GET /api/projects/{id}/builds[/{id}]`    | visible projects          | visible projects     | all   |
| `GET /api/projects/{id}/branches`         | visible projects          | visible projects     | all   |
| `POST /api/projects/{id}/builds`          | no                        | yes                  | yes   |
| `POST` build with a `credentialRef`       | no                        | no                   | yes   |
| `GET /api/branding`                       | yes                       | yes                  | yes   |
| `PUT /api/branding`                       | no                        | no                   | yes   |
| `GET /api/images` (allowed images)        | yes                       | yes                  | yes   |
| `PUT /api/images`                         | no                        | no                   | yes   |
| `GET/POST/DELETE /api/images/local`       | no                        | no                   | yes   |

Without signing in, `GET /api/branding/public`, `GET /api/branding/logo`, `GET /api/stats/public`, the public project page (`GET /api/project-pages/{name}`, `GET /api/project-pages/{name}/builds/{buildId}` and its build logs) and `GET /api/project-pages/{name}/branches` for public projects are readable. The project page accepts an optional `branch` query parameter, which filters the builds but applies the same public/private visibility rules. The statistics are aggregate counts computed from public projects only (public projects, their builds by status, success rate, last build time). Private projects and their builds are left out of every number, and no names, repository URLs, logs or users are exposed. The server caches the result for `STATS_CACHE_SECONDS` (default 30) seconds. The branch endpoint returns remote branch names for public projects. For private projects it requires the same owner/admin access as the page and returns only the branches that already have builds, so the private repository is never read; anonymous callers and users without access receive `404`.

Private projects (and their build history and logs) are visible only to their creator and admins. Ownership is recorded with the immutable Keycloak subject (`sub`), not the user name. A project that is not visible to the caller is reported as `404 Not Found`.

The private flag controls visibility inside cisys; it does not grant or restrict Git access.

## Authentication

* The browser signs in with the Keycloak authorization-code flow with PKCE (`S256`) as the public client `cisys-ui`. Direct access grants are disabled in the development realm.
* The backend validates bearer access tokens and reads roles from `realm_access/roles`.
* CORS allows `http://localhost:5173` only. Change `quarkus.http.cors.origins` for any other frontend origin.

## Build runner

The runner is always on. It lets leads and admins run code from a Git repository in a container on the backend host, using only images an admin has allowed on the **Images** page.

**Trust boundary:** access to the container engine (in particular the Docker socket) grants host-level control to the backend. The limits below are not a hostile-code sandbox. Use a dedicated Linux machine, disk quotas and trusted repositories only.

Controls that are in place

* Only HTTPS `github.com` and `gitlab.com` repository URLs are accepted for builds and branch discovery; redirects, embedded credentials, query strings, fragments and non-default ports are rejected. Branch discovery reads the project's own repository URL, never a URL supplied by the caller.
* Branch discovery for private projects is not attempted because credentials are not stored on the project; the endpoint returns an empty list with reason `private_repository`.
* Branch names are validated as Git references. Builds may only use images an admin has allowed; image names are validated against a restrictive pattern.
* The branch is fetched afresh with JGit at depth 1, so a force push is reflected in the next build. The `.git` directory is not copied into the container.
* The container runs with no network, no capabilities, `no-new-privileges`, a PID limit of 256, 1 GiB of memory, one CPU, and as the unprivileged user `65534:65534`.
* One build runs at a time and times out after ten minutes. Output is stored in full, up to the `BUILD_LOG_MAX_BYTES` safety limit (default 1 GiB); output beyond it is discarded and the build is marked truncated.
* Builds left in the `RUNNING` state by a server restart are marked `FAILED` at startup.

Known gaps

* Repository checkout size is not bounded.
* Runner and Podman operation on Windows/WSL has not been verified.
* Credentials are not scoped to individual projects; only admins may reference one.

### Git credentials

For private repositories set an environment variable named `CISYS_GIT_<NAME>` to a read-only provider token in the backend environment before starting it, then enter the variable name in the build form as an admin. The value is never stored in the database or passed to the build container. Never place secrets in build commands or output.

## TLS

The application is not configured for TLS. Terminate TLS in a reverse proxy, or configure the [Quarkus TLS registry](https://quarkus.io/guides/tls-registry-reference), before any shared deployment. Keycloak must also be moved off `start-dev` and onto HTTPS in that case.

See [ROADMAP.md](ROADMAP.md) for the planned hardening work.
