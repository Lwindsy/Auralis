import genreIntros from "./genre-intros.json";
import { i18n, type Locale } from "../i18n/index.ts";

const notesById = new Map(genreIntros.map((note) => [note.id, note]));

export const genreNote = (
  genreId: string,
  locale: Locale = i18n.locale,
): string | null =>
  notesById.get(genreId)?.[locale === "en" ? "en" : "zh"] ?? null;
