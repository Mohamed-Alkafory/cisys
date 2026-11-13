\newpage

# Introduction

[**cisys**](https://github.com/mnemosyne-systems/cisys) is a modern continuous integration (CI) system for local installations and multi-user servers. It registers Git projects and runs manual builds for them in disposable containers.

It provides sign-in through Keycloak, three roles, project registration with public and private visibility, manual Linux builds with stored logs, and configurable branding.

![Keycloak sign-in page](images/keycloak-login.png){ width=100% }

## Features

* Keycloak Single Sign-On (SSO) authentication with 3 roles (Member, Lead, Admin)
* Persistent project registration with public and private projects
* Manual Linux builds from a chosen branch, run in a container through Docker or Podman
* Build history with status, commit ID, timing and output
* Private Git access through environment-variable credential references
* Branding (name, colors, logo and background image)
* Light and dark mode
* Public front page for visitors who are not signed in, with aggregate statistics for public projects

## Roles

* **Members** can view public projects and the build history of projects they can access.
* **Leads** can additionally create projects and start manual builds.
* **Admins** can see every project, change the branding, and use credential references for private Git.

## Project handling

Each project keeps

* A name and an HTTPS repository URL
* A private flag, which controls project and build-log visibility
* The owner, recorded as the Keycloak subject

## Build handling

Each build keeps

* The requester and the status (`RUNNING`, `PASSED`, `FAILED`)
* The commit ID that was built
* Start and finish times
* The requested branch, the workflow file it ran, the image, the image digest and the container exit code
* The complete output, stored as a PostgreSQL large object
