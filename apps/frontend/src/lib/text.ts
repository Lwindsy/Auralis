/** Letters kept in capitals when a genre name is title-cased. */
const ACRONYMS = new Set([
  "uk",
  "us",
  "usa",
  "idm",
  "edm",
  "ebm",
  "dnb",
  "r&b",
  "dj",
  "mpb",
  "nwobhm",
  "aor",
  "ccm",
  "lgbt",
]);

/**
 * Catalogue genre names arrive in lower case ("space rock revival"). Shown as names they
 * read better title-cased, consistently in every place: "Space Rock Revival", "UK Garage",
 * "J-Pop", "Lo-Fi". Names that already carry capitals are left as they are.
 */
export function genreName(name: string): string {
  if (/[A-Z]/.test(name)) return name;
  return name.replace(/[\p{L}\p{N}&']+/gu, (word) =>
    ACRONYMS.has(word)
      ? word.toUpperCase()
      : word.charAt(0).toUpperCase() + word.slice(1),
  );
}
