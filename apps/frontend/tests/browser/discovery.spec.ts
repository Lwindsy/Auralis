// Synthetic fixture backend only: no Java, no model calls. Covers the start page doors,
// the wait, the one-album-at-a-time room and the end-of-group choices.
import {
  expect,
  test,
  type BrowserContext,
  type Page,
  type Route,
} from "@playwright/test";
import type { LongReviewView, ProductState } from "../../src/api/types.ts";
import {
  FixtureBackend,
  JAZZ_GENRE_ID,
  idle,
  ready,
  shared,
} from "../fixtures.ts";

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

/** The page opens in English; these checks read the Chinese copy, so switch first. */
async function visit(page: Page) {
  await page.goto("/");
  await page.getByRole("button", { name: "中文", exact: true }).click();
  await expect(page.locator("html")).toHaveAttribute("lang", "zh-CN");
}

async function openDoor(page: Page, name: RegExp) {
  await page.getByRole("button", { name }).first().click();
}

async function type(page: Page, text: string) {
  await openDoor(page, /^你的任何想法都可以说/);
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
  await visit(page);
  await expect(page.getByText("契约样例模式")).toBeVisible();
  await type(page, "中文原文 only on server");
  await page.getByLabel("传播度", { exact: true }).fill("100");
  await start(page);
  await expect(album(page, "样例专辑 1")).toBeVisible({ timeout: 10000 });
  const cookies = await context.cookies();
  expect(cookies.find((cookie) => cookie.name === "fixture_id")!.httpOnly).toBe(
    true,
  );
  expect(JSON.stringify(cookies)).not.toContain("中文原文");
  await page.close();
  const reopened = await context.newPage();
  await visit(reopened);
  await expect(album(reopened, "样例专辑 1")).toBeVisible();
  await reopened.getByRole("link", { name: "Auralis 首页" }).click();
  await expect(reopened.getByLabel("传播度", { exact: true })).toHaveValue(
    "100",
  );
  await openDoor(reopened, /^你的任何想法都可以说/);
  await expect(reopened.getByLabel("这次想听的音乐")).toHaveValue(
    "中文原文 only on server",
  );
  expect(
    await reopened.evaluate(() => [localStorage.length, sessionStorage.length]),
  ).toEqual([0, 0]);
});

test("picked genres clear in one go; 传播度 explains itself without covering anything", async ({
  page,
  context,
}) => {
  await attach(context, new FixtureBackend());
  await visit(page);
  await expect(
    page.getByText("以你的偏好为主，也会加入一些相邻的风格。"),
  ).toBeVisible();
  await page.getByRole("button", { name: /^风格/ }).click();
  await page.getByRole("button", { name: "喜欢Jazz", exact: true }).click();
  await page.getByRole("button", { name: "展开Unique", exact: true }).click();
  await page.getByRole("button", { name: "不喜欢Metal", exact: true }).click();
  const picked = page.getByRole("list", { name: "已选风格" });
  await expect(picked.getByRole("listitem")).toHaveCount(3);
  await page.getByRole("button", { name: "清空已选风格" }).click();
  await expect(picked).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "喜欢Jazz", exact: true }),
  ).toHaveAttribute("aria-pressed", "false");
  await page.keyboard.press("Escape");
  await expect(page.locator(".reach__zone")).toHaveText("听众较少");
  await page.getByRole("button", { name: "传播度说明" }).hover();
  await expect(page.getByRole("tooltip")).toHaveText(
    "听众数的参考，不代表全球热度",
  );
  await page.mouse.move(5, 5);
  await expect(page.getByRole("tooltip")).toHaveCount(0);
});

