/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 *
 *   THE DOCUMENTATION AND THE SOFTWARE DISTRIBUTED IN CONNECTION WITH THIS
 *   DOCUMENTATION ARE PROVIDED "AS IS" WITHOUT ANY WARRANTY OF ANY KIND.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Database-level integrity checks for the build/project schema. These tests talk to the database
 * directly with native SQL, deliberately bypassing the application layer (Java enums, Panache
 * entities), so they exercise the constraints and triggers the migration creates.
 */
@io.quarkus.test.junit.QuarkusTest
class BuildIntegrityTest {

  /**
   * The shared test database must not be left with synthetic rows behind: a queued build of ours
   * would be picked up by another class's runner.dispatch() and starve it.
   */
  @org.junit.jupiter.api.AfterEach
  void cleanSyntheticData() {
    update("delete from build where requestedby = 'integrity-user'");
    update("delete from project where ownersubject = 'integrity-owner'");
  }

  // ---------- helpers ----------

  /** Runs the query, expects a single numeric row, returns it as a long. */
  private static long scalar(String sql, Object... params) {
    var query = Build.getEntityManager().createNativeQuery(sql);
    for (int i = 0; i < params.length; i++) {
      query.setParameter(i + 1, params[i]);
    }
    return ((Number) query.getSingleResult()).longValue();
  }

  private static void update(String sql, Object... params) {
    io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
        .run(() -> {
          var query = Build.getEntityManager().createNativeQuery(sql);
          for (int i = 0; i < params.length; i++) {
            query.setParameter(i + 1, params[i]);
          }
          query.executeUpdate();
        });
  }

  private static String indexDef(String name) {
    return ((String)
            Build.getEntityManager()
                .createNativeQuery(
                    "select pg_get_indexdef(i.indexrelid) from pg_index i"
                        + " join pg_class c on c.oid = i.indexrelid where c.relname = ?1")
                .setParameter(1, name)
                .getSingleResult())
        .replaceAll("\\s+", " ");
  }

  private static String constraintDef(String name) {
    return ((String)
            Build.getEntityManager()
                .createNativeQuery(
                    "select pg_get_constraintdef(oid) from pg_constraint where conname = ?1")
                .setParameter(1, name)
                .getSingleResult())
        .replaceAll("\\s+", " ");
  }

  /**
   * The set of single-quoted literals in a constraint definition (e.g. the allowed enum values).
   */
  private static java.util.Set<String> arrayValues(String def) {
    var matcher = java.util.regex.Pattern.compile("'((?:[^']|'')*)'").matcher(def);
    var values = new java.util.LinkedHashSet<String>();
    while (matcher.find()) {
      values.add(matcher.group(1).replace("''", "'"));
    }
    return values;
  }

  /** Creates a project with the given buildcounter; name/url are unique per call. */
  private static Long newProject(long buildcounter) {
    String n = "integrity-" + UUID.randomUUID();
    return ((Number)
            Build.getEntityManager()
                .createNativeQuery(
                    "insert into project"
                        + " (id, name, ownersubject, privaterepository, repositoryurl, buildcounter)"
                        + " values (nextval('project_seq'), ?1, 'integrity-owner', false, ?2, ?3)"
                        + " returning id")
                .setParameter(1, n)
                .setParameter(2, "https://github.com/octocat/" + n + ".git")
                .setParameter(3, buildcounter)
                .getSingleResult())
        .longValue();
  }

  /** Inserts a build via plain SQL; use for valid cases only. */
  private static Long newBuild(Long projectId, String status, String trigger, long number) {
    return insertBuild(projectId, status, trigger, number, null);
  }

  private static Long insertBuild(
      Long projectId, String status, String trigger, long number, Integer exitCode) {
    return ((Number)
            Build.getEntityManager()
                .createNativeQuery(
                    "insert into build"
                        + " (id, finishedat, projectid, requestedby, revision, startedat, status,"
                        + "  branch, logtruncated, exitcode, pinned, cancelrequested,"
                        + "  requestedat, number, trigger)"
                        + " values (nextval('build_seq'), null, ?1, 'integrity-user', 'rev', now(),"
                        + "  ?2, 'main', false, ?3, false, false, now(), ?4, ?5)"
                        + " returning id")
                .setParameter(1, projectId)
                .setParameter(2, status)
                .setParameter(3, exitCode)
                .setParameter(4, number)
                .setParameter(5, trigger)
                .getSingleResult())
        .longValue();
  }

