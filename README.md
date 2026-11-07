# cisys: Continuous Integration System

[![Discussions](https://img.shields.io/github/discussions/mnemosyne-systems/cisys)](https://github.com/mnemosyne-systems/cisys/discussions) [![GitHub stars](https://img.shields.io/github/stars/mnemosyne-systems/cisys?style=social)](https://github.com/mnemosyne-systems/cisys/stargazers)

**cisys** is an early-stage continuous integration (CI) system for local installations and, eventually, multi-user servers. It registers Git projects and runs manual Linux builds for them in disposable containers.

## Features

- Keycloak Single Sign-On (SSO) authentication with 3 roles (Member, Lead, Admin)
- Persistent project registration with public and private projects
- Manual Linux builds from a chosen branch, run in a container with Docker or Podman
- Build history with status, commit ID, timing and output
- Private Git access through environment-variable credential references
- Branding (name, font, colors, logo and background image)
- Light and dark mode

## Documentation

- [Developer guide](docs/DEVELOPERS.md)
- [Build and run guide](docs/BUILDING.md)
- [Security notes](docs/SECURITY.md)
- [Entity diagram](docs/entity-diagram.md)
- [Roadmap](docs/ROADMAP.md)
- [Pull request guide](docs/PR_GUIDE.md)
- [Manual sources](docs/manual/en) (build with `docs/manual/en/generate-manual.sh`)
- [License](LICENSE)

Run `make` targets for the common tasks; see the Makefile section of the build guide.

## Technologies

**cisys** is built with

- [Quarkus](https://quarkus.io/) on [Java 21](https://openjdk.org/) for the backend
- [Keycloak](https://www.keycloak.org/) for identity management and Single Sign-On (SSO)
- [PostgreSQL](https://www.postgresql.org) for storage
- [JGit](https://www.eclipse.org/jgit/) for repository access
- [Docker](https://www.docker.com/) or [Podman](https://podman.io/) for build containers
- [React](https://react.dev/) on the frontend, bundled with [Vite](https://vitejs.dev/)
- [shadcn/ui](https://ui.shadcn.com/) components with [Tailwind CSS](https://tailwindcss.com/)
- [Red Hat Text](https://github.com/RedHatOfficial/RedHatFont) as the default typeface
- [Maven](https://maven.apache.org/) for the backend build
- [Node.js and npm](https://nodejs.org/) for frontend dependencies

## Screenshots

### Keycloak sign-in

![Keycloak sign-in page](docs/screenshots/keycloak-login.png)

### Projects — Admin view

![Admin projects page](docs/screenshots/admin-projects.png)

### Manual build configuration

![Manual build configuration](docs/screenshots/admin-builds.png)

## Getting started

Requires Java 21+, Maven, Node.js, npm, and Docker Compose or Podman with Compose. Images and dependencies must be downloadable. This setup is for a trusted local machine only.

Clone the repository:

```sh
git clone https://github.com/mnemosyne-systems/cisys.git
cd cisys
```

Start PostgreSQL and Keycloak with compose:

```sh
make platform-start
```

This detects whether you have Podman Compose or Docker Compose and starts the containers defined in `compose.yaml`. You can also run `docker compose up -d` directly. Wait for Keycloak to become available at <http://localhost:8180>.

Run `make platform-stop` to stop the containers and keep their data, or `make reset` to stop them and delete their data after asking for confirmation.

Start the backend and the frontend in development mode:

```sh
make run
```

This installs the frontend dependencies when needed, runs the frontend in the background and the backend (`mvn quarkus:dev`) in the foreground. Stopping the backend also stops the frontend and signs all users out. It does not compile or run the tests; use `make test` for those.

The install also sets up the Husky pre-commit hook in a Git checkout; if you unpacked an archive, run `git init` first. Run `make lint` for the frontend lint check.

The application is available at <http://localhost:5173>.

The configuration is defined in `src/backend/main/resources/application.properties`.

### Ports

The local setup uses the following ports. All of them are bound to the loopback interface.

| Port   | Service             | Address                 | Started by                     |
| :----- | :------------------ | :---------------------- | :----------------------------- |
| `5173` | Frontend            | <http://localhost:5173> | `make run`                     |
| `8080` | Backend API         | <http://localhost:8080> | `make run`                     |
| `8180` | Keycloak            | <http://localhost:8180> | `make platform-start`          |
| `5432` | PostgreSQL          | `localhost:5432`        | `make platform-start`          |
| `5005` | Java debugger       | `localhost:5005`        | `make run` (development mode)  |

Open the application on port `5173`. Keycloak and the backend only accept that frontend address, so sign-in and API calls fail if Vite moves to another port because `5173` is already in use.

### Test accounts

The users defined for testing are

- Member: `member` / `member`
- Lead: `lead` / `lead`
- Admin: `admin` / `admin`

The Keycloak admin console is at <http://localhost:8180/admin> with `admin` / `admin`. The realm import runs on the first startup only; existing Keycloak data is not overwritten. To recreate the local development data, run `make reset` (this deletes the local databases).

### Windows

Install Docker Desktop with its WSL 2 backend and Ubuntu. Put the project in your Ubuntu home directory, install Java 21, Maven, Node.js and npm inside Ubuntu, then follow the steps above from an Ubuntu terminal. Open <http://localhost:5173> in your Windows browser.

## Contributing

Contributions to **cisys** are managed on [GitHub.com](https://github.com/mnemosyne-systems/cisys/)

- [Ask a question](https://github.com/mnemosyne-systems/cisys/discussions)
- [Raise an issue](https://github.com/mnemosyne-systems/cisys/issues)
- [Feature request](https://github.com/mnemosyne-systems/cisys/issues)
- [Code submission](https://github.com/mnemosyne-systems/cisys/pulls)

Contributions are most welcome !

Please, consult our [Code of Conduct](CODE_OF_CONDUCT.md) policies for interacting in our community, and the [Contributing guide](CONTRIBUTING.md).

Consider giving the project a [star](https://github.com/mnemosyne-systems/cisys/stargazers) on [GitHub](https://github.com/mnemosyne-systems/cisys/) if you find it useful.

## License

[Eclipse Public License - v2.0](https://www.eclipse.org/legal/epl-2.0/)