test("doors widen one at a time; genres group families, show notes and keep marks", async ({
  page,
  context,
}) => {
  const backend = new FixtureBackend();
  await attach(context, backend);
  await visit(page);
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
  // A press anywhere outside the open door folds it as well.
  await genres.click();
  await expect(genres).toHaveAttribute("aria-expanded", "true");
  await page.getByRole("heading", { name: "Auralis" }).click();
  await expect(genres).toHaveAttribute("aria-expanded", "false");
  await expect(genres).toHaveAccessibleName("风格，已选 2");
  await type(page, "温暖的爵士 🎷");
  await page.getByLabel("惊喜", { exact: true }).check({ force: true });
  await start(page);
  const waiting = page.getByRole("region", { name: "正在推荐" });
  await expect(waiting).toBeVisible();
  await expect(waiting).not.toContainText("已等待");
  await expect(album(page, "样例专辑 1")).toBeVisible({ timeout: 10000 });
  expect(backend.state.editable_input!.liked_genres[0]).toEqual({
    genre_id: JAZZ_GENRE_ID,
    strength: "special",
    focused: true,
  });
  expect(backend.state.editable_input!.disliked_genre_ids).toEqual(["metal"]);
  expect(backend.state.editable_input!.mode).toBe("surprise");
  expect(backend.state.editable_input!.request_text).toBe("温暖的爵士 🎷");
});

