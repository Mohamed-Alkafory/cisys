/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * A CI workflow, read from {@value #PATH} in a project's repository.
 *
 * <pre>
 * name: main branch
 *
 * environment:
 *   base: alpine:3.23
 *
 * jobs:
 *   - job: Build and test
 *     steps:
 *       - name: Start
 *         run: make platform-start
 *       - name: Wait for 8180
 *         wait: 8180
 * </pre>
 *
 * A step either runs a shell command ({@code run}) or waits until a TCP port accepts connections ({@code wait}).
 * A build turns the workflow into a Containerfile, see {@link #containerfile(String, String, String, String, boolean)}.
 */
public record Workflow(String name, Environment environment, List<Job> jobs) {
    /** Where the workflow lives, relative to the repository root. */
    public static final String PATH = ".cisys/workflows/ci.yml";
    static final int MAX_BYTES = 64 * 1024;
    static final int MAX_NAME = 200;
    static final int MAX_COMMAND = 4000;

    /** The container the jobs run in. */
    public record Environment(String base) {}

    public record Job(String job, List<Step> steps) {}

    /** Exactly one of {@code run} and {@code wait} is set; {@code wait} is held in {@link #port()}, as records cannot have a {@code wait} component. */
    public record Step(String name, String run, @JsonProperty("wait") Integer port) {
        public boolean isRun() {
            return run != null;
        }
    }

    private static final ObjectMapper MAPPER = JsonMapper.builder(new YAMLFactory())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .build();

    /** Reads the workflow from a checkout, or returns null when the repository has none. */
    public static Workflow load(Path checkout) throws IOException {
        String text = read(checkout);
        return text == null ? null : parse(text);
    }

    /** The workflow file's text, unparsed, or null when the repository has none. Builds store it as they read it. */
    public static String read(Path checkout) throws IOException {
        Path file = checkout.resolve(PATH);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new InvalidWorkflowException(PATH + " must be a regular file");
        if (Files.size(file) > MAX_BYTES) throw new InvalidWorkflowException(PATH + " is larger than " + MAX_BYTES + " bytes");
        String text;
        try {
            text = Files.readString(file);
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new InvalidWorkflowException(PATH + " is not valid UTF-8");
        }
        // PostgreSQL text cannot hold NUL characters.
        if (text.indexOf('\0') >= 0) throw new InvalidWorkflowException(PATH + " contains a NUL character");
        return text;
    }

    /** Parses and validates a workflow document. */
    public static Workflow parse(String yaml) {
        if (yaml == null || yaml.isBlank()) throw new InvalidWorkflowException("The workflow is empty");
        if (yaml.length() > MAX_BYTES) throw new InvalidWorkflowException("The workflow is larger than " + MAX_BYTES + " bytes");
        Workflow workflow;
        try {
            workflow = MAPPER.readValue(yaml, Workflow.class);
        } catch (UnrecognizedPropertyException e) {
            throw new InvalidWorkflowException("Unknown key '" + e.getPropertyName() + "'" + location(e));
        } catch (JsonProcessingException e) {
            throw new InvalidWorkflowException(e.getOriginalMessage() + location(e));
        }
        workflow.validate();
        return workflow;
    }

    private static String location(JsonProcessingException e) {
        return e.getLocation() == null ? "" : " at line " + e.getLocation().getLineNr();
    }

    private void validate() {
        requireName(name, "name");
        if (environment == null) throw new InvalidWorkflowException("environment is required");
        if (environment.base() == null || environment.base().isBlank()) throw new InvalidWorkflowException("environment.base is required");
        if (!environment.base().matches(AllowedImage.PATTERN)) throw new InvalidWorkflowException("environment.base is not a valid image reference");
        if (jobs == null || jobs.isEmpty()) throw new InvalidWorkflowException("At least one job is required");
        for (int j = 0; j < jobs.size(); j++) {
            Job job = jobs.get(j);
            String where = "jobs[" + j + "]";
            if (job == null) throw new InvalidWorkflowException(where + " is empty");
            requireName(job.job(), where + ".job");
            if (job.steps() == null || job.steps().isEmpty()) throw new InvalidWorkflowException(where + " needs at least one step");
            for (int s = 0; s < job.steps().size(); s++) {
                Step step = job.steps().get(s);
                String at = where + ".steps[" + s + "]";
                if (step == null) throw new InvalidWorkflowException(at + " is empty");
                requireName(step.name(), at + ".name");
                if ((step.run() == null) == (step.port() == null)) throw new InvalidWorkflowException(at + " needs exactly one of run or wait");
                if (step.run() != null && (step.run().isBlank() || step.run().length() > MAX_COMMAND)) {
                    throw new InvalidWorkflowException(at + ".run must be a command of at most " + MAX_COMMAND + " characters");
                }
                if (step.port() != null && (step.port() < 1 || step.port() > 65535)) {
                    throw new InvalidWorkflowException(at + ".wait must be a port between 1 and 65535");
                }
            }
        }
    }

    /** How long a {@code wait} step waits for its port, in seconds. */
    static final int WAIT_SECONDS = 300;
    static final int MAX_CONTAINERFILE = 200_000;
    /** The user the clone and the steps run as. */
    static final String BUILD_USER = "65534:65534";
    /** The id of the build secret that holds the Git token, see {@link #containerfile}. */
    static final String GIT_SECRET = "git";

    /**
     * The Containerfile that builds this workflow's image from the local image {@code baseId}, which
     * {@link ImageReference#resolve} found for {@code environment.base}; see
     * {@link #containerfile(String, String, String, String, boolean, int)}.
     */
    public String containerfile(String baseId, String repositoryUrl, String branch, String revision, boolean credential) {
        return containerfile(baseId, repositoryUrl, branch, revision, credential, WAIT_SECONDS);
    }

    /**
     * Builds an image from the base image, named by its ID so the build uses exactly the image that was resolved:
     * <ol>
     * <li>installs {@link #TOOLS} (Git, Make, Podman and Podman Compose) as root when the image does not have them,</li>
     * <li>clones the branch into {@code /workspace} as an unprivileged user and checks that it is at {@code revision};
     * the working tree stays on the branch, with its {@code .git},</li>
     * <li>runs the steps as that user, without network.</li>
     * </ol>
     * Each {@code run} step is its own {@code RUN}. A job with a {@code wait} step runs as a single {@code RUN},
     * because processes started in the background end with the {@code RUN} that started them. The first failing
     * step stops the build with its exit code. The Git token, when there is one, is a build secret
     * ({@value #GIT_SECRET}) that is mounted for the clone only and is not stored in the image.
     */
    String containerfile(String baseId, String repositoryUrl, String branch, String revision, boolean credential, int waitSeconds) {
        if (!baseId.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Not an image ID: " + baseId);
        StringBuilder f = new StringBuilder();
        f.append("FROM ").append(baseId).append('\n');
        f.append("USER 0\n");
        f.append("RUN ").append(exec("sh", "-c", INSTALL_TOOLS)).append('\n');
        f.append("RUN ").append(exec("sh", "-c", "mkdir -p /workspace && chown " + BUILD_USER + " /workspace")).append('\n');
        f.append("USER ").append(BUILD_USER).append('\n');
        f.append("WORKDIR /workspace\n");
        f.append("ENV HOME=/tmp GIT_TERMINAL_PROMPT=0\n");
        f.append("RUN ");
        if (credential) f.append("--mount=type=secret,id=").append(GIT_SECRET).append(' ');
        f.append(exec("sh", "-c", clone(credential), "clone", repositoryUrl, branch, revision)).append('\n');
        for (String run : runs(waitSeconds)) f.append("RUN --network=none ").append(exec("sh", "-c", run)).append('\n');
        if (f.length() > MAX_CONTAINERFILE) throw new InvalidWorkflowException("The workflow is too large to run");
        return f.toString();
    }

    /** The commands every build image contains; each is installed from the package of the same name when missing. */
    static final List<String> TOOLS = List.of("git", "make", "podman", "podman-compose");

    private static final String INSTALL_TOOLS = """
            missing=
            for tool in %1$s; do command -v "$tool" >/dev/null 2>&1 || missing="$missing $tool"; done
            [ -z "$missing" ] && exit 0
            echo "==> Installing$missing"
            if command -v apk >/dev/null 2>&1; then apk add --no-cache $missing
            elif command -v apt-get >/dev/null 2>&1; then apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq ca-certificates $missing && rm -rf /var/lib/apt/lists/*
            elif command -v dnf >/dev/null 2>&1; then dnf install -y -q $missing && dnf clean all
            elif command -v microdnf >/dev/null 2>&1; then microdnf install -y $missing && microdnf clean all
            elif command -v yum >/dev/null 2>&1; then yum install -y -q $missing && yum clean all
            elif command -v zypper >/dev/null 2>&1; then zypper --non-interactive install $missing && zypper clean
            else echo '==> Cannot install%2$s: the base image has no supported package manager'; exit 1
            fi || { rc=$?; echo "==> Could not install$missing"; exit "$rc"; }
            for tool in %1$s; do command -v "$tool" >/dev/null 2>&1 || { echo "==> $tool is missing after the installation"; exit 1; }; done
            """.formatted(String.join(" ", TOOLS), String.join(", ", TOOLS).replaceFirst("^", " "));

    /** Arguments: $1 repository URL, $2 branch, $3 expected revision. */
    private static String clone(boolean credential) {
        String helper = credential
                ? "-c 'credential.helper=!f() { echo username=oauth2; printf \"password=%s\\n\" \"$(cat /run/secrets/" + GIT_SECRET + ")\"; }; f' "
                : "";
        return "echo \"==> Checkout: $2\"\n"
                + "git -c credential.helper= " + helper + "clone --quiet --depth 1 --single-branch --branch \"$2\" -- \"$1\" . || exit $?\n"
                + "rev=$(git rev-parse HEAD) || exit $?\n"
                + "if [ \"$rev\" != \"$3\" ]; then echo \"==> Checkout failed: $2 is at $rev, expected $3\"; exit 1; fi\n";
    }

    private static final JsonMapper JSON = new JsonMapper();

    /** The exec (JSON) form, so nothing in the arguments is interpreted by the Containerfile parser or a shell. */
    private static String exec(String... args) {
        try {
            return JSON.writeValueAsString(args);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** All steps as one shell script, as the Containerfile's {@code RUN}s run them in order. */
    String script(int waitSeconds) {
        return String.join("", runs(waitSeconds));
    }

    /** The scripts of the Containerfile's step {@code RUN}s. */
    List<String> runs(int waitSeconds) {
        List<String> runs = new java.util.ArrayList<>();
        for (Job job : jobs) {
            String header = "echo " + quote("==> Job: " + job.job()) + "\n";
            if (job.steps().stream().anyMatch(step -> !step.isRun())) {
                StringBuilder s = new StringBuilder(header);
                for (Step step : job.steps()) s.append(step(step, waitSeconds));
                runs.add(s.toString());
            } else {
                for (int i = 0; i < job.steps().size(); i++) {
                    runs.add((i == 0 ? header : "") + step(job.steps().get(i), waitSeconds));
                }
            }
        }
        return runs;
    }

    /**
     * A {@code run} step runs in its own shell from {@code /workspace}. A {@code wait} step polls the kernel's TCP
     * tables for a listening socket on the port, so the image needs only {@code sh} and {@code grep}.
     */
    private static String step(Step step, int waitSeconds) {
        StringBuilder s = new StringBuilder();
        s.append("echo ").append(quote("==> Step: " + step.name())).append('\n');
        if (step.isRun()) {
            s.append("sh -c ").append(quote(step.run()))
                    .append(" || { rc=$?; echo ").append(quote("==> Step failed: " + step.name() + ", exit code"))
                    .append(" \"$rc\"; exit \"$rc\"; }\n");
        } else {
            String port = String.format("%04X", step.port());
            s.append("i=0; until grep -Eqs '^ *[0-9]+: [0-9A-F]+:").append(port)
                    .append(" [0-9A-F]+:[0-9A-F]+ 0A ' /proc/net/tcp /proc/net/tcp6; do")
                    .append(" i=$((i+1)); if [ \"$i\" -ge ").append(waitSeconds).append(" ]; then echo ")
                    .append(quote("==> Step failed: " + step.name() + ", port " + step.port()
                            + " not listening after " + waitSeconds + " seconds"))
                    .append("; exit 1; fi; sleep 1; done\n");
        }
        return s.toString();
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void requireName(String value, String field) {
        if (value == null || value.isBlank()) throw new InvalidWorkflowException(field + " is required");
        if (value.length() > MAX_NAME) throw new InvalidWorkflowException(field + " is longer than " + MAX_NAME + " characters");
    }

    /** The workflow file is not valid; the message is safe to show to the user. */
    public static class InvalidWorkflowException extends IllegalArgumentException {
        public InvalidWorkflowException(String message) {
            super(message);
        }
    }
}
