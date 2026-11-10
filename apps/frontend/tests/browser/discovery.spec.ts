// Synthetic fixture backend only: no Java, no model calls. Covers the start page doors,
// the wait, the one-album-at-a-time room and the end-of-group choices.
import {
  expect,
  test,
  type BrowserContext,
  type Page,
  type Route,
} from "@playwright/test";
import type { ProductState } from "../../src/api/types.ts";
import { FixtureBackend, idle, ready, shared } from "../fixtures.ts";

async function attach(
  context: BrowserContext,
  backend: FixtureBackend,
  intercept?: (route: Route) => Promise<boolean>,
) {
  await context.route("**/v1/**", async (route) => {
    const request = route.request();
    if (intercept && (await intercept(route))) return;
    const response = backend.handle(
      request.method(),
      request.url(),
      request.postDataJSON() ?? undefined,
      request.headers(),
    );
    await route.fulfill({
      status: response.status,
      contentType: "application/json",
      body: JSON.stringify(response.body),
    });
  });
}

const posts = (backend: FixtureBackend, suffix: string) =>
  backend.calls.filter(
    (call) => call.method === "POST" && call.path.endsWith(suffix),
  );

const album = (page: Page, title: string) =>
  page.getByRole("article", { name: title, exact: true });
const notes = (page: Page, title: string) => album(page, title);

async function openDoor(page: Page, name: RegExp) {
  await page.getByRole("button", { name }).first().click();
}

async function type(page: Page, text: string) {
  await openDoor(page, /^说说想听什么/);
  await page.getByLabel("这次想听的音乐").fill(text);
}

async function start(page: Page) {
  await page.getByRole("button", { name: "开始发现" }).click();
}

const NEXT = { name: "下一张", exact: true };
const PREV = { name: "上一张", exact: true };

test("actual fixture HTTP restores via HttpOnly cookie without storing input in browser storage", async ({
  page,
  context,
}) => {
  await page.goto("/");
  await expect(page.getByText("契约样例模式")).toBeVisible();
  await type(page, "中文原文 only on server");
  await page.getByRole("button", { name: "更多设置" }).click();
  await page.getByLabel("最低听众数（可选）").fill("500");
  await start(page);
  await expect(album(page, "样例专辑 1")).toBeVisible({ timeout: 10000 });
  const cookies = await context.cookies();
  expect(cookies.find((cookie) => cookie.name === "fixture_id")!.httpOnly).toBe(
    true,
  );
  expect(JSON.stringify(cookies)).not.toContain("中文原文");
  await page.close();
  const reopened = await context.newPage();
  await reopened.goto("/");
  await expect(album(reopened, "样例专辑 1")).toBeVisible();
  await reopened.getByRole("link", { name: "Auralis 首页" }).click();
  await expect(reopened.getByLabel("最低听众数（可选）")).toHaveValue("500");
  await openDoor(reopened, /^说说想听什么/);
  await expect(reopened.getByLabel("这次想听的音乐")).toHaveValue(
    "中文原文 only on server",
  );
  expect(
    await reopened.evaluate(() => [localStorage.length, sessionStorage.length]),
  ).toEqual([0, 0]);
});

