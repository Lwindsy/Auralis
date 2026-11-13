import { readFileSync } from "node:fs";
import { expect, test, type BrowserContext, type Page } from "@playwright/test";
import { validate } from "../../src/api/client.ts";
import type { ProductState } from "../../src/api/types.ts";

const harness = JSON.parse(
  readFileSync(process.env.AURALIS_PRODUCT_BROWSER_HARNESS!, "utf8"),
) as { control: string; key: string; origin: string };

interface ControlState {
  active: number;
  heard: number;
  model_calls: number;
  sends: number;
}

async function control(
  body: Record<string, unknown> = {},
): Promise<ControlState> {
  const response = await fetch(harness.control, {
    method: "POST",
    headers: { "X-Test-Key": harness.key, "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  expect(response.status).toBe(200);
  return (await response.json()) as ControlState;
}

async function state(context: BrowserContext): Promise<ProductState> {
  const response = await context.request.get("/v1/current-state");
  expect(response.status()).toBe(200);
  return validate("ProductState", await response.json());
}

async function submit(page: Page, text = "爵士新发现 🎷") {
  await page.goto("/");
  // The page opens in English; these checks read the Chinese copy.
  await page.getByRole("button", { name: "中文", exact: true }).click();
  await page.getByRole("button", { name: /^风格/ }).click();
  // A root genre without sub-genres sits in the display-only Unique group.
  const like = page.getByRole("button", { name: "喜欢爵士", exact: true });
  const unique = page.getByRole("button", { name: "展开Unique", exact: true });
  await expect(like.or(unique).first()).toBeVisible();
  if (!(await like.isVisible())) await unique.click();
  await like.click();
  await page.getByRole("button", { name: "爵士特别喜欢", exact: true }).click();
  await page.getByRole("button", { name: "爵士本次重点", exact: true }).click();
  if (text) {
    await page.getByRole("button", { name: /^你的任何想法都可以说/ }).click();
    await page.getByLabel("这次想听的音乐").fill(text);
  }
  await page.getByRole("button", { name: "开始发现", exact: true }).click();
}

/** The room shows one album at a time; the wait winds down before it appears. */
const room = (page: Page) => page.getByRole("region", { name: "推荐" });
const shown = (page: Page) => room(page).getByRole("article");
const SETTLE = { timeout: 20000 };

async function toFinale(page: Page) {
  await expect.poll(async () => (await control()).active).toBe(0);
  const live = await state(page.context());
  const ready = live.slots.filter((slot) => slot.status === "READY").length;
  const retained = await page.locator(".pips li[data-heard=true]").count();
  await expect(page.getByRole("button", { name: /^第 \d+ 张$/ })).toHaveCount(
    ready + retained,
  );
  const next = page.getByRole("button", { name: "下一张", exact: true });
  while (await next.isEnabled()) await next.click();
  await expect(page.getByRole("group", { name: "接下来" })).toBeVisible();
}

async function textOnEntry(page: Page) {
  await page.getByRole("button", { name: /^你的任何想法都可以说/ }).click();
  return page.getByLabel("这次想听的音乐");
}

const defaults = {
  hold: false,
  hold_long: false,
  basic: false,
  fail_long: false,
  long_delay_ms: 600,
  recall_limit: 12,
};

test.beforeEach(async () => {
  await control(defaults);
});
test.afterEach(async () => {
  await control(defaults);
  await expect.poll(async () => (await control()).active).toBe(0);
});

test("real Java recommendations keep input, record 听过了 and restore after reopening", async ({
  page,
  context,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await submit(page, "原文与重点保留 🎷");
  await expect(shown(page)).toHaveCount(1, SETTLE);
  const initial = await state(context);
  expect(initial.editable_input!.liked_genres[0]).toMatchObject({
    strength: "special",
    focused: true,
  });
  // Back to the start page and return without submitting anything.
  await page.getByRole("link", { name: "Auralisy 首页" }).click();
  await expect(await textOnEntry(page)).toHaveValue("原文与重点保留 🎷");
  await page.getByLabel("这次想听的音乐").fill("不提交的修改");
  await page.getByRole("button", { name: /回到刚才的推荐/ }).click();
  await expect(shown(page)).toHaveCount(1);
  expect((await state(context)).session_id).toBe(initial.session_id);

  const title = (await shown(page).getAttribute("aria-label"))!;
  const heard = shown(page).getByRole("button", { name: "听过了" });
  await heard.click();
  await expect(heard).toHaveAttribute("aria-pressed", "true");
  await expect(
    page.getByRole("article", { name: title, exact: true }),
  ).toBeVisible();
  await expect
    .poll(async () => (await control({ session_id: initial.session_id })).heard)
    .toBe(1);

  await toFinale(page);
  await page.getByRole("button", { name: /再给我来一些/ }).click();
  await expect
    .poll(async () => (await state(context)).round_id)
    .not.toBe(initial.round_id);
  await expect(shown(page)).toHaveCount(1, SETTLE);
  const saved = await state(context);
  await page.reload();
  await expect(shown(page)).toHaveCount(1);
  expect((await state(context)).expires_at).toBe(saved.expires_at);
  await page.close();
  const reopened = await context.newPage();
  await reopened.goto("/");
  // The page opens in English; these checks read the Chinese copy.
  await reopened.getByRole("button", { name: "中文", exact: true }).click();
  await expect(shown(reopened)).toHaveCount(1);
  expect((await state(context)).expires_at).toBe(saved.expires_at);
  await reopened.screenshot({
    path: "test-results/java-recommendation-desktop.png",
    fullPage: true,
  });
  await toFinale(reopened);
  await reopened.getByRole("button", { name: /换一些想法吧/ }).click();
  await expect(await textOnEntry(reopened)).toHaveValue("原文与重点保留 🎷");
  const changed = reopened.waitForResponse(
    (r) =>
      r.request().method() === "POST" &&
      r.url().endsWith("/v1/discovery-sessions"),
  );
  await reopened.getByLabel("这次想听的音乐").fill("明确的新需求");
  await reopened.getByRole("button", { name: "开始发现", exact: true }).click();
  expect(((await (await changed).json()) as ProductState).session_id).not.toBe(
    initial.session_id,
  );
  expect(
    await reopened.evaluate(() => [localStorage.length, sessionStorage.length]),
  ).toEqual([0, 0]);
  expect(JSON.stringify(await context.cookies())).not.toContain("原文与重点");
  expect(errors).toEqual([]);
});

test("long review writes in place, interrupted work regenerates only on click", async ({
  page,
  context,
}) => {
  await control({ hold_long: true, long_delay_ms: 0, fail_long: true });
  await submit(page);
  await expect(shown(page)).toHaveCount(1, SETTLE);
  await shown(page).getByRole("button", { name: "读长评" }).click();
  await expect(shown(page).getByText("长评写作中")).toBeVisible();
  await control({ hold_long: false });
  await expect(shown(page).getByText(/生成中断/)).toBeVisible({
    timeout: 10000,
  });
  const before = await control();
  const saved = await state(context);
  await page.waitForTimeout(2200);
  expect((await control()).model_calls).toBe(before.model_calls);
  expect((await state(context)).expires_at).toBe(saved.expires_at);
  await shown(page)
    .getByRole("button", { name: "重新生成", exact: true })
    .click();
  await expect(
    page.getByText("制作保留了空间感，乐器之间能听见清楚的层次。"),
  ).toBeVisible({ timeout: 10000 });
  expect((await control()).model_calls).toBeGreaterThan(before.model_calls);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: "test-results/java-recommendation-mobile.png",
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});

test("BASIC rescreen is explicit against the real provider", async ({
  page,
  context,
}) => {
  await control({ basic: true });
  await submit(page, "");
  await expect(page.getByText("基础推荐 · 本次个性化未完成")).toBeVisible(
    SETTLE,
  );
  const before = await control();
  await page.reload();
  await expect(shown(page)).toHaveCount(1);
  expect((await control()).sends).toBe(before.sends);
  await toFinale(page);
  await page.getByRole("button", { name: "按原需求重新复筛" }).click();
  await expect
    .poll(async () => (await state(context)).analysis_mode)
    .toBe("PERSONALIZED");
  await expect(shown(page)).toHaveCount(1, SETTLE);
});

test("a fourth real browser request waits and proceeds automatically", async ({
  page,
  browser,
}) => {
  await control({ hold: true });
  const extra: BrowserContext[] = [];
  try {
    for (let n = 0; n < 3; n++) {
      const c = await browser.newContext({ baseURL: "http://127.0.0.1:4189" });
      extra.push(c);
      const p = await c.newPage();
      await submit(p, "");
      await expect.poll(async () => (await state(c)).status).toBe("RUNNING");
    }
    await submit(page, "");
    await expect(page.getByText("排队中")).toBeVisible(SETTLE);
    await control({ hold: false });
    await expect(shown(page)).toHaveCount(1, SETTLE);
  } finally {
    await control({ hold: false });
    for (const c of extra) await c.close();
  }
});

test("expired server session restores a blank start page", async ({
  page,
  context,
}) => {
  await submit(page);
  await expect(shown(page)).toHaveCount(1, SETTLE);
  await control({
    expire: true,
    session_id: (await state(context)).session_id,
  });
  await page.reload();
  await expect(page.getByRole("article")).toHaveCount(0);
  await expect(await textOnEntry(page)).toHaveValue("");
  await expect(
    page.getByRole("button", { name: "开始发现", exact: true }),
  ).toBeDisabled();
});

for (const [limit, status] of [
  [1, "PARTIAL"],
  [0, "NO_MATCH"],
] as const) {
  test(`real ${status} does not fabricate cards`, async ({ page, context }) => {
    await control({ recall_limit: limit });
    await submit(page, "保留不足结果的输入");
    await expect.poll(async () => (await state(context)).status).toBe(status);
    await expect(
      page.getByRole("list", { name: "位置" }).getByRole("listitem"),
    ).toHaveCount(limit + 1, SETTLE);
    await toFinale(page);
    await page.getByRole("button", { name: /换一些想法吧/ }).click();
    await expect(await textOnEntry(page)).toHaveValue("保留不足结果的输入");
  });
}
