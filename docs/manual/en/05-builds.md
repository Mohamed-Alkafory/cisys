\newpage

# Builds

A **build** runs a branch's workflow, `.cisys/workflows/ci.yml`, by building a container image from it on the backend host. The **Workflows** chapter describes the file and the image build.

![Manual build configuration](images/builds.png){ width=100% }

## Start a build

Leads and admins select **Build** next to a branch on a project page. That runs the branch's workflow. The **Build history** form starts a build by entering

* **Branch**: the branch to fetch
* **Credential reference** (admins only, optional): see below

An admin can also turn on **Automatic builds** under **Settings** → **Builds**. A timer then looks for new commits on the project's watched branches and starts a build for each; see **Automatic builds** below. Automatic builds are on by default, for the whole instance, and are only for repositories you trust.

Members can view build history and logs for the projects they can access. Private projects and their logs are visible only to their creator and admins.

## How a build runs

1. The branch is fetched afresh with JGit at depth 1, so a force push is reflected in the next build, and its commit ID is recorded. An automatic build checks out the exact commit the timer queued from the project's local mirror, not the branch tip. If that commit is gone, the build fails rather than falling back to the branch tip; if the mirror directory itself is gone, the build fetches the exact commit from the project's repository instead. The checkout must stay within the size and file limits.
2. The branch's `.cisys/workflows/ci.yml` is read and stored with the build, then validated. A branch without the file, an invalid file, or a `container` image that is not on the build host or that an admin has not allowed on the **Images** page fails the build with a message in the log.
3. The workflow becomes a Containerfile. The image installs `git`, `make`, `podman` and `podman-compose` if they are missing and the workflow's `dependencies`, copies the checkout from step 1, and runs the steps as `65534:65534` without network. When admins allow hosts under **Settings** → **Network**, steps reach those hosts over HTTPS through the egress proxy and nothing else (Podman only). Podman builds with `no-new-privileges` and no layer cache. Docker uses its legacy builder, which honors the resource limits that BuildKit ignores: it first builds an image with the tools and dependencies, then builds the steps from that image with network off. See **Workflows**.
4. The status is `PASSED` when the image build succeeds, otherwise `FAILED` with the exit code of the failing step. A build that exceeds the timeout is stopped and reported as timed out, with the timeout in the log. The image is removed afterwards.

Build containers are limited to `BUILD_MAX_MEMORY_MIB` (`cisys.build.max-memory-mib`, default 1024) MiB of memory, `BUILD_MAX_CPUS` (`cisys.build.max-cpus`, default 1) CPU cores and `BUILD_MAX_PIDS` (`cisys.build.max-pids`, default 512) processes. All three must be positive. Admins can change them under **Settings** → **Performance** (64–65536 MiB, 1–16 cores, 16–32768 processes); saved values replace the configured ones for new builds. The process limit uses `ulimit nproc`, so it caps the processes of the unprivileged build user rather than every process in a cgroup.

Build requests are stored as `QUEUED` and claimed in request order. Each accepted request gets the next build number of its project, starting at #1; numbers are not reused after builds are deleted. By default one build runs at a time. Set `BUILD_CONCURRENCY` (`cisys.build.concurrency`) to change the number of concurrent builds and `BUILD_QUEUE_LIMIT` (`cisys.build.queue-limit`, default 100) to limit waiting requests. Both limits must be positive. The image build of one build may take `BUILD_TIMEOUT_MINUTES` (`cisys.build.timeout-minutes`, default 10) minutes, which must be positive. Admins can change all three under **Settings** → **Builds** (1–16 concurrent builds, 1–1000 queued builds, a timeout of 1–1440 minutes); saved values replace the configured ones, and a new timeout applies to builds that start after it is saved. A full queue returns HTTP 429 with a clear error. Requests keep their repository URL, branch and credential reference; credentials themselves are read from the environment when execution begins.

A checkout is limited to `BUILD_MAX_CHECKOUT_MIB` (`cisys.build.max-checkout-mib`, default 1024) MiB and `BUILD_MAX_CHECKOUT_FILES` (`cisys.build.max-checkout-files`, default 100000) files. Both limits must be positive. Admins can change both under **Settings** → **Checkout** (1–102400 MiB, 1–1000000 files); saved values replace the configured ones. The fetched data is checked during the fetch, the files are measured as they will be written, after line ending conversion such as `eol=crlf` in `.gitattributes`, before checkout, and the working tree is measured again after checkout. The image copies this checkout and does not fetch the branch again. A repository over a limit fails the build with `Build failed: Repository checkout exceeds the limit of N MiB` (or `N files`) in the log, and the checkout directory is removed.

