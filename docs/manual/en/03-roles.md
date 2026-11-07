\newpage

# Roles

[**cisys**](https://github.com/mnemosyne-systems/cisys) is designed around three roles, which are Keycloak realm roles. A signed-in user without one of them cannot use the application.

## Member

The **Member** role is the standard read-only role. Members can view public projects, and the build history and logs of the projects they can access.

## Lead

The **Lead** role is for people who run projects. Leads can do everything a member can, create projects, and start manual builds on projects they can see.

## Admin

The **Admin** role manages the installation. Admins can see every project including private projects of other users, start builds, use credential references for private Git repositories, and change the branding.

## Access summary

| Action                              | Member | Lead | Admin |
| :---------------------------------- | :----- | :--- | :---- |
| View public projects                | yes    | yes  | yes   |
| View own private projects           | n/a    | yes  | yes   |
| View other users' private projects  | no     | no   | yes   |
| Create a project                    | no     | yes  | yes   |
| View build history and logs         | yes    | yes  | yes   |
| Start a manual build                | no     | yes  | yes   |
| Use a Git credential reference      | no     | no   | yes   |
| Change branding                     | no     | no   | yes   |

"View" always means projects the user may access. A project that is not visible to a user is reported as not found.

The following chapters describe the application areas in more detail.
