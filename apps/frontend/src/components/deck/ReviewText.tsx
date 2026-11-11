import { Fragment } from "react";
import { useCopy } from "../../i18n/index.ts";

const MARKER = /\[(R[1-9][0-9]*)\]/g;

/** Renders model prose as plain text; citation markers become quiet superscripts. */
export function ReviewText({
  text,
  className,
}: {
  text: string;
  className?: string;
}) {
  const paragraphs = text
    .split(/\n{2,}/)
    .map((part) => part.trim())
    .filter(Boolean);
  return (
    <div className={className}>
      {paragraphs.map((paragraph, index) => (
        <p key={index}>
          <Inline text={paragraph} />
        </p>
      ))}
    </div>
  );
}

export function Inline({ text }: { text: string }) {
  const words = useCopy().review;
  const parts: (string | { marker: string })[] = [];
  let last = 0;
  for (const match of text.matchAll(MARKER)) {
    if (match.index > last) parts.push(text.slice(last, match.index));
    parts.push({ marker: match[1] ?? "" });
    last = match.index + match[0].length;
  }
  if (last < text.length) parts.push(text.slice(last));
  return (
    <>
      {parts.map((part, index) =>
        typeof part === "string" ? (
          <Fragment key={index}>{part}</Fragment>
        ) : (
          <sup key={index} className="cite" title={words.source(part.marker)}>
            {part.marker}
          </sup>
        ),
      )}
    </>
  );
}
