import genreIntros from "./genre-intros.json";
import { i18n, type Locale } from "../i18n/index.ts";

const notesById = new Map(genreIntros.map((note) => [note.id, note]));

/** A random catalogue genre other than the one at `previous`, by its place in the list. */
export const randomGenreIndex = (previous = -1): number => {
  if (genreIntros.length < 2) return 0;
  let index = previous;
  while (index === previous)
    index = Math.floor(Math.random() * genreIntros.length);
  return index;
};

/** A genre's name and its one-sentence introduction in the interface language. */
export const genreFact = (index: number, locale: Locale = i18n.locale) => {
  const note = genreIntros[index]!;
  return { name: note.name, text: locale === "en" ? note.en : note.zh };
};

export const genreNote = (
  genreId: string,
  locale: Locale = i18n.locale,
): string | null =>
  notesById.get(genreId)?.[locale === "en" ? "en" : "zh"] ?? null;
