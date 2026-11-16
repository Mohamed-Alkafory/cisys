/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import java.util.List;
import java.util.Optional;

/**
 * Where users, their passwords and their cisys role are kept: Keycloak, see {@link KeycloakUsers}.
 */
public interface UserDirectory {
  /** The cisys realm roles, from least to most privileged. */
  List<String> ROLES = List.of("member", "lead", "admin");

  /** A user, identified by the subject that appears in tokens. */
  record User(
      String id,
      String username,
      String email,
      String firstName,
      String lastName,
      boolean enabled) {}

  /** The username or email is already taken. */
  class ConflictException extends RuntimeException {
    /** Creates the exception with the reason to show. */
    public ConflictException(String message) {
      super(message);
    }
  }

  /** The directory refused the request, for example a password that breaks the realm's policy. */
  class RejectedException extends RuntimeException {
    /** Creates the exception with the reason to show. */
    public RejectedException(String message) {
      super(message);
    }
  }

  /** The directory could not be reached or answered with an error. */
  class UnavailableException extends RuntimeException {
    /** Creates the exception with the cause to log. */
    public UnavailableException(String message) {
      super(message);
    }
  }

  /** Users whose username, email or name contains {@code search}; all users when it is blank. */
  List<User> search(String search, int first, int max);

  /** How many users {@link #search} matches. */
  int count(String search);

  /** The user, when it exists. */
  Optional<User> find(String id);

  /** The users that have {@code role}. */
  List<User> inRole(String role);

  /** The user's highest cisys role, or null when it has none. */
  String role(String id);

  /** Creates an enabled user with a temporary password and returns its id. */
  String create(User user, String password);

  /** Changes the user's email, names and whether it is enabled. */
  void update(User user);

  /** Gives the user {@code role} and removes its other cisys roles. */
  void setRole(String id, String role);

  /** Sets a temporary password, which the user changes when signing in next. */
  void setPassword(String id, String password);

  /** Deletes the user. */
  void delete(String id);
}
