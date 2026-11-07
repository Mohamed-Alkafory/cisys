/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import { useCallback, useState } from "react";

import { $isCodeNode } from "@lexical/code";
import {
  $getNearestNodeFromDOMNode,
  $getSelection,
  $setSelection,
  type LexicalEditor,
} from "lexical";

import { CircleCheckIcon, CopyIcon } from "lucide-react";

import { useDebounce } from "@/components/editor/use-debounce";
import { buttonVariants } from "@/components/ui/button";
import { cn } from "@/lib/utils";

interface Props {
  editor: LexicalEditor;
  getCodeDOMNode: () => HTMLElement | null;
}

export function CopyButton({ editor, getCodeDOMNode }: Props) {
  const [isCopyCompleted, setCopyCompleted] = useState<boolean>(false);

  const clearCopyCompleted = useCallback(() => {
    setCopyCompleted(false);
  }, []);
  const removeSuccessIcon = useDebounce(clearCopyCompleted, 1000);

  async function handleClick(): Promise<void> {
    const codeDOMNode = getCodeDOMNode();

    if (!codeDOMNode) {
      return;
    }

    let content = "";

    editor.update(() => {
      const codeNode = $getNearestNodeFromDOMNode(codeDOMNode);

      if ($isCodeNode(codeNode)) {
        content = codeNode.getTextContent();
      }

      const selection = $getSelection();
      $setSelection(selection);
    });

    try {
      await navigator.clipboard.writeText(content);
      setCopyCompleted(true);
      removeSuccessIcon();
    } catch (err) {
      console.error("Failed to copy: ", err);
    }
  }

  return (
    <button
      className={cn(
        buttonVariants({ size: "icon-xs", variant: "ghost" }),
        "p-1 uppercase",
      )}
      onClick={handleClick}
      aria-label="copy"
    >
      {isCopyCompleted ? (
        <CircleCheckIcon className="size-4" />
      ) : (
        <CopyIcon className="size-4" />
      )}
    </button>
  );
}