If saving a completed result fails, the runner retains it in memory and retries on each dispatcher tick without rerunning the build. Its slot stays reserved until the result is saved.

On restart, interrupted `RUNNING` builds become `FAILED`, or `CANCELLED` when a cancellation was requested, and retain their partial logs. `QUEUED` builds remain queued and run when a slot is available. Startup also removes leftover `cisys-build-*` containers and images and `cisys-*` checkout directories from an unclean stop; a cleanup failure is logged and does not stop startup. Queued and running builds are protected from retention cleanup and prevent project deletion. This runner supports one backend instance; startup recovery assumes the previous instance has stopped.

## Cancel a build

Admins, the project's creator and whoever started a build can cancel it while it is `QUEUED` or `RUNNING` with the cancel button on its build page. A queued build becomes `CANCELLED` at once and never runs. For a running build, the runner stops the fetch or the image build within about a second, forcibly after ten more seconds, removes the image and records `CANCELLED` with `Build cancelled` at the end of the log. A cancellation that arrives while the build is finishing still wins. Cancelling a finished build returns HTTP 409.

## Automatic builds

**Automatic builds** are one global setting for the whole instance, not a per-project option; only an admin can change them. They are on by default. cisys then polls each project's repository on a timer with plain Git and queues a build for a new commit. It needs no push hook and no provider API, so GitHub, GitLab, self-hosted and private repositories all work the same way, and no provider needs to reach cisys.

The settings are global:

* **Automatic builds**: on or off.
* **Granularity**: **One build per commit** (default) queues every new commit, oldest first; **Tip only** queues just the branch tip when it moves.
* **Branches**: **All branches with `.cisys/workflows/ci.yml`** (default) or **main or master only**. In both cases a branch is watched only when its tip has the workflow file; a commit whose tree has no workflow file is skipped but does not stop the branch.
* **Poll interval (seconds)**: how often the timer checks; the default comes from `cisys.poll.every` (`POLL_EVERY`, default 60 s).

The first time a branch is seen, its tip is recorded but nothing is built, so turning automatic builds on never builds a repository's history. (A branch whose tip has no workflow file has no cursor either, so the commit that adds the file is a first sighting: it is seeded, not built.) Afterwards only the commits that appeared since the last time cisys looked are queued. A commit already built by the poller, or covered by a pull request build, is skipped whatever its status and whatever branch reaches it. A manual build, whatever its status, never stops the poller, so a manual build may share a commit.

All detection uses the project's own bare mirror, a fetch of every branch with forced refs and no garbage collection, so a commit a queued build still needs stays readable even after it leaves the remote. The poller and the runner support one backend instance, and `cisys.poll.mirror-dir` must be on that instance's local storage, not on storage shared by several instances. The mirror lives under `cisys.poll.mirror-dir` (`POLL_MIRROR_DIR`, default `~/.cisys/mirrors`), outside the temporary checkout directory, and is removed when the project is deleted or when both automatic builds and pull request builds are off. Its size is limited per project by `cisys.poll.mirror-max-mib` (`POLL_MIRROR_MAX_MIB`, default 4096; `0` turns the limit off); exceeding the limit stops that project's polling until the mirror is cleaned up. Private projects use the same `CISYS_GIT_*` credential reference as manual builds.

The last-seen commit of a branch moves only after a commit was queued or found to have been built, so a full build queue slows polling down instead of losing commits: the branch is retried on the next tick. A project that cannot be fetched (bad URL, host rule, authentication, network) is logged once and retried later, and one failing project never stops the tick. The build page shows **Poll** as the trigger.

Turning automatic builds off deletes every last-seen cursor, so that nothing is built for what happened while it was off. Mirrors are deleted too when pull request builds are also off. If a project has a queued or running poll or pull request build, its mirror is kept until that build finishes, because the build reads its commits from the mirror at run time.

Repository URLs use the same rules as manual builds (HTTPS, allowed hosts and ports). Automatic builds execute the repository's code in a container on the backend host; enable them only for repositories you trust.

## Pull request builds

