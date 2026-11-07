/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import { sanitizeHref } from "./sanitize-href";

export function resolveSafeNavigationHref(
  ...candidates: Array<string | null | undefined>
): string | undefined {
  for (const candidate of candidates) {
    const safeHref = sanitizeHref(candidate ?? undefined);
    if (safeHref) {
      return safeHref;
    }
  }

  return undefined;
}

export function openSafeNavigationHref(href: string | undefined): boolean {
  if (!href || typeof window === "undefined") {
    return false;
  }

  window.open(href, "_blank", "noopener,noreferrer");
  return true;
}
