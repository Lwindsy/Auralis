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
  await page.getByRole("button", { name: /^风格/ }).click();
  await page.getByRole("button", { name: "喜欢爵士", exact: true }).click();
  await page.getByRole("button", { name: "爵士特别喜欢", exact: true }).click();
  await page.getByRole("button", { name: "爵士本次重点", exact: true }).click();
  await page.getByLabel("这次想听的音乐").fill(text);
  await page.getByRole("button", { name: "开始发现", exact: true }).click();
}

async function act(page: Page, title: string, action: string) {
  const list = page.getByRole("list", { name: "这一组曲目单" });
  if (await list.count())
    await list
      .getByRole("button", {
        name: new RegExp(title.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")),
      })
      .click();
  await page
    .getByRole("region", { name: `${title} 乐评与操作` })
    .getByRole("button", { name: action })
    .click();
}

const editor = (page: Page) =>
  page.getByRole("dialog", { name: "修改这次需求" });
const defaults = {
  hold: false,
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

test("real Java recommendations preserve input, consume cards and restore after reopening", async ({
  page,
  context,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await submit(page, "原文与重点保留 🎷");
  await expect(page.getByRole("article")).toHaveCount(2);
  const initial = await state(context);
  expect(initial.editable_input!.liked_genres[0]).toMatchObject({
    strength: "special",
    focused: true,
  });
  await page.getByRole("button", { name: "修改需求", exact: true }).click();
  await expect(editor(page).getByLabel("这次想听的音乐")).toHaveValue(
    "原文与重点保留 🎷",
  );
  await editor(page).getByLabel("这次想听的音乐").fill("取消修改应保持原文");
  await editor(page).getByRole("button", { name: "取消修改" }).click();
  await expect(editor(page)).toBeHidden();
  const first = page.getByRole("article").first();
  const title = (await first.getAttribute("aria-label"))!;
  await act(page, title, "已听过");
  await expect(
    page.getByRole("article", { name: title, exact: true }),
  ).toHaveCount(0);
  await expect(page.getByRole("article")).toHaveCount(2);
  expect((await control({ session_id: initial.session_id })).heard).toBe(1);
  const remaining = page.getByRole("article", {
    name: "新增专辑",
    exact: true,
  });
  await act(page, "新增专辑", "来张新的");
  await expect(remaining).toHaveCount(0);
  await expect(page.getByRole("article")).toHaveCount(2);
  expect((await control({ session_id: initial.session_id })).heard).toBe(1);
  await page.getByRole("button", { name: "换一批", exact: true }).click();
  await expect
    .poll(async () => (await state(context)).round_id)
    .not.toBe(initial.round_id);
  await expect(page.getByRole("article")).toHaveCount(2);
  const saved = await state(context);
  await page.reload();
  await expect(page.getByRole("article")).toHaveCount(2);
  expect((await state(context)).expires_at).toBe(saved.expires_at);
  await page.close();
  const reopened = await context.newPage();
  await reopened.goto("/");
  await expect(reopened.getByRole("article")).toHaveCount(2);
  expect((await state(context)).expires_at).toBe(saved.expires_at);
  await reopened.screenshot({
    path: "test-results/java-recommendation-desktop.png",
    fullPage: true,
  });
  await reopened.getByRole("button", { name: "修改需求", exact: true }).click();
  await expect(editor(reopened).getByLabel("这次想听的音乐")).toHaveValue(
    "原文与重点保留 🎷",
  );
  const changed = reopened.waitForResponse(
    (r) =>
      r.request().method() === "POST" &&
      r.url().endsWith("/v1/discovery-sessions"),
  );
  await editor(reopened).getByLabel("这次想听的音乐").fill("明确的新需求");
  await editor(reopened)
    .getByRole("button", { name: "提交新需求", exact: true })
    .click();
  expect(((await (await changed).json()) as ProductState).session_id).not.toBe(
    initial.session_id,
  );
  expect(
    await reopened.evaluate(() => [localStorage.length, sessionStorage.length]),
  ).toEqual([0, 0]);
  expect(JSON.stringify(await context.cookies())).not.toContain("原文与重点");
  expect(errors).toEqual([]);
});

test("long review placeholders poll, interrupted work regenerates only on click", async ({
  page,
  context,
}) => {
  await control({ long_delay_ms: 3000, fail_long: true });
  await submit(page);
  await expect(page.getByRole("article")).toHaveCount(2);
  await page.getByRole("button", { name: "详细乐评" }).first().click();
  await expect(page.locator(".placeholder").first()).toBeVisible();
  await expect(page.getByText("生成中断", { exact: true })).toBeVisible();
  const before = await control();
  const saved = await state(context);
  await page.waitForTimeout(2200);
  expect((await control()).model_calls).toBe(before.model_calls);
  expect((await state(context)).expires_at).toBe(saved.expires_at);
  await page.getByRole("button", { name: "重新生成", exact: true }).click();
  await expect(
    page.getByText("制作保留了空间感，乐器之间能听见清楚的层次。"),
  ).toBeVisible();
  expect((await control()).model_calls).toBeGreaterThan(before.model_calls);
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toBeHidden();
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
  await expect(
    page.getByText("本次个性化未完成，先看看这些基础推荐。"),
  ).toBeVisible();
  const before = await control();
  await page.reload();
  await expect(page.getByRole("article")).toHaveCount(2);
  expect((await control()).sends).toBe(before.sends);
  await page.getByRole("button", { name: "按原需求重新复筛" }).click();
  await expect(
    page.getByRole("button", { name: "按原需求重新复筛" }),
  ).toHaveCount(0);
  await expect
    .poll(async () => (await state(context)).analysis_mode)
    .toBe("PERSONALIZED");
  await expect(page.getByRole("article")).toHaveCount(2);
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
    await expect(
      page
        .getByText("正在等待推荐名额，轮到你时会自动继续。", { exact: true })
        .first(),
    ).toBeVisible();
    await control({ hold: false });
    await expect(page.getByRole("article")).toHaveCount(2);
  } finally {
    await control({ hold: false });
    for (const c of extra) await c.close();
  }
});

test("expired server session restores a blank editable page", async ({
  page,
  context,
}) => {
  await submit(page);
  await expect(page.getByRole("article")).toHaveCount(2);
  await control({
    expire: true,
    session_id: (await state(context)).session_id,
  });
  await page.reload();
  await expect(page.getByRole("article")).toHaveCount(0);
  await expect(page.getByLabel("这次想听的音乐")).toHaveValue("");
  await expect(
    page.getByRole("button", { name: "开始发现", exact: true }),
  ).toBeDisabled();
});

for (const [limit, status, copy] of [
  [1, "PARTIAL", "本次找到的合格专辑不足目标数量，先听听这些。"],
  [0, "NO_MATCH", "暂时没有合适的结果，试着补充更多信息。"],
] as const) {
  test(`real ${status} does not fabricate cards`, async ({ page, context }) => {
    await control({ recall_limit: limit });
    await submit(page, "保留不足结果的输入");
    await expect.poll(async () => (await state(context)).status).toBe(status);
    await expect(page.getByRole("article")).toHaveCount(limit);
    await expect(page.getByText(copy, { exact: true }).first()).toBeVisible();
    await page
      .getByRole("button", {
        name: limit ? "修改需求" : "重新输入",
        exact: true,
      })
      .click();
    await expect(editor(page).getByLabel("这次想听的音乐")).toHaveValue(
      "保留不足结果的输入",
    );
  });
}
