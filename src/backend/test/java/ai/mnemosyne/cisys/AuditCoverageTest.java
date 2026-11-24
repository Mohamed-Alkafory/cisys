/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the rule that every mutating JAX-RS endpoint is audited. Build comments are the only
 * deliberate exception: the audit catalog says comments and other ordinary collaboration actions
 * are not recorded. Adding a new POST, PUT, PATCH or DELETE without {@link Audited} fails here, so
 * the decision has to be made explicitly.
 */
class AuditCoverageTest {
  private static final Set<Class<? extends Annotation>> MUTATING =
      Set.of(POST.class, PUT.class, PATCH.class, DELETE.class);

  private static final Set<String> ALLOWED_UNAUDITED =
      Set.of(
          "ai.mnemosyne.cisys.BuildCommentResource#create",
          "ai.mnemosyne.cisys.BuildCommentResource#update",
          "ai.mnemosyne.cisys.BuildCommentResource#delete");

  @Test
  void everyMutatingEndpointIsAuditedOrAllowlisted() throws Exception {
    List<String> offenders = new ArrayList<>();
    int found = 0;
    for (Class<?> resource : resourceClasses()) {
      for (Method method : resource.getDeclaredMethods()) {
        if (method.isSynthetic() || method.isBridge() || !mutating(method)) {
          continue;
        }
        found++;
        String key = resource.getName() + "#" + method.getName();
        if (!method.isAnnotationPresent(Audited.class) && !ALLOWED_UNAUDITED.contains(key)) {
          offenders.add(key);
        }
      }
    }
    assertTrue(found >= 30, "Expected to scan the resource endpoints, found " + found);
    assertTrue(
        offenders.isEmpty(),
        "Mutating endpoints must be @Audited or allowlisted in AuditCoverageTest: " + offenders);
  }

  private static boolean mutating(Method method) {
    return MUTATING.stream().anyMatch(method::isAnnotationPresent);
  }

  private static List<Class<?>> resourceClasses() throws IOException, URISyntaxException {
    URL classesUrl = AuditResource.class.getProtectionDomain().getCodeSource().getLocation();
    if (classesUrl == null || !"file".equals(classesUrl.getProtocol())) {
      throw new IllegalStateException("Expected an exploded class directory, got " + classesUrl);
    }
    Path directory = Path.of(classesUrl.toURI()).resolve("ai/mnemosyne/cisys");
    List<Class<?>> classes = new ArrayList<>();
    try (Stream<Path> files = Files.list(directory)) {
      files
          .map(path -> path.getFileName().toString())
          .filter(name -> name.endsWith(".class") && !name.contains("$"))
          .map(name -> load("ai.mnemosyne.cisys." + name.substring(0, name.length() - 6)))
          .filter(type -> type.isAnnotationPresent(jakarta.ws.rs.Path.class))
          .forEach(classes::add);
    }
    return classes;
  }

  private static Class<?> load(String name) {
    try {
      return Class.forName(name, false, AuditCoverageTest.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("Could not load " + name, e);
    }
  }
}