test("doors widen one at a time; genres group families, show notes and keep marks", async ({
  page,
  context,
}) => {
  const backend = new FixtureBackend();
  await attach(context, backend);
  await page.goto("/");
  await expect(page.getByRole("button", { name: "开始发现" })).toBeDisabled();
  const genres = page.getByRole("button", { name: /^风格/ });
  await genres.click();
  await expect(genres).toHaveAttribute("aria-expanded", "true");
  await expect(page.locator(".door[data-door='text']")).toHaveAttribute(
    "data-state",
    "folded",
  );
  // Hover note comes from the short genre notes, relation from the catalogue.
  await page.getByRole("button", { name: "Jazz", exact: true }).hover();
  await expect(page.getByRole("tooltip")).toContainText("即兴");
  await page.getByRole("button", { name: "Jazz", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "喜欢Cool Jazz", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "喜欢Jazz", exact: true }),
  ).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: "Jazz特别喜欢", exact: true }).click();
  await page.getByRole("button", { name: "Jazz本次重点", exact: true }).click();
  await expect(
    page.getByRole("list", { name: "风格家族" }).getByRole("button", {
      name: "不喜欢Metal",
    }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "展开Unique", exact: true }).click();
  await page.getByRole("button", { name: "不喜欢Metal", exact: true }).click();
  await page.keyboard.press("Escape");
  await expect(genres).toHaveAttribute("aria-expanded", "false");
  await expect(genres).toHaveAccessibleName("风格，已选 2");
  await type(page, "温暖的爵士 🎷");
  await page.getByLabel("惊喜", { exact: true }).check({ force: true });
  await start(page);
  await expect(page.getByRole("region", { name: "正在推荐" })).toBeVisible();
  await expect(album(page, "样例专辑 1")).toBeVisible({ timeout: 10000 });
  expect(backend.state.editable_input!.liked_genres[0]).toEqual({
    genre_id: "jazz",
    strength: "special",
    focused: true,
  });
  expect(backend.state.editable_input!.disliked_genre_ids).toEqual(["metal"]);
  expect(backend.state.editable_input!.mode).toBe("surprise");
  expect(backend.state.editable_input!.request_text).toBe("温暖的爵士 🎷");
});

test("one album at a time: edges and keys move, 听过了 records and can be taken back", async ({
  page,
  context,
}) => {
  const backend = new FixtureBackend(ready());
  await attach(context, backend);
  await page.goto("/");
  await expect(album(page, "样例专辑 1")).toBeVisible();
  await expect(page.getByRole("article")).toHaveCount(1);
  await expect(page.getByRole("button", PREV)).toBeDisabled();
  await page.getByRole("button", NEXT).click();
  await expect(album(page, "样例专辑 2")).toBeVisible();
  await page.keyboard.press("ArrowLeft");
  await expect(album(page, "样例专辑 1")).toBeVisible();

  const heard = notes(page, "样例专辑 1").getByRole("button", {
    name: "听过了",
  });
  await heard.click();
  await expect(heard).toHaveAttribute("aria-pressed", "true");
  expect(backend.heard.has("candidate-1")).toBe(true);
  // The album stays in front of the reader; its replacement joins the end.
  await expect(album(page, "样例专辑 1")).toBeVisible();
  await expect(
    page.getByRole("list", { name: "位置" }).getByRole("listitem"),
  ).toHaveCount(5);
  // Choosing it again takes the mark back on the server too.
  await heard.click();
  await expect(heard).toHaveAttribute("aria-pressed", "false");
  expect(backend.heard.has("candidate-1")).toBe(false);
  expect(posts(backend, "/undo-last-card")).toHaveLength(1);
  await expect(
    page.getByRole("list", { name: "位置" }).getByRole("listitem"),
  ).toHaveCount(4);

  // 我喜欢 and 一般般 are exclusive and stay on this page.
  const like = notes(page, "样例专辑 1").getByRole("button", {
    name: "我喜欢！",
  });
  const meh = notes(page, "样例专辑 1").getByRole("button", { name: "一般般" });
  await like.click();
  await expect(like).toHaveAttribute("aria-pressed", "true");
  await meh.click();
  await expect(meh).toHaveAttribute("aria-pressed", "true");
  await expect(like).toHaveAttribute("aria-pressed", "false");

  const expiry = backend.state.expires_at;
  await page.reload();
  await expect(album(page, "样例专辑 1")).toBeVisible();
  expect(backend.state.expires_at).toBe(expiry);
});

