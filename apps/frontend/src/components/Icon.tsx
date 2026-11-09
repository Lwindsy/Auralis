import type { SVGProps } from "react";

const paths = {
  heart: (
    <path d="M12 20s-7-4.35-7-9.7A4.1 4.1 0 0 1 12 7.8a4.1 4.1 0 0 1 7 2.5C19 15.65 12 20 12 20Z" />
  ),
  ban: (
    <>
      <circle cx="12" cy="12" r="8" />
      <path d="m6.4 6.4 11.2 11.2" />
    </>
  ),
  plus: <path d="M12 5v14M5 12h14" />,
  check: <path d="m5 12.5 4.2 4.2L19 7" />,
  close: <path d="M6 6l12 12M18 6 6 18" />,
  chevronRight: <path d="m9.5 6 6 6-6 6" />,
  chevronDown: <path d="m6 9.5 6 6 6-6" />,
  arrowRight: <path d="M5 12h14m-5.5-5.5L19 12l-5.5 5.5" />,
  search: (
    <>
      <circle cx="11" cy="11" r="6.5" />
      <path d="m16 16 4 4" />
    </>
  ),
  refresh: (
    <>
      <path d="M19.5 12a7.5 7.5 0 0 1-13 5.1M4.5 12a7.5 7.5 0 0 1 13-5.1" />
      <path d="M17.5 3.5v3.6h-3.6M6.5 20.5v-3.6h3.6" />
    </>
  ),
  shuffle: (
    <>
      <path d="M4 7h3.2c2 0 3.2 1 4.3 2.7l1 1.6c1.1 1.7 2.3 2.7 4.3 2.7H20" />
      <path d="M4 17h3.2c1.4 0 2.4-.5 3.3-1.4M20 7h-3.2c-1.4 0-2.4.5-3.3 1.4" />
      <path d="m17.5 4.5 2.5 2.5-2.5 2.5M17.5 11.5l2.5 2.5-2.5 2.5" />
    </>
  ),
  headphones: (
    <>
      <path d="M4.5 16v-3.5a7.5 7.5 0 0 1 15 0V16" />
      <rect x="3.5" y="14" width="4" height="6" rx="1.6" />
      <rect x="16.5" y="14" width="4" height="6" rx="1.6" />
    </>
  ),
  sliders: (
    <>
      <path d="M5 7h9M18 7h1M5 17h3M12 17h7" />
      <circle cx="16" cy="7" r="2" />
      <circle cx="10" cy="17" r="2" />
    </>
  ),
  book: (
    <>
      <path d="M12 6.5C10.2 5.2 7.8 4.8 4.5 5v13c3.3-.2 5.7.2 7.5 1.5 1.8-1.3 4.2-1.7 7.5-1.5V5c-3.3-.2-5.7.2-7.5 1.5Z" />
      <path d="M12 6.5v13" />
    </>
  ),
  target: (
    <>
      <circle cx="12" cy="12" r="7.5" />
      <circle cx="12" cy="12" r="3.5" />
      <circle cx="12" cy="12" r="0.6" fill="currentColor" />
    </>
  ),
  sparkle: (
    <path d="M12 3.5c.6 4.4 2.1 5.9 6.5 6.5-4.4.6-5.9 2.1-6.5 6.5-.6-4.4-2.1-5.9-6.5-6.5 4.4-.6 5.9-2.1 6.5-6.5ZM18.5 15.5c.25 1.6.9 2.25 2.5 2.5-1.6.25-2.25.9-2.5 2.5-.25-1.6-.9-2.25-2.5-2.5 1.6-.25 2.25-.9 2.5-2.5Z" />
  ),
  alert: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 7.5v5.5M12 16.2v.3" />
    </>
  ),
  info: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 11v5.5M12 7.8v.3" />
    </>
  ),
  disc: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <circle cx="12" cy="12" r="2.5" />
    </>
  ),
  clock: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 7.5V12l3 2" />
    </>
  ),
} as const;

export type IconName = keyof typeof paths;

export function Icon({
  name,
  size = 18,
  filled = false,
  ...rest
}: {
  name: IconName;
  size?: number;
  filled?: boolean;
} & SVGProps<SVGSVGElement>) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill={filled ? "currentColor" : "none"}
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      {...rest}
    >
      {paths[name]}
    </svg>
  );
}
