package ai.mnemosyne.cisys;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
@QuarkusTest class BuildResourceTest {
 private Long project(boolean priv){return QuarkusTransaction.requiringNew().call(()->{Project p=new Project();p.name="example";p.repositoryUrl="https://github.com/octocat/Hello-World.git";p.ownerSubject="owner-id";p.privateRepository=priv;p.persist();return p.id;});}
 static final String REQUEST="{\"branch\":\"main\",\"image\":\"alpine:3.23\",\"command\":\"echo hello\"}";
 @Test @TestSecurity(user="member",roles="member") @JwtSecurity(claims=@Claim(key="sub",value="other-id")) void privateLogsAreHidden(){Long id=project(true);given().get("/api/projects/"+id+"/builds").then().statusCode(404);}
 @Test @TestSecurity(user="member",roles="member") @JwtSecurity(claims=@Claim(key="sub",value="other-id")) void memberCanViewPublicButCannotTrigger(){Long id=project(false);given().get("/api/projects/"+id+"/builds").then().statusCode(200);given().contentType("application/json").body(REQUEST).post("/api/projects/"+id+"/builds").then().statusCode(403);}
 @Test @TestSecurity(user="lead",roles="lead") @JwtSecurity(claims=@Claim(key="sub",value="owner-id")) void ownerCanSeePrivateButRunnerIsDisabled(){Long id=project(true);given().get("/api/projects/"+id+"/builds").then().statusCode(200);given().contentType("application/json").body(REQUEST).post("/api/projects/"+id+"/builds").then().statusCode(503);}
 @Test @TestSecurity(user="lead",roles="lead") @JwtSecurity(claims=@Claim(key="sub",value="owner-id")) void rejectInvalidBranch(){Long id=project(true);given().contentType("application/json").body(REQUEST.replace("main","branch.lock")).post("/api/projects/"+id+"/builds").then().statusCode(400);}
 @Test @TestSecurity(user="lead",roles="lead") @JwtSecurity(claims=@Claim(key="sub",value="owner-id")) void leadCannotUseGlobalCredentials(){Long id=project(true);given().contentType("application/json").body(REQUEST.replace("\"command\":", "\"credentialRef\":\"CISYS_GIT_SECRET\",\"command\":" )).post("/api/projects/"+id+"/builds").then().statusCode(403);}
 @Test void processOutputIsBounded()throws Exception{var r=BuildRunner.command(List.of("sh","-c","head -c 500000 /dev/zero"),10,1024);assertEquals(0,r.code());assertTrue(r.output().endsWith("[output truncated]\n"));assertTrue(r.output().length()<1100);}
}