  /** True when the runnable fails with the given SQLState (e.g. 23514, 23503). */
  private static boolean violates(String sqlState, Runnable action) {
    try {
      io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(action::run);
      return false;
    } catch (RuntimeException e) {
      for (Throwable cause = e; cause != null; cause = cause.getCause()) {
        if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
            && sqlState.equals(violation.getSQLState())) {
          return true;
        }
      }
      e.printStackTrace();
      return false;
    }
  }

  // ---------- A. index and migration objects ----------

  @Test
  void branchNumberAndRetentionIndexesExist() {
    assertTrue(
        indexDef("build_project_branch_number").contains("(projectid, branch, number DESC)"),
        "branch index definition: " + indexDef("build_project_branch_number"));
    // pg_get_indexdef prints the built-in COALESCE keyword in upper case; compare loosely.
    String retention = indexDef("build_retention").toLowerCase(java.util.Locale.ROOT);
    for (String token :
        new String[] {
          "coalesce(finishedat, startedat)",
          "desc nulls last",
          "projectid",
          "id desc",
          "pinned",
          "passed",
          "failed",
          "cancelled"
        }) {
      assertTrue(
          retention.contains(token),
          "retention index missing token: " + token + " in definition: " + retention);
    }
  }

  @Test
  void foreignKeyExistsWithCascade() {
    String def = constraintDef("build_project_fk");
    assertTrue(def.contains("FOREIGN KEY"), def);
    assertTrue(def.contains("(projectid)"), def);
    assertTrue(def.contains("REFERENCES project(id)"), def);
    assertTrue(def.contains("ON DELETE CASCADE"), def);
  }

  @Test
  void checkConstraintsExistWithExpectedValues() {
    assertEquals(
        java.util.Set.of("QUEUED", "RUNNING", "PASSED", "FAILED", "CANCELLED"),
        arrayValues(constraintDef("build_status_check")),
        "build_status_check must allow exactly the Java Status enum values");

    assertEquals(
        java.util.Set.of("MANUAL", "POLL", "PULL_REQUEST"),
        arrayValues(constraintDef("build_trigger_check")),
        "build_trigger_check must allow exactly the Java Trigger enum values");

    assertTrue(
        constraintDef("build_number_check").contains(">= 0"), constraintDef("build_number_check"));
    assertTrue(
        constraintDef("project_buildcounter_check").contains(">= 0"),
        constraintDef("project_buildcounter_check"));
  }

  /**
   * Weak but honest check: every @QuarkusTest class boots the application against a fresh
   * PostgreSQL instance, and Flyway applies R__schema.sql on each boot — the suite passing at all
   * is the strong signal that the migration is re-runnable. This asserts the history table exists
   * and at least one migration applied successfully. It does NOT verify that the current migration
   * was re-applied; no test can express that across classes.
   */
  @Test
  void repeatableMigrationWasApplied() {
    assertTrue(
        scalar("select count(*) from flyway_schema_history where success") >= 1,
        "flyway_schema_history has no successful migration");
  }

  // ---------- B. CHECK constraints, enforced by the database ----------

  @Test
  void validStatusesAndTriggersAreAccepted() {
    Long p = newProject(0);
    long n = 1;
    for (String status : new String[] {"QUEUED", "RUNNING", "PASSED", "FAILED", "CANCELLED"}) {
      newBuild(p, status, "MANUAL", n++);
    }
    for (String trigger : new String[] {"MANUAL", "POLL", "PULL_REQUEST"}) {
      newBuild(p, "PASSED", trigger, n++);
    }
  }

  @Test
  void invalidStatusesAndTriggersAreRejected() {
    Long p = newProject(0);
    assertTrue(violates("23514", () -> newBuild(p, "BOGUS", "MANUAL", 81)));
    assertTrue(violates("23514", () -> newBuild(p, "PASSED", "SMOKE", 82)));
    assertTrue(violates("23514", () -> newBuild(p, "PASSED", "", 83)));
    assertTrue(violates("23514", () -> newBuild(p, "passed", "MANUAL", 84)));
  }

  @Test
  void negativeCountersAreRejectedAndExitCodeIsNot() {
    Long p = newProject(0);
    assertTrue(violates("23514", () -> newBuild(p, "PASSED", "MANUAL", -1)));
    assertTrue(violates("23514", () -> newProject(-1)));
    // exitcode may legally be negative; the migration deliberately does not constrain it.
    Long id = insertBuild(p, "PASSED", "MANUAL", 90, -3);
    assertEquals(-3, scalar("select exitcode from build where id = ?1", id));
  }

  @Test
  void nullsAreAcceptedAccordingToSchemaSemantics() {
    Long p = newProject(0);
    // status is nullable in the schema; a NULL status must pass the CHECK.
    Long id = insertBuild(p, null, "MANUAL", 101, null);
    assertEquals(1, scalar("select count(*) from build where id = ?1", id));
  }

  // ---------- C. foreign key and cascade ----------

  @Test
  void deletingProjectDeletesItsBuilds() {
    Long p = newProject(7);
    Long build = newBuild(p, "PASSED", "MANUAL", 201);
    update("delete from project where id = ?1", p);
    assertEquals(0, scalar("select count(*) from build where projectid = ?1", p));
    assertEquals(0, scalar("select count(*) from build where id = ?1", build));
  }

  @Test
  void insertingBuildForMissingProjectIsRejected() {
    assertTrue(violates("23503", () -> newBuild(987654321L, "PASSED", "MANUAL", 301)));
  }

  @Test
  void nullProjectIdsKeepExistingBehavior() {
    Long build = insertBuild(null, "PASSED", "MANUAL", 401, null);
    Long p = newProject(0);
    update("delete from project where id = ?1", p);
    assertEquals(1, scalar("select count(*) from build where id = ?1", build));
    update("delete from build where id = ?1", build);
  }

  @Test
  void logLargeObjectIsUnlinkedOnCascadeDelete() {
    Long p = newProject(3);
    Long build = newBuild(p, "PASSED", "MANUAL", 501);
    long oid =
        ((Number)
                Build.getEntityManager()
                    .createNativeQuery("select lo_from_bytea(0, convert_to('log line', 'UTF8'))")
                    .getSingleResult())
            .longValue();
    update("update build set logoid = cast(?1 as oid) where id = ?2", oid, build);
    assertEquals(
        1,
        scalar("select count(*) from pg_largeobject_metadata where oid = cast(?1 as oid)", oid),
        "large object must exist before the cascade delete");
    update("delete from project where id = ?1", p);
    assertEquals(
        0, scalar("select count(*) from pg_largeobject_metadata where oid = cast(?1 as oid)", oid));
    assertEquals(0, scalar("select count(*) from build where id = ?1", build));
  }
}
