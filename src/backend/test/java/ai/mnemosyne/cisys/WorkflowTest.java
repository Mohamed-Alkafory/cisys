/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowTest {
    @TempDir Path temp;

    static final String VALID = """
            name: main branch

            environment:
              base: alpine:3.23

            jobs:
              - job: Build and test
                steps:
                  - name: Start
                    run: make platform-start
                  - name: Wait for 8180
                    wait: 8180
                  - name: Run
                    run: make run
                  - name: Stop
                    run: make platform-stop
            """;

    @Test
    void parsesWorkflow() {
        Workflow w = Workflow.parse(VALID);
        assertEquals("main branch", w.name());
        assertEquals("alpine:3.23", w.environment().base());
        assertEquals(1, w.jobs().size());
        Workflow.Job job = w.jobs().get(0);
        assertEquals("Build and test", job.job());
        assertEquals(List.of("Start", "Wait for 8180", "Run", "Stop"), job.steps().stream().map(Workflow.Step::name).toList());
        assertTrue(job.steps().get(0).isRun());
        assertEquals("make platform-start", job.steps().get(0).run());
        assertFalse(job.steps().get(1).isRun());
        assertEquals(8180, job.steps().get(1).port());
    }

    @Test
    void parsesRepositoryWorkflow() throws Exception {
        assertNotNull(Workflow.load(Path.of(".")));
    }

    @Test
    void loadsFromCheckout() throws Exception {
        Path file = temp.resolve(Workflow.PATH);
        Files.createDirectories(file.getParent());
        Files.writeString(file, VALID);
        assertEquals("main branch", Workflow.load(temp).name());
    }

    @Test
    void missingFileIsNull() throws Exception {
        assertNull(Workflow.load(temp));
    }

    @Test
    void rejectsSymbolicLink() throws Exception {
        Path target = Files.writeString(temp.resolve("elsewhere.yml"), VALID);
        Path file = temp.resolve(Workflow.PATH);
        Files.createDirectories(file.getParent());
        Files.createSymbolicLink(file, target);
        assertThrows(Workflow.InvalidWorkflowException.class, () -> Workflow.load(temp));
    }

    @Test
    void rejectsLargeFile() throws Exception {
        Path file = temp.resolve(Workflow.PATH);
        Files.createDirectories(file.getParent());
        Files.writeString(file, VALID + "#".repeat(Workflow.MAX_BYTES));
        assertThrows(Workflow.InvalidWorkflowException.class, () -> Workflow.load(temp));
    }

    @Test
    void rejectsInvalidDocuments() {
        for (String yaml : List.of(
                "",
                "name: [",
                VALID.replace("name: main branch", "name: \"\""),
                VALID.replace("  base: alpine:3.23", "  base: \"-rm\""),
                VALID.replace("environment:\n  base: alpine:3.23\n", ""),
                VALID.replace("    steps:", "    stepz:"),
                VALID.replace("        wait: 8180", "        wait: 70000"),
                VALID.replace("        wait: 8180", "        wait: 81.5"),
                VALID.replace("        wait: 8180", "        wait: 8180\n        run: true"),
                VALID.replace("        wait: 8180", "        timeout: 5"),
                VALID.replace("        run: make run", "        run: \" \""),
                VALID + "name: duplicate\n",
                "name: x\nenvironment:\n  base: alpine:3.23\njobs: []\n",
                "name: x\nenvironment:\n  base: alpine:3.23\njobs:\n  - job: j\n    steps: []\n")) {
            assertThrows(Workflow.InvalidWorkflowException.class, () -> Workflow.parse(yaml), yaml);
        }
    }

    private static Workflow workflow(String steps) {
        return Workflow.parse("name: w\nenvironment:\n  base: alpine:3.23\njobs:\n  - job: j\n    steps:\n" + steps);
    }

    private static BuildRunner.Result runScript(String script) throws Exception {
        return BuildRunner.command(List.of("sh", "-c", script), 30, 100_000);
    }

    @Test
    void scriptRunsStepsInOrderAndStopsAtFirstFailure() throws Exception {
        var r = runScript(workflow("""
                      - name: First
                        run: echo "it's one"
                      - name: Fails
                        run: exit 3
                      - name: Never
                        run: echo never
                """).script(Workflow.WAIT_SECONDS));
        assertEquals(3, r.code(), r.output());
        assertTrue(r.output().contains("==> Job: j\n==> Step: First\nit's one\n==> Step: Fails\n"), r.output());
        assertTrue(r.output().contains("==> Step failed: Fails, exit code 3"), r.output());
        assertFalse(r.output().contains("never"), r.output());
    }

    @Test
    void waitStepPassesOnceThePortListens() throws Exception {
        try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            var r = runScript(workflow("      - name: Wait\n        wait: " + server.getLocalPort()
                    + "\n      - name: After\n        run: echo ready\n").script(5));
            assertEquals(0, r.code(), r.output());
            assertTrue(r.output().contains("ready"), r.output());
        }
    }

    @Test
    void waitStepFailsWhenThePortNeverListens() throws Exception {
        int port;
        try (var probe = new java.net.ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        var r = runScript(workflow("      - name: Wait\n        wait: " + port + "\n").script(1));
        assertEquals(1, r.code(), r.output());
        assertTrue(r.output().contains("port " + port + " not listening after 1 seconds"), r.output());
    }

    @Test
    void rejectsContainerfilesTooLargeToRun() {
        // Each quote grows to five characters in the Containerfile ('\'' in JSON), so 13 steps exceed the limit.
        String step = "      - name: s\n        run: x" + "'".repeat(3999) + "\n";
        Workflow w = workflow(step.repeat(13));
        assertThrows(Workflow.InvalidWorkflowException.class, () -> w.containerfile(URL, "main", REV, false));
    }

    static final String URL = "https://github.com/example/app";
    static final String REV = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void qualifiesOfficialImages() {
        assertEquals("docker.io/library/alpine:3.23", Workflow.official("alpine:3.23"));
        assertEquals("docker.io/library/alpine", Workflow.official("alpine"));
        assertEquals("docker.io/valkey/valkey:8-alpine", Workflow.official("valkey/valkey:8-alpine"));
        assertEquals("quay.io/keycloak/keycloak:26", Workflow.official("quay.io/keycloak/keycloak:26"));
        assertEquals("localhost/tools:1", Workflow.official("localhost/tools:1"));
        assertEquals("registry:5000/tools:1", Workflow.official("registry:5000/tools:1"));
    }

    @Test
    void containerfileClonesTheBranchThenRunsEachStepWithoutNetwork() {
        List<String> lines = Workflow.parse(VALID.replace("        wait: 8180\n", "        run: make wait\n"))
                .containerfile(URL, "feature/x", REV, false).lines().toList();
        assertEquals("FROM docker.io/library/alpine:3.23", lines.get(0));
        assertEquals("USER 0", lines.get(1));
        assertTrue(lines.get(2).startsWith("RUN [\"sh\",\"-c\",\"command -v git"), lines.get(2));
        assertEquals("USER 65534:65534", lines.get(4));
        assertEquals("WORKDIR /workspace", lines.get(5));
        String clone = lines.get(7);
        assertTrue(clone.startsWith("RUN [\"sh\",\"-c\","), clone);
        assertTrue(clone.endsWith(",\"clone\",\"" + URL + "\",\"feature/x\",\"" + REV + "\"]"), clone);
        assertFalse(clone.contains("secret"), clone);
        List<String> steps = lines.subList(8, lines.size());
        assertEquals(4, steps.size(), String.join("\n", lines));
        assertTrue(steps.stream().allMatch(l -> l.startsWith("RUN --network=none [\"sh\",\"-c\",")), String.join("\n", steps));
        assertTrue(steps.get(0).contains("==> Job: Build and test") && steps.get(0).contains("make platform-start"), steps.get(0));
    }

    @Test
    void jobWithAWaitStepIsOneRun() {
        List<String> runs = Workflow.parse(VALID).runs(Workflow.WAIT_SECONDS);
        assertEquals(1, runs.size());
        assertTrue(runs.get(0).contains("make platform-start") && runs.get(0).contains("make platform-stop"), runs.get(0));
    }

    @Test
    void credentialIsMountedForTheCloneOnly() {
        String file = Workflow.parse(VALID).containerfile(URL, "main", REV, true);
        assertEquals(1, file.split("--mount=type=secret,id=" + Workflow.GIT_SECRET + " ", -1).length - 1, file);
        assertTrue(file.contains("/run/secrets/" + Workflow.GIT_SECRET), file);
    }

    @Test
    void argumentsCannotEscapeTheExecForm() {
        String file = workflow("      - name: \"a\\\"]\\nRUN evil\"\n        run: echo \"\\\\\\n\"\n")
                .containerfile(URL, "main", REV, false);
        assertEquals(9, file.lines().count(), file);
        assertTrue(file.lines().noneMatch(l -> l.startsWith("RUN evil")), file);
    }

    @Test
    void reportsUnknownKey() {
        var e = assertThrows(Workflow.InvalidWorkflowException.class, () -> Workflow.parse(VALID.replace("    steps:", "    stepz:")));
        assertTrue(e.getMessage().contains("stepz"), e.getMessage());
    }
}
