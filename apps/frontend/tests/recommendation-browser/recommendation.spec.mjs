import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import { validate } from "../../src/api.js";

const harness = JSON.parse(
  readFileSync(process.env.AURALIS_PRODUCT_BROWSER_HARNESS, "utf8"),
);
async function control(body = {}) {
  const response = await fetch(harness.control, {
    method: "POST",
    headers: { "X-Test-Key": harness.key, "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  expect(response.status).toBe(200);
  return response.json();
}
async function state(context) {
  const response = await context.request.get("/v1/current-state");
  expect(response.status()).toBe(200);
  const value = await response.json();
  validate("ProductState", value);
  return value;
}
async function submit(page, text = "爵士新发现 🎷") {
  await page.goto("/");
  await page.getByRole("button", { name: "喜欢爵士", exact: true }).click();
  await page
    .getByLabel("爵士喜欢程度", { exact: true })
    .selectOption("special");
  await page.getByLabel("本次重点", { exact: true }).check();
  await page.getByLabel("这次想听的音乐").fill(text);
  await page.getByRole("button", { name: "开始发现", exact: true }).click();
}
test.beforeEach(async () => {
  await control({
    hold: false,
    basic: false,
    fail_long: false,
    long_delay_ms: 600,
    recall_limit: 12,
  });
});
test.afterEach(async () => {
  await control({
    hold: false,
    basic: false,
    fail_long: false,
    long_delay_ms: 600,
    recall_limit: 12,
  });
  await expect.poll(async () => (await control()).active).toBe(0);
});

test("real Java recommendations preserve input, consume cards and restore after reopening", async ({
  page,
  context,
}) => {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await submit(page, "原文与重点保留 🎷");
  await expect(page.getByRole("article")).toHaveCount(2);
  const initial = await state(context);
  expect(initial.editable_input.liked_genres[0]).toMatchObject({
    strength: "special",
    focused: true,
  });
  await page.getByRole("button", { name: "修改需求", exact: true }).click();
  await expect(page.getByLabel("这次想听的音乐")).toHaveValue(
    "原文与重点保留 🎷",
  );
  await page.getByLabel("这次想听的音乐").fill("取消修改应保持原文");
  await page.getByRole("button", { name: "取消修改" }).click();
  const first = page.getByRole("article").first();
  const title = await first.getAttribute("aria-label");
  await first.getByRole("button", { name: "已听过" }).click();
  await expect(
    page.getByRole("article", { name: title, exact: true }),
  ).toHaveCount(0);
  await expect(page.getByRole("article")).toHaveCount(2);
  expect((await control({ session_id: initial.session_id })).heard).toBe(1);
  const remaining = page.getByRole("article", {
    name: "新增专辑",
    exact: true,
  });
  await remaining.getByRole("button", { name: "来张新的" }).click();
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
  await expect(reopened.getByLabel("这次想听的音乐")).toHaveValue(
    "原文与重点保留 🎷",
  );
  const changed = reopened.waitForResponse(
    (r) =>
      r.request().method() === "POST" &&
      r.url().endsWith("/v1/discovery-sessions"),
  );
  await reopened.getByLabel("这次想听的音乐").fill("明确的新需求");
  await reopened
    .getByRole("button", { name: "提交新需求", exact: true })
    .click();
  expect((await (await changed).json()).session_id).not.toBe(
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
  await expect(page.locator(".placeholder")).toHaveCount(4);
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
  await expect(page.getByRole("dialog")).not.toBeVisible();
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
  const extra = [];
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
]) {
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
    await expect(page.getByLabel("这次想听的音乐")).toHaveValue(
      "保留不足结果的输入",
    );
  });
}
