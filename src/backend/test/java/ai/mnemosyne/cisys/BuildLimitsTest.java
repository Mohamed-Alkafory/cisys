/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuildLimitsTest {
  private static final int[] LIMITS = {0, 0, 1024, 2, 256};

  @Test
  void limitsApplyToBothRuntimes() {
    assertEquals(
        List.of(
            "--memory=1024m",
            "--cpu-period=100000",
            "--cpu-quota=200000",
            "--ulimit=nproc=256:256"),
        BuildRunner.resourceLimits(LIMITS[2], LIMITS[3], LIMITS[4]));
  }

  @Test
  void podmanBuildKeepsItsRuntimeFlags() {
    BuildRunner runner = new BuildRunner();
    runner.runtime = "podman";
    List<String> build =
        runner.buildCommand(Path.of("/tmp/Containerfile"), "cisys-build-1", LIMITS);
    assertEquals("podman", build.get(0));
    assertEquals("build", build.get(1));
    assertTrue(build.contains("--pull=never"), build.toString());
    assertTrue(build.contains("--layers=false"), build.toString());
    assertTrue(build.contains("--security-opt=no-new-privileges"), build.toString());
    assertFalse(build.contains("--network=none"), build.toString());
    assertTrue(build.containsAll(BuildRunner.resourceLimits(1024, 2, 256)), build.toString());
  }

  @Test
  void dockerBuildUsesOnlyFlagsTheLegacyBuilderAccepts() {
    BuildRunner runner = new BuildRunner();
    runner.runtime = "docker";
    List<String> build =
        runner.buildCommand(Path.of("/tmp/Containerfile"), "cisys-build-1", LIMITS);
    assertEquals("docker", build.get(0));
    assertFalse(build.contains("--pull=never"), build.toString());
    assertFalse(build.contains("--progress=plain"), build.toString());
    assertFalse(build.contains("--layers=false"), build.toString());
    assertFalse(build.contains("--security-opt=no-new-privileges"), build.toString());
    assertTrue(build.contains("--force-rm"), build.toString());
    assertTrue(build.containsAll(BuildRunner.resourceLimits(1024, 2, 256)), build.toString());
  }

  @Test
  void dockerBuildsDisableBuildKit() {
    assertEquals("0", BuildRunner.LEGACY_BUILDER.get("DOCKER_BUILDKIT"));
    assertEquals(java.util.Set.of("DOCKER_BUILDKIT"), BuildRunner.LEGACY_BUILDER.keySet());
  }

  @Test
  void theSecondDockerStageSharesTheBuildBudget() {
    assertTrue(BuildRunner.remainingSeconds(System.nanoTime()) >= 1);
    assertTrue(
        BuildRunner.remainingSeconds(System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos())
            > 1);
  }

  /** Docker 29 legacy builder output of a build whose last step fails. */
  private static final String FAILED_STEP =
      "Sending build context to Docker daemon  1.583kB\n"
          + "Step 1/3 : FROM cisys-build-71-prepare\n"
          + " ---> 5ba30cb78a4a\n"
          + "Step 2/3 : RUN echo step1 > /s1\n"
          + " ---> Running in cb8cc0880735\n"
          + " ---> Removed intermediate container cb8cc0880735\n"
          + " ---> 2583e14c3721\n"
          + "Step 3/3 : RUN exit 3\n"
          + " ---> Running in c448618b7d3d\n"
          + " ---> Removed intermediate container c448618b7d3d\n"
          + "The command '/bin/sh -c exit 3' returned a non-zero code: 3\n";

  private static BuildRunner.StepImages feed(String output, ByteArrayOutputStream log) {
    BuildRunner.StepImages images = new BuildRunner.StepImages(log::write);
    byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
    // In two pieces, split inside a line, as the reader may deliver it.
    int half = output.indexOf("2583e1") + 3;
    images.append(bytes, 0, half);
    images.append(bytes, half, bytes.length - half);
    return images;
  }

  @Test
  void stepImagesFindTheLastCommittedStep() {
    ByteArrayOutputStream log = new ByteArrayOutputStream();
    assertEquals("2583e14c3721", feed(FAILED_STEP, log).last());
    assertEquals(FAILED_STEP, log.toString(StandardCharsets.UTF_8));
  }

  @Test
  void stepImagesIgnoreTheImageBuiltFrom() {
    String firstStepFails =
        "Step 1/2 : FROM cisys-build-73-prepare\n"
            + " ---> c43c4455e81c\n"
            + "Step 2/2 : RUN exit 5\n"
            + " ---> Running in 0a1b2c3d4e5f\n"
            + "The command '/bin/sh -c exit 5' returned a non-zero code: 5\n";
    assertNull(feed(firstStepFails, new ByteArrayOutputStream()).last());
  }

  private static BuildRunner runnerWithDangling(String dangling, List<List<String>> calls) {
    BuildRunner runner = new BuildRunner();
    runner.runtime = "docker";
    runner.commands =
        (args, timeout, limit) -> {
          calls.add(args);
          return new BuildRunner.Result(0, false, args.get(1).equals("images") ? dangling : "");
        };
    return runner;
  }

  @Test
  void danglingStepImageIsRemoved() {
    List<List<String>> calls = new ArrayList<>();
    runnerWithDangling(
            "sha256:2583e14c37215b851fabe3d79b10497318132d2b0489b6bf8d2d919c274a3b92\n", calls)
        .removeStepImage(feed(FAILED_STEP, new ByteArrayOutputStream()));
    assertEquals(List.of("docker", "rmi", "2583e14c3721"), calls.get(calls.size() - 1));
  }

  @Test
  void stepImageInUseIsKept() {
    List<List<String>> calls = new ArrayList<>();
    runnerWithDangling(
            "sha256:9999999999995b851fabe3d79b10497318132d2b0489b6bf8d2d919c274a3b92\n", calls)
        .removeStepImage(feed(FAILED_STEP, new ByteArrayOutputStream()));
    assertFalse(calls.stream().anyMatch(c -> c.get(1).equals("rmi")), calls.toString());
  }

  @Test
  void passingOrUnstartedBuildRunsNothing() {
    List<List<String>> calls = new ArrayList<>();
    BuildRunner runner = runnerWithDangling("", calls);
    runner.removeStepImage(null);
    runner.removeStepImage(new BuildRunner.StepImages((b, o, l) -> {}));
    assertTrue(calls.isEmpty(), calls.toString());
  }
}
