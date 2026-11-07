/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

export function invariant(
  cond?: boolean,
  message?: string,
  ...args: string[]
): asserts cond {
  void args;
  if (cond) {
    return;
  }

  throw new Error(
    "Internal Lexical error: invariant() is meant to be replaced at compile " +
      "time. There is no runtime version. Error: " +
      message,
  );
}
