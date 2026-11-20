/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React from "react";
import { Newspaper } from "lucide-react";
import { articlesPath } from "./project-paths";

/** Header icon that opens the articles. */
export default function ArticlesButton({ active, onOpen }) {
  return (
    <a
      href={articlesPath()}
      className="header-icon"
      aria-label="Articles"
      title="Articles"
      aria-current={active ? "page" : undefined}
      onClick={(event) => {
        event.preventDefault();
        onOpen();
      }}
    >
      <Newspaper size={20} aria-hidden="true" />
    </a>
  );
}