test("long review unfolds in place of the short one; failures regenerate only on request", async ({
  page,
  context,
}) => {
  const backend = new FixtureBackend(ready());
  await attach(context, backend);
  await page.goto("/");
  const card = notes(page, "样例专辑 1");
  await card.getByRole("button", { name: "读长评" }).click();
  await expect(card.getByText("长评写作中")).toBeVisible();
  await expect(card.getByText("第二段合成文字，验证多段落排版。")).toBeVisible({
    timeout: 8000,
  });
  await expect(card.getByRole("button", { name: "收起长评" })).toBeVisible();
  expect(posts(backend, "/open")).toHaveLength(1);
  expect(posts(backend, "/regenerate")).toHaveLength(0);
  await card.getByRole("button", { name: "收起长评" }).click();
  backend.reviewState = "INTERRUPTED";
  await card.getByRole("button", { name: "读长评" }).click();
  await expect(card.getByText(/生成中断/)).toBeVisible();
  await page.waitForTimeout(2500);
  expect(posts(backend, "/regenerate")).toHaveLength(0);
  await card.getByRole("button", { name: "重新生成" }).click();
  await expect(card.getByText("长评写作中")).toBeVisible();
  expect(posts(backend, "/regenerate")).toHaveLength(1);
});

test("after the last album: more of the same, or rethink with the old input", async ({
  page,
  context,
}) => {
  const backend = new FixtureBackend(ready());
  await attach(context, backend);
  await page.goto("/");
  for (const title of ["样例专辑 1", "样例专辑 2", "样例专辑 3"]) {
    await expect(album(page, title)).toBeVisible();
    await page.getByRole("button", NEXT).click();
  }
  await expect(page.getByText("这一组听完了")).toBeVisible();
  await expect(page.getByRole("button", NEXT)).toBeDisabled();
  await page.getByRole("button", { name: /再给我来一些/ }).click();
  await expect(page.getByRole("region", { name: "正在推荐" })).toBeVisible();
  await expect(album(page, "样例专辑 10")).toBeVisible({ timeout: 10000 });
  expect(posts(backend, "/next-batch")).toHaveLength(1);

  for (let i = 0; i < 3; i++) await page.getByRole("button", NEXT).click();
  await page.getByRole("button", { name: /换一些想法吧/ }).click();
  await openDoor(page, /^说说想听什么/);
  await expect(page.getByLabel("这次想听的音乐")).toHaveValue("想听轻松的爵士");
  await page.getByLabel("这次想听的音乐").fill("换个方向");
  await start(page);
  await expect(album(page, "样例专辑 1")).toBeVisible({ timeout: 10000 });
  expect(backend.state.editable_input!.request_text).toBe("换个方向");
});

test("BASIC results say so and rescreen only on request", async ({
  page,
  context,
}) => {
  const state = ready(2, 5);
  state.analysis_mode = "BASIC";
  const backend = new FixtureBackend(state);
  await attach(context, backend);
  await page.goto("/");
  await expect(page.getByText("基础推荐 · 本次个性化未完成")).toBeVisible();
  expect(posts(backend, "/rescreen")).toHaveLength(0);
  await page.getByRole("button", NEXT).click();
  await page.getByRole("button", NEXT).click();
  await page.getByRole("button", { name: "按原需求重新复筛" }).click();
  await expect.poll(() => posts(backend, "/rescreen").length).toBe(1);
});

for (const status of ["NO_MATCH", "FAILED", "INTERRUPTED"] as const) {
  test(`${status} shows no empty album and can rethink with the input kept`, async ({
    page,
    context,
  }) => {
    const state = ready(0);
    state.status = status;
    await attach(context, new FixtureBackend(state));
    await page.goto("/");
    await expect(page.getByRole("group", { name: "接下来" })).toBeVisible();
    await expect(page.getByRole("article")).toHaveCount(0);
    await page.getByRole("button", { name: /换一些想法吧/ }).click();
    await openDoor(page, /^说说想听什么/);
    await expect(page.getByLabel("这次想听的音乐")).toHaveValue(
      "想听轻松的爵士",
    );
  });
}

test("WAITING winds down into the albums without another POST", async ({
  page,
  context,
}) => {
  const backend = new FixtureBackend(
    shared<ProductState>("fourth-request-waiting"),
  );
  await attach(context, backend);
  await page.goto("/");
  await expect(page.getByText("排队中")).toBeVisible();
  backend.state = ready();
  await expect(album(page, "样例专辑 1")).toBeVisible({ timeout: 10000 });
  expect(
    backend.calls
      .filter((call) => call.method === "POST")
      .map((call) => call.path),
  ).toEqual(["/v1/bootstrap"]);
});

