\newpage

# Projects

A **project** registers a Git repository with cisys so that it can be built.

![Projects, Admin view](images/projects-admin.png){ width=100% }

## Visibility

* **Public** projects are visible to every signed-in user.
* **Private** projects are visible only to their creator and to admins.

The private flag controls visibility of the project and its build logs inside cisys. It does not grant or restrict access to the Git repository itself.

![Projects, Member view](images/projects-member.png){ width=100% }

## Create a project

Leads and admins can create projects. Enter

* **Name**: up to 100 characters
* **Repository URL**: an HTTPS URL without embedded credentials, up to 2048 characters
* **Private**: whether the project is private

The creator's Keycloak subject is stored as the owner.

Registering a project accepts any HTTPS host, but builds currently accept only `github.com` and `gitlab.com` URLs; see the **Builds** chapter.

## Ownership

Ownership uses the immutable Keycloak subject ID, not the user name. Development projects created before subject-based ownership was introduced must be reset or migrated to Keycloak subject IDs.
