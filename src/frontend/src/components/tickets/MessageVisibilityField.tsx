/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import type { Role } from "../../types/app";
import { Switch } from "../ui/switch";

function privateAudienceLabel(role?: Role): string {
  return role === "support" || role === "tam"
    ? "Support and TAM"
    : "User and Superuser";
}

export default function MessageVisibilityField({
  role,
  checked,
  onCheckedChange,
  inline = false,
}: {
  role?: Role;
  checked: boolean;
  onCheckedChange: (checked: boolean) => void;
  inline?: boolean;
}) {
  if (inline) {
    return (
      <div className="flex items-center justify-end gap-3">
        <span className="text-sm font-semibold text-[var(--color-section-header)]">
          {checked ? "Public" : "Private"}
        </span>
        <Switch
          checked={checked}
          onCheckedChange={onCheckedChange}
          aria-label={`Message visibility: ${checked ? "public" : "private"}`}
        />
      </div>
    );
  }

  return (
    <div className="flex items-center justify-between gap-4 rounded-xl border border-border px-4 py-3">
      <div className="space-y-1">
        <div className="text-sm font-semibold text-[var(--color-section-header)]">
          Public
        </div>
        <p className="text-sm text-muted-foreground">
          On shows everyone on the ticket. Off limits the message to{" "}
          <span className="font-medium text-foreground">
            {privateAudienceLabel(role)}
          </span>
          .
        </p>
      </div>
      <div className="flex flex-col items-end gap-2">
        <Switch
          checked={checked}
          onCheckedChange={onCheckedChange}
          aria-label="Message visibility"
        />
        <span className="text-xs font-medium text-muted-foreground">
          {checked ? "Public" : "Private"}
        </span>
      </div>
    </div>
  );
}
