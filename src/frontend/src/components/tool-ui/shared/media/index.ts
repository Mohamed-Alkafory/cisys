/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

export {
  AspectRatioSchema,
  MediaFitSchema,
  RATIO_CLASS_MAP,
  getRatioClass,
  getFitClass,
  type AspectRatio,
  type MediaFit,
} from "./aspect-ratio";

export { OVERLAY_GRADIENT } from "./overlay-gradient";

export { formatDuration, formatFileSize } from "./format-utils";

export { sanitizeHref } from "./sanitize-href";
export {
  resolveSafeNavigationHref,
  openSafeNavigationHref,
} from "./safe-navigation";
