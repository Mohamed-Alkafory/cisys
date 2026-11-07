/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import * as React from "react";

import { cn } from "@/lib/utils";

interface SwitchProps extends Omit<
  React.ComponentProps<"button">,
  "onChange" | "type"
> {
  checked?: boolean;
  onCheckedChange?: (checked: boolean) => void;
}

function Switch({
  checked = false,
  className,
  disabled,
  onCheckedChange,
  onClick,
  ...props
}: SwitchProps) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      disabled={disabled}
      data-slot="switch"
      data-state={checked ? "checked" : "unchecked"}
      className={cn(
        "peer inline-flex h-7 w-12 shrink-0 items-center rounded-full border border-transparent bg-muted transition-colors outline-none focus-visible:ring-2 focus-visible:ring-[var(--color-buttons-bg)] focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50 data-[state=checked]:bg-[var(--color-buttons-bg)]",
        className,
      )}
      onClick={(event) => {
        onClick?.(event);
        if (event.defaultPrevented || disabled) {
          return;
        }
        onCheckedChange?.(!checked);
      }}
      {...props}
    >
      <span
        data-slot="switch-thumb"
        className={cn(
          "pointer-events-none block size-5 translate-x-1 rounded-full bg-background shadow-sm ring-0 transition-transform data-[state=checked]:translate-x-6",
        )}
        data-state={checked ? "checked" : "unchecked"}
      />
    </button>
  );
}

export { Switch };
