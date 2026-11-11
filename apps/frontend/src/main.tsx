import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "@fontsource-variable/archivo/wdth.css";
import "@fontsource/ibm-plex-mono/400.css";
import "@fontsource/ibm-plex-mono/500.css";
import "./styles/tokens.css";
import "./styles/base.css";
import "./styles/frame.css";
import "./styles/entry.css";
import "./styles/waiting.css";
import "./styles/deck.css";
import { Api } from "./api/client.ts";
import { App } from "./App.tsx";
import { i18n } from "./i18n/index.ts";
import { DiscoveryController } from "./state/controller.ts";
import { LongReviewController } from "./state/review.ts";

const api = new Api();
const controller = new DiscoveryController(api);
const review = new LongReviewController(
  api,
  () => controller.state,
  () => controller.refresh(),
);
const fixture = !!document.querySelector('meta[name="auralis-fixture"]');

// A restored discovery reopens in the language it was asked for (until one is picked).
controller.subscribe(() =>
  i18n.adopt(controller.state?.editable_input?.language),
);

document.addEventListener("visibilitychange", () => {
  if (!document.hidden && controller.state) void controller.refresh();
});
window.addEventListener("pagehide", () => {
  controller.dispose();
  review.dispose();
});
window.addEventListener("pageshow", (event) => {
  if (event.persisted) location.reload();
});
void controller.connect();

const root = document.getElementById("root");
if (!root) throw new Error("Missing #root");
createRoot(root).render(
  <StrictMode>
    <App api={api} controller={controller} review={review} fixture={fixture} />
  </StrictMode>,
);
