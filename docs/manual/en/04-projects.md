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

Private projects use the same page once you sign in as their owner or an administrator; everyone else is told the project does not exist. The branches panel lists the branches that already have builds, because reading a private repository requires credentials.

## Create a project

Leads and admins can create projects. Enter

* **Name**: up to 100 letters, digits, `.`, `_` and `-`, starting with a letter or digit, for example `my-project`. The name is the project's URL, `/<name>/`
* **Repository URL**: an HTTPS URL without embedded credentials, up to 2048 characters
* **Private**: whether the project is private

Projects created before this naming rule keep working. An administrator can rename one in the database; its builds follow, because they reference the project's id:

```sql
UPDATE project SET name = 'new-name' WHERE name = 'old name';
```

The creator's Keycloak subject is stored as the owner.

The repository must be on an allowed host and must not resolve to a loopback, private, link-local or metadata address. By default only `github.com` and `gitlab.com` are allowed; the backend settings `cisys.git.allowed-hosts` and `cisys.git.allowed-networks` extend that list.

## Ownership

Ownership uses the immutable Keycloak subject ID, not the user name. Development projects created before subject-based ownership was introduced must be reset or migrated to Keycloak subject IDs.
