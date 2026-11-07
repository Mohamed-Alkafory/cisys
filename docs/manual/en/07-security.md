\newpage

# Security

The **Security** model in cisys controls who can sign in, what they can access, and which actions each role may perform.

## Keycloak

Cisys uses **Keycloak** as its identity provider.

* **Centralized credentials**: passwords and accounts are managed in Keycloak, not in cisys.
* **Role-based access control**: the realm roles `member`, `lead` and `admin` decide which actions are available.
* **PKCE**: the browser uses the authorization-code flow with PKCE as the public client `cisys-ui`.

The backend validates the access token, reads roles from `realm_access/roles` and uses the immutable subject (`sub`) as the user identity.

## How to sign in

1. Open the application at <http://localhost:5173>.
2. If you are not signed in, you are redirected to Keycloak.
3. Enter your user name and password and click **Sign In**.

![Keycloak sign-in page](images/keycloak-login.png){ width=100% }

4. Keycloak redirects you back to cisys.

To sign out, use the sign-out control in the header.

## Development accounts

* Member: `member` / `member`
* Lead: `lead` / `lead`
* Admin: `admin` / `admin`

These credentials, HTTP, and Keycloak development mode are for a trusted local machine only and must be replaced for any shared server.

## Authorization

Private projects and their build logs are visible only to their creator and admins. Starting builds requires the Lead or Admin role. Changing branding, and using Git credential references, require the Admin role. The full endpoint mapping is in `docs/SECURITY.md`.

## Build runner

Builds execute repository-controlled code through the local container engine. Read the **Builds** chapter, including the trust boundary, before enabling the runner.

## TLS

TLS is not configured. Terminate TLS in a reverse proxy or configure the Quarkus TLS registry before any shared deployment.
