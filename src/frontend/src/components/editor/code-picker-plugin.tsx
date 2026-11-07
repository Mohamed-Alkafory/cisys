/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import { $createCodeNode } from "@lexical/code";
import { $setBlocksType } from "@lexical/selection";
import { $getSelection, $isRangeSelection } from "lexical";

import { CodeIcon } from "lucide-react";

import { ComponentPickerOption } from "@/components/editor/component-picker-option";

export function CodePickerPlugin() {
  return new ComponentPickerOption("Code", {
    icon: <CodeIcon className="size-4" />,
    keywords: ["javascript", "python", "js", "codeblock"],
    onSelect: (_, editor) =>
      editor.update(() => {
        const selection = $getSelection();

        if ($isRangeSelection(selection)) {
          if (selection.isCollapsed()) {
            $setBlocksType(selection, () => $createCodeNode());
          } else {
            // Will this ever happen?
            const textContent = selection.getTextContent();
            const codeNode = $createCodeNode();
            selection.insertNodes([codeNode]);
            selection.insertRawText(textContent);
          }
        }
      }),
  });
}
