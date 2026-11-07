/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import { $createQuoteNode } from "@lexical/rich-text";
import { $setBlocksType } from "@lexical/selection";
import { $getSelection, $isRangeSelection } from "lexical";

import { QuoteIcon } from "lucide-react";

import { ComponentPickerOption } from "@/components/editor/component-picker-option";

export function QuotePickerPlugin() {
  return new ComponentPickerOption("Quote", {
    icon: <QuoteIcon className="size-4" />,
    keywords: ["block quote"],
    onSelect: (_, editor) =>
      editor.update(() => {
        const selection = $getSelection();
        if ($isRangeSelection(selection)) {
          $setBlocksType(selection, () => $createQuoteNode());
        }
      }),
  });
}
