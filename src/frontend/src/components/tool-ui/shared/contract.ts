/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import { z } from "zod";
import { parseWithSchema, safeParseWithSchema } from "./parse";

export interface ToolUiContract<T> {
  schema: z.ZodType<T>;
  parse: (input: unknown) => T;
  safeParse: (input: unknown) => T | null;
}

export function defineToolUiContract<T>(
  componentName: string,
  schema: z.ZodType<T>,
): ToolUiContract<T> {
  return {
    schema,
    parse: (input: unknown) => parseWithSchema(schema, input, componentName),
    safeParse: (input: unknown) => safeParseWithSchema(schema, input),
  };
}
