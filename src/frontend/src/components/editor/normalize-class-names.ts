/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

export default function normalizeClassNames(
  ...classNames: Array<typeof undefined | boolean | null | string>
): Array<string> {
  const rval = [];
  for (const className of classNames) {
    if (className && typeof className === "string") {
      for (const [s] of Array.from(className.matchAll(/\S+/g))) {
        rval.push(s);
      }
    }
  }
  return rval;
}
