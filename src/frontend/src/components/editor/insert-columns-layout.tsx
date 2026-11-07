/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import { Columns3Icon } from "lucide-react";

import { useToolbarContext } from "@/components/editor/toolbar-context";
import { InsertLayoutDialog } from "@/components/editor/layout-plugin";
import { DropdownMenuItem } from "@/components/ui/dropdown-menu";

export function InsertColumnsLayout() {
  const { activeEditor, showModal } = useToolbarContext();

  return (
    <DropdownMenuItem
      onClick={() =>
        showModal("Insert Columns Layout", (onClose) => (
          <InsertLayoutDialog activeEditor={activeEditor} onClose={onClose} />
        ))
      }
    >
      <div className="flex items-center gap-1">
        <Columns3Icon className="size-4" />
        <span>Columns Layout</span>
      </div>
    </DropdownMenuItem>
  );
}