test("lost response retains original key and prevents duplicate intents", async ({
  page,
  context,
}) => {
  const backend = new FixtureBackend(ready());
  let lost = true;
  await attach(context, backend, async (route) => {
    if (lost && route.request().url().endsWith("/actions")) {
      lost = false;
      const req = route.request();
      backend.handle(
        req.method(),
        req.url(),
        req.postDataJSON(),
        req.headers(),
      );
      await route.abort("failed");
      return true;
    }
    return false;
  });
  await page.goto("/");
  await notes(page, "样例专辑 1")
    .getByRole("button", { name: "听过了" })
    .click();
  await expect(page.getByRole("alert")).toContainText("操作是否完成尚不确定");
  await page.getByRole("button", { name: "重试原操作", exact: true }).click();
  await expect(page.getByRole("alert")).toHaveCount(0);
  const calls = backend.calls.filter((call) => call.path.endsWith("/actions"));
  expect(calls).toHaveLength(2);
  expect(calls[0]!.headers["idempotency-key"]).toBe(
    calls[1]!.headers["idempotency-key"],
  );
  expect(backend.heard.size).toBe(1);
});

test("album shelf sends only MBID and marks; removal resets marks", async ({
  page,
  context,
}) => {
  const backend = new FixtureBackend();
  await attach(context, backend);
  await page.goto("/");
  const name = "Synthetic 外部专辑 — Synthetic Artist";
  await openDoor(page, /^专辑/);
  await page.getByLabel("查找专辑").fill("Synthetic");
  await page.getByLabel("查找专辑").press("Enter");
  await page.getByRole("button", { name: `添加${name}`, exact: true }).click();
  await expect(
    page.getByRole("button", { name: `已添加${name}`, exact: true }),
  ).toBeDisabled();
  await page.getByRole("button", { name: `${name}特别喜欢` }).click();
  await expect(
    page.getByRole("button", { name: `${name}特别喜欢` }),
  ).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: `移除${name}` }).click();
  await page.getByRole("button", { name: `添加${name}`, exact: true }).click();
  await expect(
    page.getByRole("button", { name: `${name}特别喜欢` }),
  ).toHaveAttribute("aria-pressed", "false");
  await start(page);
  await expect(album(page, "样例专辑 1")).toBeVisible({ timeout: 10000 });
  expect(backend.state.editable_input!.liked_albums[0]).toEqual({
    mb_release_group_id: "11111111-1111-4111-8111-111111111111",
    strength: "normal",
    focused: false,
  });
});

test("mobile layout fits, keys work and markup stays inert", async ({
  page,
  context,
}) => {
  const state = ready();
  state.slots[0]!.card!.identity.title = "<img src=x onerror=alert(1)>";
  await attach(context, new FixtureBackend(state));
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await expect(
    page.getByRole("heading", {
      name: "<img src=x onerror=alert(1)>",
      exact: true,
    }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.keyboard.press("ArrowRight");
  await expect(album(page, "样例专辑 2")).toBeVisible();
  expect(
    await page.evaluate(() => [localStorage.length, sessionStorage.length]),
  ).toEqual([0, 0]);
});

test("expired cookie opens an empty start page with an expiry notice", async ({
  page,
  context,
}) => {
  const state = { ...idle(), message_key: "session.expired" };
  await attach(context, new FixtureBackend(state));
  await page.goto("/");
  await expect(
    page.getByText("上次发现已过期，已为你打开空白页面，可以重新开始。"),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "开始发现" })).toBeDisabled();
});

test("visual baselines for start page, room and mobile", async ({
  page,
  context,
}) => {
  const backend = new FixtureBackend(ready());
  await attach(context, backend);
  await page.setViewportSize({ width: 1360, height: 900 });
  await page.goto("/");
  await expect(album(page, "样例专辑 1")).toBeVisible();
  await page.waitForTimeout(1200);
  await page.screenshot({ path: "test-results/room.png" });
  await page.getByRole("link", { name: "Auralis 首页" }).click();
  await page.waitForTimeout(1600);
  await page.screenshot({ path: "test-results/entry.png" });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(400);
  await page.screenshot({ path: "test-results/mobile.png", fullPage: true });
});