**Pull request builds** are a separate, admin-only global setting under **Settings** → **Builds**, next to **Automatic builds**. They are off by default and support GitHub pull requests (`refs/pull/N/head`) and GitLab merge requests (`refs/merge-requests/N/head`) on `github.com` and `gitlab.com`. Merge refs are never used, and no provider API or webhook is involved: discovery uses the same Git access as automatic builds. Fork pull requests are not supported in 0.1.0: their heads are never fetched into the project's mirror. A fork commit that a same-repository branch already reaches is treated like any other commit.

* Enabling the feature records the highest current pull request number as a baseline and seeds every existing pull request without building it. Only a head above the baseline, or a head that changes afterwards, is built. Turning the feature off, or on again, clears every project's cursors and baseline as part of the setting change, so re-enabling always seeds the heads that exist at that moment and never builds changes made while the feature was off. The reset does not depend on a project being idle, and branch polling is not affected.
* Only the pull request head is built; the merge result and the intermediate commits of the branch are not tested.
* A head is eligible only when its commit is present in the project's mirror, a local branch reaches it, and `.cisys/workflows/ci.yml` exists at that exact commit. The branch scope (**All branches** or **main or master only**) and the **Automatic builds** switch do not apply to pull request builds.
* A pull request build checks out the exact commit from the project's mirror. If the mirror or the commit is missing, the build fails instead of fetching the repository again, and it never resolves or receives a project credential. Discovery and the mirror fetch may use the project credential, like automatic builds.
* If the project already has a usable build for the same commit, whatever started it, no new pull request build is queued. A usable build is any build whose status is not `CANCELLED`, so a cancelled pull request row never hides a queued, running or finished poll or manual build. The existing build keeps its own trigger, so a poll build is not relabelled as a pull request build, and two pull requests with the same head share one build. A build that failed is not retried automatically.
* Every successful discovery pass cancels queued pull request builds whose commit no longer belongs to any tracked, eligible pull request, whether it moved, closed, or became ineligible. The set is project-wide, so a commit shared by several pull requests is cancelled only after the last of them no longer needs it. Running, finished, poll and manual builds are left alone, and a queued build cancelled this way is queued again if its commit becomes needed later. A build cancelled by a user or admin, or one that had already started, is not revived.
* Because only one build row may exist per project and commit, a cancelled pull request row that is not revived still covers its commit: a later pull request pointing at the same commit gets no new build until retention removes the row. This is deliberate; cisys does not override an intentional cancellation.
* Turning the feature off clears every project's pull request cursors and baseline and keeps the build history. The reset happens in the same transaction as the setting change, so it is not skipped when a project is busy, and a discovery pass racing the change cannot recreate the old cursors or baseline. Branch cursors and branch polling are untouched. Changing the repository URL resets the cursors in the same way. Mirrors are kept while either automatic builds or pull request builds are enabled, and removed when both are off unless a queued or running poll or pull request build still needs one.
* The poller, the runner and pull request discovery support one backend instance.

Reachability from a local branch does not prove that the head came from the base repository. A fork pull request whose head commit already exists in the base repository is built; the poller already trusts that commit. Treat write access to the repository as the ability to trigger a build: there is no approval step in 0.1.0.

Discovery was verified once for each provider, on public repositories without a credential: GitHub still listed `refs/pull/1/head` for closed `octocat/Hello-World` pull request 1, and GitLab still listed `refs/merge-requests/3/head` for merged `gitlab-org/gitlab-test` merge request 3. Not verified: whether a head ref updates after a push, whether GitLab deletes merge request refs after some time, and private repositories read with a credential. A missing ref simply stops further builds for that number.

## Workflow file

The workflow file format, how the image is built, and its limits are described in the **Workflows** chapter.

## What a build records

Each build stores the requested branch, the text of the workflow file it read (shown on the build page), the full name of the base image it found, the credential reference name if one was used (shown to admins only), the base image digest when the container runtime reports one, the commit ID, how it was started (manual, polled or pull request), the provider and number of a pull request build, the exit code of the image build, which is that of the failing step (none when the build timed out, was cancelled or never ran), who started it, and when it started and finished; the duration is derived from these times.

## Build logs

The complete output of the build, standard output and standard error together, is stored in PostgreSQL as one large object per build, appended while the build runs. It is stored as the raw bytes the build wrote and shown as UTF-8. Output from a running build is visible within about five seconds; use **Refresh** on the build.

