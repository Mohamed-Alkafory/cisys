/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps users in memory in place of Keycloak, so no test needs a Keycloak server. */
@Alternative
@Priority(1)
@ApplicationScoped
public class TestUserDirectory implements UserDirectory {
  static final Map<String, User> USERS = new ConcurrentHashMap<>();
  static final Map<String, String> ROLES_BY_ID = new ConcurrentHashMap<>();
  static final Map<String, String> PASSWORDS = new ConcurrentHashMap<>();

  static void reset() {
    USERS.clear();
    ROLES_BY_ID.clear();
    PASSWORDS.clear();
  }

  static void add(String id, String username, String role) {
    USERS.put(id, new User(id, username, null, null, null, true));
    if (role != null) {
      ROLES_BY_ID.put(id, role);
    }
  }

  @Override
  public List<User> search(String search, int first, int max) {
    return matching(search).stream().skip(first).limit(max).toList();
  }

  @Override
  public int count(String search) {
    return matching(search).size();
  }

  private List<User> matching(String search) {
    String s = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
    return USERS.values().stream()
        .filter(u -> u.username().contains(s))
        .sorted(Comparator.comparing(User::username))
        .toList();
  }

  @Override
  public Optional<User> find(String id) {
    return Optional.ofNullable(USERS.get(id));
  }

  @Override
  public List<User> inRole(String role) {
    return USERS.values().stream().filter(u -> role.equals(ROLES_BY_ID.get(u.id()))).toList();
  }

  @Override
  public String role(String id) {
    return ROLES_BY_ID.get(id);
  }

  @Override
  public String create(User user, String password) {
    if (USERS.values().stream().anyMatch(u -> u.username().equals(user.username()))) {
      throw new ConflictException("User exists with same username");
    }
    if ("weak".equals(password)) {
      throw new RejectedException("Password policy not met");
    }
    String id = UUID.randomUUID().toString();
    USERS.put(
        id, new User(id, user.username(), user.email(), user.firstName(), user.lastName(), true));
    PASSWORDS.put(id, password);
    return id;
  }

  @Override
  public void update(User user) {
    USERS.put(user.id(), user);
  }

  @Override
  public void setRole(String id, String role) {
    ROLES_BY_ID.put(id, role);
  }

  @Override
  public void setPassword(String id, String password) {
    PASSWORDS.put(id, password);
  }

  @Override
  public void delete(String id) {
    USERS.remove(id);
    ROLES_BY_ID.remove(id);
  }
}