test("中文／EN switches the whole page in place and sends the language with a new round", async ({
  page,
  context,
}) => {
  const backend = new FixtureBackend();
  await attach(context, backend);
  await page.goto("/");
  // The page opens in English.
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  await expect(page).toHaveTitle("Auralis · Find your next album");
  await page
    .getByRole("group", { name: "Language" })
    .getByRole("button", { name: "中文" })
    .click();
  await expect(page.locator("html")).toHaveAttribute("lang", "zh-CN");
  const switcher = page.getByRole("group", { name: "界面语言" });
  const privacy = page.getByRole("contentinfo", { name: "隐私说明" });
  await expect(privacy).toContainText("记录保留 30 天");
  const details = privacy.getByRole("button", { name: "详情" });
  await expect(details).toHaveAttribute("aria-expanded", "false");
  await details.click();
  await expect(privacy.getByText(/Cookie 只保存匿名会话凭证/)).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(privacy.getByText(/Cookie 只保存匿名会话凭证/)).toBeHidden();
  await expect(switcher.getByRole("button")).toHaveText(["中文", "EN"]);
  await expect(switcher.getByRole("button", { name: "中文" })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  await switcher.getByRole("button", { name: "English" }).click();
  // Same page, same state: only its words change.
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  await expect(page).toHaveTitle("Auralis · Find your next album");
  const privacyEn = page.getByRole("contentinfo", { name: "Privacy" });
  await expect(privacyEn).toContainText("May be processed by OpenAI");
  await privacyEn.getByRole("button", { name: "Details" }).click();
  await expect(
    privacyEn.getByText(/anonymous session credential/),
  ).toBeVisible();
  await page.mouse.click(10, 300);
  await expect(
    privacyEn.getByText(/anonymous session credential/),
  ).toBeHidden();
  const english = page.getByRole("group", { name: "Language" });
  await expect(
    english.getByRole("button", { name: "English" }),
  ).toHaveAttribute("aria-pressed", "true");
  await expect(
    page.getByRole("button", { name: "Start discovering" }),
  ).toBeDisabled();
  await page.getByRole("button", { name: /^Genres/ }).click();
  await page.getByRole("button", { name: "Jazz", exact: true }).hover();
  await expect(page.getByRole("tooltip")).toContainText("Improvisation");
  await page.getByRole("button", { name: "Like Jazz", exact: true }).click();
  await page.getByRole("button", { name: /^Say anything you like/ }).click();
  await page
    .getByLabel("What you want to hear this time")
    .fill("rainy day jazz piano");
  await page.getByLabel("Surprise", { exact: true }).check({ force: true });
  await page.getByRole("button", { name: "Start discovering" }).click();
  await expect(album(page, "样例专辑 1")).toBeVisible({ timeout: 10000 });
  expect(backend.state.editable_input!.language).toBe("en");
  expect(backend.state.editable_input!.request_text).toBe(
    "rainy day jazz piano",
  );
  // Reviews are shown exactly as returned, whatever the page language.
  const card = album(page, "样例专辑 1");
  await card.getByRole("button", { name: "Jazz", exact: true }).focus();
  await expect(card.locator(".wave__note")).toContainText("Improvisation");
  await card.getByRole("button", { name: "Rabbit Song", exact: true }).focus();
  await expect(card.locator(".wave__note")).toContainText("Just a hint.");
  await expect(card.getByText("这是用于交互验证的合成短评")).toBeAttached();
  await expect(card.getByRole("button", { name: "Love it!" })).toBeVisible();
  await expect(card.locator(".track__text")).not.toContainText("R1");
  await card.getByRole("button", { name: "Read the full review" }).click();
  await expect(card.getByText("第二段合成文字，验证多段落排版。")).toBeVisible({
    timeout: 8000,
  });
  await expect(card.locator(".reviews")).not.toContainText("R1");
  // Switching back keeps the review that was already written.
  await english.getByRole("button", { name: "中文" }).click();
  await expect(page.locator("html")).toHaveAttribute("lang", "zh-CN");
  await card.getByRole("button", { name: "Jazz", exact: true }).focus();
  await expect(card.locator(".wave__note")).toContainText("即兴");
  await expect(card.getByRole("button", { name: "收起长评" })).toBeVisible();
  await expect(
    card.getByText("第二段合成文字，验证多段落排版。"),
  ).toBeVisible();
  // Asking again in Chinese (the old input prefilled) sends zh-CN.
  for (let i = 0; i < 3; i++) await page.getByRole("button", NEXT).click();
  await page.getByRole("button", { name: /换一些想法吧/ }).click();
  await start(page);
  await expect(album(page, "样例专辑 1")).toBeVisible({ timeout: 10000 });
  const languages = posts(backend, "/v1/discovery-sessions").map(
    (call) => (call.body as { input: { language: string } }).input.language,
  );
  expect(languages).toEqual(["en", "zh-CN"]);
  expect(
    await page.evaluate(() => [localStorage.length, sessionStorage.length]),
  ).toEqual([0, 0]);
});

test("a restored English discovery reopens in English", async ({
  page,
  context,
}) => {
  const state = ready();
  state.editable_input = { ...state.editable_input!, language: "en" };
  await attach(context, new FixtureBackend(state));
  await page.goto("/");
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  await expect(
    album(page, "样例专辑 1").getByRole("button", { name: "Heard it" }),
  ).toBeVisible();
});

test("English layout fits on a phone", async ({ page, context }) => {
  await attach(context, new FixtureBackend(ready()));
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await page.getByRole("button", { name: "English" }).click();
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  await page.getByRole("link", { name: "Auralis home" }).click();
  await expect(
    page.getByRole("button", { name: "Start discovering" }),
  ).toBeVisible();
  const overlap = await page.evaluate(() => {
    const box = (selector: string) =>
      document.querySelector(selector)!.getBoundingClientRect();
    const modes = box(".modes__row");
    const go = box(".dock .go");
    return modes.right > go.left && modes.bottom > go.top;
  });
  expect(overlap).toBe(false);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});

test("an album at rest keeps no strips; the leaving copy is static and keeps its room", async ({
  page,
  context,
}) => {
  await attach(context, new FixtureBackend(ready()));
  await visit(page);
  await expect(album(page, "样例专辑 1")).toBeVisible();
  // The strips exist only while the cover assembles.
  await expect(page.locator(".slice")).toHaveCount(0, { timeout: 4000 });
  const before = await page.evaluate(() =>
    getComputedStyle(document.documentElement).getPropertyValue("--page-room"),
  );
  await page.keyboard.press("ArrowRight");
  const ghost = page.locator(".cut[data-leaving]");
  await expect(ghost).toHaveCount(1);
  // Static: no per-character playback markup and the old room colours pinned inline.
  await expect(ghost.locator(".type")).toHaveCount(0);
  expect(
    await ghost.evaluate((node) =>
      getComputedStyle(node).getPropertyValue("--page-room"),
    ),
  ).toBe(before);
  // The old room fades out as a single layer above the new one.
  await expect(page.locator(".room-fade")).toHaveCount(1);
  await expect(album(page, "样例专辑 2")).toBeVisible();
  await expect(ghost).toHaveCount(0);
  await expect(page.locator(".room-fade")).toHaveCount(0, { timeout: 3000 });
  await expect(page.locator(".slice")).toHaveCount(0, { timeout: 4000 });
});

test("a genre note opens above the wave and nothing below it moves", async ({
  page,
  context,
}) => {
  await attach(context, new FixtureBackend(ready()));
  await visit(page);
  const card = notes(page, "样例专辑 1");
  await expect(card.getByText("这是用于交互验证的合成短评")).toBeAttached();
  await page.waitForTimeout(2600);
  const review = card.locator(".reviews");
  const wave = card.locator(".wave__field");
  const before = [await review.boundingBox(), await wave.boundingBox()];
  await card.getByRole("button", { name: "Jazz", exact: true }).focus();
  const note = card.locator(".wave__note");
  await expect(note).toContainText("即兴");
  const after = [await review.boundingBox(), await wave.boundingBox()];
  expect(after).toEqual(before);
  const box = (await note.boundingBox())!;
  expect(box.y + box.height).toBeLessThanOrEqual(before[1]!.y + 1);
  // Its lead line is the album's own spark, not the fixed accent.
  const spark = await card.evaluate((node) =>
    getComputedStyle(node.closest(".cut")!).getPropertyValue("--spark"),
  );
  expect(spark).toMatch(/^#[0-9a-f]{6}$/);
});

test("the long review is printed on a sheet; the album moves into the header and back", async ({
  page,
  context,
}) => {
  await attach(context, new FixtureBackend(ready()));
  await page.setViewportSize({ width: 1440, height: 900 });
  await visit(page);
  const card = notes(page, "样例专辑 1");
  await page.waitForTimeout(2600);
  const cover = card.locator(".sliced");
  const resting = (await cover.boundingBox())!;
  await card.getByRole("button", { name: "读长评" }).click();
  await expect(page.locator(".cut[data-reading]")).toHaveCount(1, {
    timeout: 8000,
  });
  await expect(card.getByText("第二段合成文字，验证多段落排版。")).toBeVisible({
    timeout: 8000,
  });
  await page.waitForTimeout(1000);
  const small = (await cover.boundingBox())!;
  expect(Math.round(small.width)).toBe(84);
  expect(Math.round(small.y)).toBe(82);
  // The sheet covers the page below the header; the background keeps running.
  const paper = (await card.locator(".liner__paper").boundingBox())!;
  expect(Math.round(paper.y)).toBe(82 + 84 + 30);
  await expect(page.locator(".contours")).toBeVisible();
  expect(
    await page.evaluate(() => document.documentElement.style.overflow),
  ).toBe("hidden");
  // Reactions stay usable on the sheet's foot.
  await card.getByRole("button", { name: "我喜欢！" }).click();
  await expect(card.getByRole("button", { name: "收起长评" })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.locator(".cut[data-reading]")).toHaveCount(0);
  await page.waitForTimeout(1200);
  expect(await cover.boundingBox()).toEqual(resting);
  expect(
    await page.evaluate(() => document.documentElement.style.overflow),
  ).toBe("");
});

test("from the last album the sleeve flies into the group's stack", async ({
  page,
  context,
}) => {
  await attach(context, new FixtureBackend(ready()));
  await visit(page);
  for (const title of ["样例专辑 1", "样例专辑 2"]) {
    await expect(album(page, title)).toBeVisible();
    await page.getByRole("button", NEXT).click();
  }
  await expect(album(page, "样例专辑 3")).toBeVisible();
  await page.getByRole("button", NEXT).click();
  // No strips over the finale: the sleeve leaves whole and lands in the stack.
  await expect(
    page.locator('.cut[data-leaving] .sliced[data-exit="flight"]'),
  ).toHaveCount(1);
  await expect(page.locator(".cut[data-leaving] .slice")).toHaveCount(0);
  await expect(page.locator(".finale__stack .stack__slot")).toHaveCount(3);
  await expect(page.locator(".stack__slot[data-landing]")).toHaveCount(1);
  await expect(page.locator(".cut[data-leaving]")).toHaveCount(0, {
    timeout: 2000,
  });
  // Once the flying copy is gone its card stays put: no restarted entrance.
  const landed = page.locator(".stack__slot").last();
  await expect(landed).toHaveAttribute("data-landing", "true");
  for (let i = 0; i < 6; i++) {
    expect(
      await landed.evaluate((node) => ({
        opacity: getComputedStyle(node).opacity,
        running: node.getAnimations().filter((a) => a.playState === "running")
          .length,
      })),
    ).toEqual({ opacity: "1", running: 0 });
    await page.waitForTimeout(120);
  }
  await expect(page.getByText("这一组听完了")).toBeVisible();
});

test("the group's stack keeps every album of a large group on screen", async ({
  page,
  context,
}) => {
  await attach(context, new FixtureBackend(ready(10, 10)));
  await visit(page);
  await expect(album(page, "样例专辑 1")).toBeVisible();
  await page.locator(".pips li[data-tail] button").click();
  await expect(page.getByText("这一组听完了")).toBeVisible();
  const slots = page.locator(".finale__stack .stack__slot");
  await expect(slots).toHaveCount(10);
  const ids = await slots.evaluateAll((nodes) =>
    nodes.map((node) => node.getAttribute("data-candidate")),
  );
  expect(new Set(ids).size).toBe(10);
  const width = page.viewportSize()!.width;
  const first = (await slots.first().boundingBox())!;
  const last = (await slots.last().boundingBox())!;
  expect(first.x).toBeGreaterThanOrEqual(0);
  expect(last.x + last.width).toBeLessThanOrEqual(width);
});

test("one album at a time: edges and keys move, 听过了 records and can be taken back", async ({
  page,
  context,
}) => {
  const backend = new FixtureBackend(ready());
  await attach(context, backend);
  await visit(page);
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
  await visit(page);
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

test("long review research limit gives a specific message and keeps the retry action", async ({
  page,
  context,
}) => {
  const backend = new FixtureBackend(ready());
  await attach(context, backend, async (route) => {
    if (!route.request().url().endsWith("/open")) return false;
    const request = route.request();
    backend.reviewState = "FAILED";
    const response = backend.handle(
      request.method(),
      request.url(),
      request.postDataJSON(),
      request.headers(),
    );
    await route.fulfill({
      status: response.status,
      json: {
        ...(response.body as LongReviewView),
        message_key: "LONG_RESEARCH_LIMIT_REACHED",
      },
    });
    return true;
  });
  await visit(page);
  const card = notes(page, "样例专辑 1");
  await card.getByRole("button", { name: "读长评" }).click();
  await expect(
    card.getByText("已达到本次补查上限，长评暂未完成。"),
  ).toBeVisible();
  await expect(card.getByText("生成失败")).toHaveCount(0);
  await expect(card.getByRole("button", { name: "重新生成" })).toBeVisible();
  expect(posts(backend, "/regenerate")).toHaveLength(0);
});

test("after the last album: more of the same, or rethink with the old input", async ({
  page,
  context,
}) => {
  const backend = new FixtureBackend(ready());
  await attach(context, backend);
  await visit(page);
  for (const title of ["样例专辑 1", "样例专辑 2", "样例专辑 3"]) {
    await expect(album(page, title)).toBeVisible();
    await page.getByRole("button", NEXT).click();
  }
  await expect(page.getByText("这一组听完了")).toBeVisible();
  await expect(page.getByRole("button", NEXT)).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "换一些想法吧", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "再给我来一些", exact: true }).click();
  await expect(page.getByRole("region", { name: "正在推荐" })).toBeVisible();
  await expect(album(page, "样例专辑 10")).toBeVisible({ timeout: 10000 });
  expect(posts(backend, "/next-batch")).toHaveLength(1);

  for (let i = 0; i < 3; i++) await page.getByRole("button", NEXT).click();
  await page.getByRole("button", { name: /换一些想法吧/ }).click();
  await openDoor(page, /^你的任何想法都可以说/);
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
  await visit(page);
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
    await visit(page);
    await expect(page.getByRole("group", { name: "接下来" })).toBeVisible();
    await expect(page.getByRole("article")).toHaveCount(0);
    await page.getByRole("button", { name: /换一些想法吧/ }).click();
    await openDoor(page, /^你的任何想法都可以说/);
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
  await visit(page);
  await expect(page.getByText("排队中")).toBeVisible();
  backend.state = ready();
  await expect(album(page, "样例专辑 1")).toBeVisible({ timeout: 10000 });
  expect(
    backend.calls
      .filter((call) => call.method === "POST")
      .map((call) => call.path),
  ).toEqual(["/v1/bootstrap"]);
});

test("first card ends the wait and the actual batch tail appends once", async ({
  page,
  context,
}) => {
  const waiting = shared<ProductState>("fourth-request-waiting");
  const backend = new FixtureBackend(waiting);
  const full = ready();
  const tail = full.slots[1]!.card!.candidate_id;
  let requests = 0;
  await attach(context, backend, async (route) => {
    if (!route.request().url().endsWith("/next-batch")) return false;
    requests++;
    expect(route.request().postDataJSON().tail_candidate_id).toBe(tail);
    backend.state = {
      ...full,
      revision: 4,
      status: "RUNNING",
      supply_state: "SUPPLYING",
      slots: full.slots.slice(0, 2),
    };
    await route.fulfill({ json: backend.state });
    return true;
  });
  await visit(page);
  await expect(page.getByText("排队中")).toBeVisible();
  backend.state = {
    ...full,
    revision: 2,
    status: "RUNNING",
    supply_state: "SUPPLYING",
    slots: full.slots.slice(0, 1),
  };
  await expect(album(page, "样例专辑 1")).toBeVisible();
  expect(requests).toBe(0);

  backend.state = {
    ...full,
    revision: 3,
    status: "PARTIAL",
    supply_state: "SUFFICIENT",
    continuation_tail_id: tail,
    slots: full.slots.slice(0, 2),
  };
  await expect
    .poll(() =>
      backend.calls.some((call) => call.path.endsWith("/current-state")),
    )
    .toBe(true);
  expect(requests).toBe(0);
  await page.getByRole("button", NEXT).click();
  await expect(album(page, "样例专辑 2")).toBeVisible();
  await expect.poll(() => requests).toBe(1);
  backend.state = { ...full, revision: 5 };
  await expect(album(page, "样例专辑 2")).toBeVisible();
  await page.getByRole("button", NEXT).click();
  await expect(album(page, "样例专辑 3")).toBeVisible();
  expect(requests).toBe(1);
});

test("seeing the current last card asks for more while the run is still working", async ({
  page,
  context,
}) => {
  const full = ready();
  const first = full.slots[0]!.card!.candidate_id;
  const state: ProductState = {
    ...full,
    status: "RUNNING",
    supply_state: "SUPPLYING",
    continuation_tail_id: first,
    slots: full.slots.slice(0, 1),
  };
  const backend = new FixtureBackend(state);
  let requests = 0;
  await attach(context, backend, async (route) => {
    if (!route.request().url().endsWith("/next-batch")) return false;
    requests++;
    expect(route.request().postDataJSON().tail_candidate_id).toBe(first);
    const { continuation_tail_id: tail, ...accepted } = state;
    expect(tail).toBe(first);
    backend.state = { ...accepted, revision: state.revision + 1 };
    await route.fulfill({ json: backend.state });
    return true;
  });
  await visit(page);
  await expect(album(page, "样例专辑 1")).toBeVisible();
  await expect.poll(() => requests).toBe(1);
  backend.state = { ...full, revision: state.revision + 2 };
  await page.getByRole("button", NEXT).click();
  await expect(album(page, "样例专辑 2")).toBeVisible();
  expect(requests).toBe(1);
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
  await visit(page);
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
  await visit(page);
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
  await visit(page);
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
  await visit(page);
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
  await visit(page);
  await expect(album(page, "样例专辑 1")).toBeVisible();
  await page.waitForTimeout(1200);
  await page.screenshot({ path: "test-results/room.png" });
  await page.getByRole("link", { name: "Auralis 首页" }).click();
  await page.waitForTimeout(1600);
  await page.screenshot({ path: "test-results/entry.png" });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(400);
  await page.screenshot({ path: "test-results/mobile.png", fullPage: true });
  await page.getByRole("button", { name: "English" }).click();
  await page.waitForTimeout(900);
  await page.screenshot({ path: "test-results/mobile-en.png", fullPage: true });
  await page.setViewportSize({ width: 1360, height: 900 });
  await page.waitForTimeout(600);
  await page.screenshot({ path: "test-results/entry-en.png" });
});
