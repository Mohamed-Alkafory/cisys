\newpage

# Workflows

A **workflow** describes how a branch is built and tested. It lives in the repository at `.cisys/workflows/ci.yml`, so every branch carries its own workflow and a change to the workflow is reviewed like any other change.

Selecting **Build** next to a branch on a project page runs that branch's workflow. Leads and admins see the button.

## The workflow file

```yaml
name: main

environment:
  base: alpine:3.23

jobs:
  - job: Test
    steps:
      - name: Start
        run: make platform-start
      - name: Wait for 8180
        wait: 8180
      - name: Test
        run: make test
      - name: Stop
        run: make platform-stop
```

The workflow does not name a repository or a branch. The build uses the project's repository and the branch you selected.

| Key | Required | Meaning |
|-----|----------|---------|
| `name` | yes | The workflow name, shown in the build log. |
| `environment.base` | yes | The official container image the build starts from, for example `alpine:3.23`. |
| `jobs` | yes | One or more jobs, run in order. |
| `jobs[].job` | yes | The job name. |
| `jobs[].steps` | yes | One or more steps, run in order. |
| `steps[].name` | yes | The step name. |
| `steps[].run` | one of `run` or `wait` | A shell command, at most 4000 characters. |
| `steps[].wait` | one of `run` or `wait` | A TCP port (1–65535). The step waits until a process listens on it. |

Each step has exactly one of `run` and `wait`.

The workflow is checked before anything runs. Unknown or duplicate keys, missing keys, wrong types and values out of range fail the build. The log names the problem and, where it can, the line. Names are at most 200 characters and the file at most 64 KiB.

### The base image

`environment.base` names an official image. A name without a registry, such as `alpine:3.23` or `debian:13-slim`, means the official image on Docker Hub (`docker.io/library/alpine:3.23`). An image from another registry is written in full, for example `quay.io/fedora/fedora:43`.

An admin must allow the image on the **Images** page, under the name exactly as the workflow writes it. Otherwise the build fails with `Image … is not allowed`.

The base image needs `sh`. If it has no `git`, the build installs it with the image's package manager. That works for `apk`, `apt-get`, `dnf`, `microdnf`, `yum` and `zypper`. Images without one of these, such as distroless images, must already contain `git`.

## How a workflow is built

A build turns the workflow into a Containerfile and builds an image from it with Podman (or Docker):

1. **Read.** The backend fetches the branch and records its commit ID. It reads `.cisys/workflows/ci.yml` and stores the text with the build, then validates it.
2. **Base.** The image starts `FROM` the qualified `environment.base`. If the image is not available locally, it is pulled.
3. **Git.** If the image has no `git`, it is installed as root. This step has network access.
4. **Checkout.** As the unprivileged user `65534:65534`, the branch is cloned into `/workspace` at depth 1. The build then checks that the clone is at the recorded commit; if the branch moved in the meantime, the build fails. The working tree is **on the branch being tested**, with its `.git` directory, so `git status`, `git log -1` and `git rev-parse HEAD` work. `HOME` is `/tmp`.
5. **Steps.** The jobs and steps run in order as `65534:65534`, in `/workspace`, **without network**. The log marks every job and step with `==> Job:` and `==> Step:` lines.
6. **Result.** The first failing step stops the build. The log shows `==> Step failed:` with the step name and exit code, and the build records that exit code and the status `FAILED`. When every step succeeds, the build is `PASSED`.
7. **Cleanup.** The image is removed when the build ends. The log, the workflow text, the commit ID and the base image digest are kept.

The whole image build has ten minutes. After that it is stopped and reported as timed out.

### Steps and shells

* Each `run` step runs in a new `sh -c`, so a `cd` or a variable does not carry over to the next step. Files written to `/workspace` or `/tmp` do carry over.
* In a job **without** `wait` steps, each step is its own image layer (`RUN`). A process started in the background ends when its step ends.
* A job **with** a `wait` step runs as one `RUN`. A service started in the background by one step, such as `make platform-start`, therefore keeps running for the later steps of that job and stops when the job ends.
* A `wait` step checks the kernel's listening sockets (`/proc/net/tcp` and `/proc/net/tcp6`) once a second, for up to five minutes, so it needs only `sh` and `grep`. If the port is not listening by then, the step fails.

### No network for steps

Steps cannot reach the network. A step that downloads dependencies, such as a `mvn` or `npm install` without a local cache, fails. Use a base image that already contains the tools and dependencies the build needs.

## Private repositories

For a private repository, an admin starts the build with a credential reference. The image build reads the token from the backend's environment and hands it to the clone step only, as a build secret. It is not written into a layer, the Containerfile, the build arguments or the log, and later steps cannot read it. See **Private Git repositories** in the **Builds** chapter.

Never put secrets in a workflow file, because each build stores the file and shows it on the build page.

## On the build page

The build page shows the workflow file the build read, under **Workflow**. A queued build shows *Not read yet*. A build that failed before it could read the file shows *None*. **Image** shows `environment.base` as written in the workflow.

## Limits

* One workflow file per branch, at a fixed path. There is no trigger on push yet; builds are started from the web UI.
* All jobs run one after another in a single image build. Jobs do not run in parallel and have no separate images.
* Steps have no network access, and there are no per-step timeouts, environment variables, caches or artifacts.
