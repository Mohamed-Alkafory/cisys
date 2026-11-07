/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import { $setBlocksType } from "@lexical/selection";
import {
  $createParagraphNode,
  $getSelection,
  $isRangeSelection,
} from "lexical";

import { TextIcon } from "lucide-react";

import { ComponentPickerOption } from "@/components/editor/component-picker-option";

export function ParagraphPickerPlugin() {
  return new ComponentPickerOption("Paragraph", {
    icon: <TextIcon className="size-4" />,
    keywords: ["normal", "paragraph", "p", "text"],
    onSelect: (_, editor) =>
      editor.update(() => {
        const selection = $getSelection();
        if ($isRangeSelection(selection)) {
          $setBlocksType(selection, () => $createParagraphNode());
        }
      }),
  });
}
