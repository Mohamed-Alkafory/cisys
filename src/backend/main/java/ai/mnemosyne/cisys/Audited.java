/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a mutation for the audit failure filter. Successful calls record their own event, with
 * target details the annotation cannot know; the filter records an authenticated {@code 403} as
 * {@link AuditOutcome#DENIED} and a {@code 409} or {@code 502} as {@link AuditOutcome#FAILURE}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Audited {
  /** The action code recorded for the event. */
  AuditAction action();

  /** The kind of object the action works on. */
  AuditTargetType target();

  /** A short, non-sensitive description used for denied and failed calls. */
  String summary();
}
