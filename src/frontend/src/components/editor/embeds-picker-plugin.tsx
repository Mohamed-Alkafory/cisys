/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import { INSERT_EMBED_COMMAND } from "@lexical/react/LexicalAutoEmbedPlugin";

import {
  type CustomEmbedConfig,
  EmbedConfigs,
} from "@/components/editor/auto-embed-plugin";
import { ComponentPickerOption } from "@/components/editor/component-picker-option";

export function EmbedsPickerPlugin({
  embed,
}: {
  embed: "tweet" | "youtube-video";
}) {
  const embedConfig = EmbedConfigs.find(
    (config) => config.type === embed,
  ) as CustomEmbedConfig;

  return new ComponentPickerOption(`Embed ${embedConfig.contentName}`, {
    icon: embedConfig.icon,
    keywords: [...embedConfig.keywords, "embed"],
    onSelect: (_, editor) =>
      editor.dispatchCommand(INSERT_EMBED_COMMAND, embedConfig.type),
  });
}
