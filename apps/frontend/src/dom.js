export function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (key.startsWith("on"))
      node.addEventListener(key.slice(2).toLowerCase(), value);
    else if (key === "className") node.className = value;
    else if (key in node && !key.startsWith("aria-")) node[key] = value;
    else node.setAttribute(key, String(value));
  }
  node.append(
    ...children.flat().filter((child) => child !== null && child !== undefined),
  );
  return node;
}
export const button = (text, handler, attrs = {}) =>
  el("button", { type: "button", onclick: handler, ...attrs }, text);
export function safeImage(url) {
  try {
    return new URL(url).protocol === "https:" ? url : null;
  } catch {
    return null;
  }
}
