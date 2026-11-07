/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import { useEffect, useState } from "react";
import { MoonIcon, SunIcon } from "lucide-react";
import { useDarkModeToggle } from "../../hooks/useDarkModeToggle";
import { formatInstallationClock } from "../../utils/formatting";
import { SmartLink } from "../../utils/routing";

interface LoginHeaderProps {
  brandName: string;
  logoSrc?: string;
  brandHref: string;
  use24HourClock?: boolean;
}

export default function LoginHeader({
  brandName,
  logoSrc,
  brandHref,
  use24HourClock = false,
}: LoginHeaderProps) {
  const [now, setNow] = useState(() => new Date());
  const { darkModeToggleRef, toggleDarkMode } = useDarkModeToggle();

  useEffect(() => {
    const timerId = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timerId);
  }, []);

  return (
    <div className="flex items-center justify-between gap-4">
      <SmartLink
        href={brandHref}
        className="flex items-center gap-2.5 text-[var(--header-text)] no-underline"
      >
        {logoSrc ? (
          <img
            src={logoSrc}
            alt={`${brandName} logo`}
            className="h-7 w-7 shrink-0 object-contain"
          />
        ) : (
          <svg
            viewBox="0 0 24 24"
            aria-hidden="true"
            className="w-6 h-6 shrink-0 fill-none stroke-current stroke-2"
          >
            <rect x="3" y="3" width="18" height="18" rx="2" />
            <path d="M7 8h10M7 12h10M7 16h6" />
            <path d="M6 8l1 1 2-2" />
          </svg>
        )}
        <span className="font-heading text-xl font-bold leading-none">
          {brandName}
        </span>
      </SmartLink>
      <div className="flex items-center gap-3">
        <button
          ref={darkModeToggleRef}
          type="button"
          onClick={toggleDarkMode}
          aria-pressed="false"
          className="group relative inline-flex h-8 w-16 items-center rounded-full border border-[var(--color-buttons-bg)] bg-[var(--color-buttons-bg)] p-1 text-[var(--color-buttons-text)] transition-opacity hover:bg-[var(--color-buttons-hover)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--color-buttons-bg)] focus-visible:ring-offset-2"
          aria-label="Toggle dark mode"
          title="Toggle dark mode"
        >
          <SunIcon className="absolute left-2 size-3.5 opacity-90" />
          <MoonIcon className="absolute right-2 size-3.5 opacity-90" />
          <span className="pointer-events-none inline-block h-6 w-6 translate-x-0 rounded-full bg-[var(--color-buttons-text)] shadow-sm transition-transform duration-200 ease-out group-aria-[pressed=true]:translate-x-8" />
        </button>
        <span className="font-semibold tabular-nums text-[var(--header-text)]">
          {formatInstallationClock(now, use24HourClock)}
        </span>
      </div>
    </div>
  );
}
