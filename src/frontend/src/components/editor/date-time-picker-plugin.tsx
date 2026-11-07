/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import { CalendarIcon } from "lucide-react";

import { INSERT_DATETIME_COMMAND } from "@/components/editor/date-time-extension";
import { ComponentPickerOption } from "@/components/editor/component-picker-option";

export function DateTimePickerPlugin() {
  return new ComponentPickerOption("Date", {
    icon: <CalendarIcon className="size-4" />,
    keywords: ["date", "calendar", "time", "today"],
    onSelect: (_, editor) => {
      const dateTime = new Date();
      dateTime.setHours(0, 0, 0, 0); // Set time to midnight
      editor.dispatchCommand(INSERT_DATETIME_COMMAND, { dateTime });
    },
  });
}