* `BUILD_LOG_MAX_BYTES` (default 1 GiB) is a safety limit per build, not a normal cap. Output beyond it is read and discarded, a marker line is added, and the build is marked as truncated. The limit is exact in bytes, so if it falls inside a multi-byte character, the last character shown may be incomplete.
* A build is also marked as truncated if the log could not be stored completely. Logs from before this version were capped at 120 KB; those that reached the cap are marked as truncated.
* The build page shows the first 2 MiB of a log. For a running build, **Refresh** appends only the new output instead of downloading the log from the start; once 2 MiB are shown, it switches to the latest 2 MiB so new output stays visible. **Download log** saves the full log; in Chromium-based browsers it is written straight to the chosen file, in other browsers it is held in browser memory first. The backend never holds a whole log in memory.
* A visitor who is not signed in can read at most `PUBLIC_LOG_MAX_BYTES` (2 MiB by default) of a log in one response. The page requests the log in chunks and retries a rate-limited page after `Retry-After` from the same offset, so **Download log** can save a log larger than the limit, even across rate-limit windows. Signed-in members of a private project, and administrators, are not capped.
* Public pages pause their refresh while the tab is hidden and refresh less often when no build is running. Anonymous requests to a public page, its branches, its logs and the public statistics are also rate limited; a client that sends too many requests is told to wait and retry.

## Pinned builds

The project's creator and admins can **Pin** a build on its build page, and **Unpin** it again. A pinned build, with its log, is kept permanently; build retention cleanup skips it. Pinned builds are marked with a pin icon in the build history.

## Build retention

Every hour, and a minute after startup, cisys deletes old builds of each project together with their logs. A finished or cancelled, unpinned build is deleted when either

* it finished more than the maximum age ago (default 30 days), or
* the project has more newer finished or cancelled, unpinned builds than the maximum count (default 50).

Admins change both limits under **Settings** → **Retention**; `0` turns a limit off. Running and pinned builds are never deleted, and pinned builds do not count towards the limit. Deleted builds cannot be restored, so pin a build to keep it. `BUILD_RETENTION_EVERY` (default `1h`) changes how often the cleanup runs.

## Repository URLs

Only HTTPS repositories on an allowed host are accepted for builds. Redirects, embedded credentials, query strings and fragments are rejected. Hosts, ports and private networks are configured by an administrator; see the **Projects** chapter.

## Private Git repositories

Set an environment variable whose name starts with `CISYS_GIT_`, for example `CISYS_GIT_PROJECT`, to a read-only provider token in the backend environment before starting it. Then, as an admin, enter that variable name in the build form.

* The value is never stored in the project database. Only the backend's fetch uses it; the image build does not receive it, so it is not stored in the image and steps cannot read it.
* Only admins can use credential references until credentials are scoped to individual projects.
* Never include secrets in workflow files or build output.

## Images

Admins manage build images under **Images** in the header. The page lists the images present in the container runtime on the backend host, with their ID, size and age.

* **Pull** downloads an image from its registry, for example `docker.io/library/alpine:3.23`.
* **Remove** deletes a local image and takes it off the allowed list.
* Tick the images that builds may use and select **Save**. A workflow whose `container` resolves to any other image fails. A workflow may name an allowed image by its short name, full name or ID; see **Workflows**.

An image that is allowed but no longer present locally is listed separately, where it can be pulled again or cleared.

## Container runtime

The runner is always on. It uses Podman when it is installed and Docker otherwise.

**Trust boundary:** the runner allows trusted leads and admins to execute code through the local container daemon. Docker socket access grants host-level control to the backend. These limits are not a hostile-code sandbox. Use a dedicated machine, disk quotas and trusted repositories, and do not expose this setup to the Internet. Repository checkout sizes are not bounded. Installing the build tools and cloning the branch happen with network access, as root and as `65534:65534` respectively; the workflow's steps run without network, or reach the allowed hosts through the egress proxy only. An allowed host can receive anything a step sends it; allow only registries you trust.

## Verification status

The image build was verified with Podman 5.8 on Linux with `alpine:3.23` and `debian:13-slim` base images and a public GitHub repository. Docker's legacy builder was verified on Linux to apply the memory, CPU and process limits from the generated Containerfiles. It has not been verified end-to-end with a real private Git provider, a full Docker build, or Windows/WSL.
