package ai.mnemosyne.cisys;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
@QuarkusTest @TestProfile(RunnerSmokeTest.Profile.class)
@EnabledIfEnvironmentVariable(named="CISYS_RUNNER_SMOKE", matches="true")
class RunnerSmokeTest {
 public static class Profile implements QuarkusTestProfile {
  public Map<String,String> getConfigOverrides(){return Map.of("cisys.runner.enabled","true");}
 }
 @Inject BuildRunner runner;
 @Test void publicRepositoryBuildCompletes() throws Exception {
  Long project=QuarkusTransaction.requiringNew().call(()->{Project p=new Project();p.name="smoke";p.repositoryUrl="https://github.com/octocat/Hello-World.git";p.ownerSubject="test";p.persist();return p.id;});
  Build created=runner.submit(project,"test","https://github.com/octocat/Hello-World.git",new BuildResource.RunRequest("master","alpine:3.23","test -f README && echo cisys-green",null));
  long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(2);
  while(System.nanoTime()<deadline){Build b=QuarkusTransaction.requiringNew().call(()->Build.findById(created.id));if(!"RUNNING".equals(b.status)){assertEquals("PASSED",b.status,b.output);assertTrue(b.output.contains("cisys-green"));assertNotNull(b.revision);return;}Thread.sleep(500);}
  fail("Build did not finish within two minutes");
 }
}
