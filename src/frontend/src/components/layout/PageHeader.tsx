/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

interface PageHeaderProps {
  title: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
  className?: string;
  titleClassName?: string;
}

export default function PageHeader({
  title,
  subtitle,
  actions,
  className,
  titleClassName,
}: PageHeaderProps) {
  return (
    <div className={cn("mb-8 border-b px-2 pb-4", className)}>
      <div className="flex flex-col gap-4 md:flex-row md:items-end md:justify-between">
        <div className="min-w-0">
          <h1
            className={cn(
              "text-2xl font-semibold tracking-tight text-[var(--color-section-header)]",
              titleClassName,
            )}
          >
            {title}
          </h1>
          {subtitle ? (
            <div className="mt-2 text-muted-foreground">{subtitle}</div>
          ) : null}
        </div>
        {actions ? (
          <div className="flex items-center gap-3">{actions}</div>
        ) : null}
      </div>
    </div>
  );
}
