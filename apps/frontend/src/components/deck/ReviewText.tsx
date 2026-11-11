/** Strip evidence aliases from visible prose, including previously saved reviews. */
export function reviewProse(text: string) {
  return text
    .replace(/\[R[1-9]\d*\]/gi, "")
    .replace(/[ \t]+(?=[.,!?;:，。！？；：、\n]|$)/g, "")
    .replace(/[ \t]{2,}/g, " ")
    .trim();
}

export function ReviewText({
  text,
  className,
}: {
  text: string;
  className?: string;
}) {
  const paragraphs = reviewProse(text)
    .split(/\n{2,}/)
    .map((part) => part.trim())
    .filter(Boolean);
  return (
    <div className={className}>
      {paragraphs.map((paragraph, index) => (
        <p key={index}>{paragraph}</p>
      ))}
    </div>
  );
}
