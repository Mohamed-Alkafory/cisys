\newpage

# Projects

A **project** registers a Git repository with cisys so that it can be built.

![Projects, Admin view](images/projects-admin.png){ width=100% }

## Visibility

* **Public** projects are visible to every signed-in user.
* **Private** projects are visible only to their creator and to admins.

The private flag controls visibility of the project and its build logs inside cisys. It does not grant or restrict access to the Git repository itself.

![Projects, Member view](images/projects-member.png){ width=100% }

## Public project page

Every public project has a page at `/<name>/`, for example `http://localhost:5173/cisys/`. It shows the repository branches in a panel on the left and the build history in a table on the right, newest first. Anonymous visitors can read it.

Selecting a branch in the left panel filters the build history to that branch. The page starts on `main`, or `master` for repositories that still use it. Each build row shows a green thumbs up for a passing build, a red thumbs down for a failing build, and a spinner while it runs, followed by the build number, branch, commit, start time, duration and exit code.

Selecting a build opens its own page at `/<name>/<branch>/build/<id>`, for example `http://localhost:5173/cisys/master/build/51`, with the build details and the full log.

Private projects use the same page once you sign in as their owner or an administrator; everyone else is told the project does not exist. The branches panel reads the repository when the project has a credential reference (see below); without one it lists only the branches that already have builds.

## Create a project

Leads and admins can create projects. Enter

* **Name**: up to 100 letters, digits, `.`, `_` and `-`, starting with a letter or digit, for example `my-project`. The name is the project's URL, `/<name>/`
* **Repository URL**: an HTTPS URL without embedded credentials, up to 2048 characters
* **Private**: whether the project is private
* **Credential reference**: admins only, and only for private projects. The name of an environment variable that holds a read-only token for the repository, for example `CISYS_GIT_PROJECT`. The token itself is never entered here or stored in the database.

The project credential is used for read-only branch discovery on the project page. Builds keep using the credential reference from the build form. An admin can change or clear the project credential later on the project's settings page (`PUT /api/projects/{id}`) or directly through `PUT /api/projects/{id}/credential`. When a lead changes the repository URL, the credential is cleared, so it is never sent to a new host; an admin must set it again for the new repository.

Admins, and the lead who owns a project, can open its settings page with the pencil button in the repositories list. **Save** applies the change and returns to the project page; **Cancel** leaves without saving. Saving keeps queued and running builds on the repository URL, branch, image, command and credential they started with; the change affects new page views and new builds.

Projects created before this naming rule keep working. An administrator can rename one in the database; its builds follow, because they reference the project's id:

```sql
UPDATE project SET name = 'new-name' WHERE name = 'old name';
```

The creator's Keycloak subject is stored as the owner.

The repository must be on an allowed host and must not resolve to a loopback, private, link-local or metadata address. By default only `github.com` and `gitlab.com` on port 443 are allowed; the backend settings `cisys.git.allowed-hosts`, `cisys.git.allowed-ports` and `cisys.git.allowed-networks` extend that list. A local server with a self-signed certificate can be named in `cisys.git.insecure-tls-hosts`; adding its certificate to the JVM truststore is preferred.

## Ownership

Ownership uses the immutable Keycloak subject ID, not the user name. Development projects created before subject-based ownership was introduced must be reset or migrated to Keycloak subject IDs.
