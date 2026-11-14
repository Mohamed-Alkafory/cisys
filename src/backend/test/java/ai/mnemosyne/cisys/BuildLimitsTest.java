/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
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
}
