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

const posts = (backend: FixtureBackend, suffix: string) =>
  backend.calls.filter(
    (call) => call.method === "POST" && call.path.endsWith(suffix),
  );

test("actual fixture HTTP restores via HttpOnly cookie without storing input in browser storage", async ({
  page,
  context,
}) => {
  await page.goto("/");
  await expect(page.getByText("契约样例模式")).toBeVisible();
  await page.getByLabel("这次想听的音乐").fill("中文原文 only on server");
  await page.getByRole("button", { name: "更多设置" }).click();
  await page.getByLabel("最低听众数（可选）").fill("500");
  await page.getByRole("button", { name: "开始发现", exact: true }).click();
  await expect(page.getByRole("article")).toHaveCount(3);
  const cookies = await context.cookies();
  expect(cookies.find((cookie) => cookie.name === "fixture_id")!.httpOnly).toBe(
    true,
  );
  expect(JSON.stringify(cookies)).not.toContain("中文原文");
  await page.close();
  const reopened = await context.newPage();
  await reopened.goto("/");
  await expect(reopened.getByRole("article")).toHaveCount(3);
  await reopened.getByRole("button", { name: "修改需求", exact: true }).click();
  const editor = reopened.getByRole("dialog", { name: "修改这次需求" });
  await expect(editor.getByLabel("最低听众数（可选）")).toHaveValue("500");
  await expect(editor.getByLabel("这次想听的音乐")).toHaveValue(
    "中文原文 only on server",
  );
  expect(
    await reopened.evaluate(() => [localStorage.length, sessionStorage.length]),
  ).toEqual([0, 0]);
});

test("input marks, edit cancellation, replacement, batches and restoration", async ({
  page,
  context,
}) => {
  const backend = new FixtureBackend();
  await attach(context, backend);
  await page.goto("/");
  await expect(
    page.getByRole("button", { name: "开始发现", exact: true }),
  ).toBeDisabled();
  await page.getByRole("button", { name: /^风格/ }).click();
  await page.getByRole("button", { name: "Jazz", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "喜欢Cool Jazz", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "喜欢Jazz", exact: true }),
  ).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: "Jazz特别喜欢", exact: true }).click();
  await page.getByRole("button", { name: "Jazz本次重点", exact: true }).click();
  await page.getByRole("button", { name: "不喜欢Metal", exact: true }).click();
  await page.getByLabel("这次想听的音乐").fill("温暖的爵士 🎷");
  await page.getByLabel("惊喜", { exact: true }).check();
  await expect(
    page.getByRole("button", { name: "开始发现", exact: true }),
  ).toBeEnabled();
  await page.getByRole("button", { name: "开始发现", exact: true }).click();
  await expect(page.getByRole("article")).toHaveCount(3);
  expect(backend.state.editable_input!.liked_genres[0]).toEqual({
    genre_id: "jazz",
    strength: "special",
    focused: true,
  });
  expect(backend.state.editable_input!.disliked_genre_ids).toEqual(["metal"]);
  expect(backend.state.editable_input!.mode).toBe("surprise");
  await expect(page.getByRole("list", { name: "本次需求" })).toContainText(
    "温暖的爵士",
  );

  await page.getByRole("button", { name: "修改需求", exact: true }).click();
  const editor = page.getByRole("dialog", { name: "修改这次需求" });
  await expect(editor.getByLabel("这次想听的音乐")).toBeFocused();
  await expect(editor.getByLabel("这次想听的音乐")).toHaveValue(
    "温暖的爵士 🎷",
  );
  await expect(
    editor.getByRole("button", { name: "Jazz特别喜欢", exact: true }),
  ).toHaveAttribute("aria-pressed", "true");
  await editor.getByLabel("这次想听的音乐").fill("不应提交");
  await editor.getByRole("button", { name: "取消修改" }).click();
  await expect(editor).toBeHidden();
  expect(backend.state.editable_input!.request_text).toBe("温暖的爵士 🎷");

  await act(page, "样例专辑 1", "来张新的");
  await expect(
    page.getByRole("article", { name: "样例专辑 1", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("article", { name: "样例专辑 2", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("正在补充", { exact: true }).first(),
  ).toBeVisible();
  await expect(page.getByRole("article")).toHaveCount(3);
  expect(backend.heard.size).toBe(0);
  await act(page, "样例专辑 2", "已听过");
  await expect(page.getByRole("article")).toHaveCount(3);
  expect(backend.heard.has("candidate-2")).toBe(true);
  await page.getByRole("button", { name: "换一批", exact: true }).click();
  await expect(
    page.getByRole("article", { name: "样例专辑 3", exact: true }),
  ).toHaveCount(0);
  const expiry = backend.state.expires_at;
  await page.reload();
  await expect(page.getByText(/欢迎回来/)).toBeVisible();
  await expect(page.getByRole("article")).toHaveCount(3);
  await page.close();
  const reopened = await context.newPage();
  await reopened.goto("/");
  await expect(reopened.getByRole("article")).toHaveCount(3);
  expect(backend.state.expires_at).toBe(expiry);
});

test("long review placeholder polls with GET; interrupted review regenerates only explicitly", async ({
  page,
  context,
}) => {
  const backend = new FixtureBackend(ready());
  await attach(context, backend);
  await page.goto("/");
  await page.getByRole("button", { name: "详细乐评" }).first().click();
  const sheet = page.getByRole("dialog", { name: "样例专辑 1" });
  await expect(sheet.getByText("详细乐评生成中").first()).toBeVisible();
  await expect(sheet.locator(".placeholder").first()).toBeVisible();
  await expect(sheet.getByText("第二段合成文字，验证多段落排版。")).toBeVisible(
    {
      timeout: 8000,
    },
  );
  expect(posts(backend, "/open")).toHaveLength(1);
  expect(posts(backend, "/regenerate")).toHaveLength(0);
  await page.getByRole("button", { name: "关闭乐评" }).click();
  await expect(sheet).toBeHidden();
  backend.reviewState = "INTERRUPTED";
  await page.getByRole("button", { name: "详细乐评" }).first().click();
  await expect(page.getByText("生成中断", { exact: true })).toBeVisible();
  await page.waitForTimeout(2500);
  expect(posts(backend, "/regenerate")).toHaveLength(0);
  await page.getByRole("button", { name: "重新生成", exact: true }).click();
  await expect(page.getByText("详细乐评生成中").first()).toBeVisible();
  expect(posts(backend, "/regenerate")).toHaveLength(1);
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toBeHidden();
});

test("BASIC rescreen is explicit and non-default target is displayed", async ({
  page,
  context,
}) => {
  const state = ready(2, 5);
  state.analysis_mode = "BASIC";
  state.slots.forEach((slot) => {
    slot.card!.long_review_state = "FAILED";
  });
  const backend = new FixtureBackend(state);
  await attach(context, backend);
  await page.goto("/");
  await expect(
    page.getByText("本次个性化未完成，先看看这些基础推荐。"),
  ).toBeVisible();
  await expect(page.getByText("已展示 2 张 · 本组目标 5 张")).toBeVisible();
  expect(posts(backend, "/rescreen")).toHaveLength(0);
  await page.getByRole("button", { name: "按原需求重新复筛" }).click();
  await expect(
    page.getByRole("button", { name: "按原需求重新复筛" }),
  ).toHaveCount(0);
  expect(posts(backend, "/rescreen")).toHaveLength(1);
});

test("CURATED results explain where the group came from", async ({
  page,
  context,
}) => {
  const state = ready();
  state.analysis_mode = "CURATED";
  await attach(context, new FixtureBackend(state));
  await page.goto("/");
  await expect(page.getByText(/这一组来自编辑精选/)).toBeVisible();
});

for (const status of ["NO_MATCH", "FAILED", "INTERRUPTED"] as const) {
  test(`${status} has no empty cards and edit preserves input`, async ({
    page,
    context,
  }) => {
    const state = ready(0);
    state.status = status;
    const backend = new FixtureBackend(state);
    await attach(context, backend);
    await page.goto("/");
    await expect(
      page.getByRole("group", { name: "没有可展示的专辑" }),
    ).toBeVisible();
    await expect(page.getByRole("article")).toHaveCount(0);
    await page.getByRole("button", { name: "重新输入", exact: true }).click();
    await expect(
      page
        .getByRole("dialog", { name: "修改这次需求" })
        .getByLabel("这次想听的音乐"),
    ).toHaveValue("想听轻松的爵士");
  });
}

test("WAITING automatically becomes ready without another POST", async ({
  page,
  context,
}) => {
  const backend = new FixtureBackend(
    shared<ProductState>("fourth-request-waiting"),
  );
  await attach(context, backend);
  await page.goto("/");
  await expect(
    page
      .getByText("正在等待推荐名额，轮到你时会自动继续。", { exact: true })
      .first(),
  ).toBeVisible();
  await expect(page.getByText("排队中")).toBeVisible();
  backend.state = ready();
  await expect(page.getByRole("article")).toHaveCount(3);
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
  await page
    .getByRole("button", { name: "已听过", exact: true })
    .first()
    .click();
  await expect(page.getByRole("alert")).toContainText("操作是否完成尚不确定");
  await expect(
    page.getByRole("button", { name: "重试原操作", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "换一批", exact: true }),
  ).toBeDisabled();
  await page
    .getByRole("button", { name: "换一批", exact: true })
    .click({ force: true });
  await page.getByRole("button", { name: "重试原操作", exact: true }).click();
  await expect(page.getByRole("article")).toHaveCount(3);
  await expect(page.getByRole("alert")).toHaveCount(0);
  const calls = backend.calls.filter((call) => call.path.endsWith("/actions"));
  expect(calls).toHaveLength(2);
  expect(calls[0]!.headers["idempotency-key"]).toBe(
    calls[1]!.headers["idempotency-key"],
  );
  expect(posts(backend, "/next-batch")).toHaveLength(0);
  expect(backend.heard.size).toBe(1);
});

test("catalog outside-core selection sends only MBID and marks; removal resets marks", async ({
  page,
  context,
}) => {
  const backend = new FixtureBackend();
  await attach(context, backend);
  await page.goto("/");
  const name = "Synthetic 外部专辑 — Synthetic Artist";
  await page.getByRole("button", { name: /^专辑/ }).click();
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
  await page.getByRole("button", { name: "开始发现", exact: true }).click();
  await expect(page.getByRole("article")).toHaveCount(3);
  expect(backend.state.editable_input!.liked_albums[0]).toEqual({
    mb_release_group_id: "11111111-1111-4111-8111-111111111111",
    strength: "normal",
    focused: false,
  });
  await page.reload();
  await page.getByRole("button", { name: "修改需求", exact: true }).click();
  await expect(
    page.getByRole("button", { name: `${name}特别喜欢` }),
  ).toHaveAttribute("aria-pressed", "false");
});

test("mobile layout and keyboard operate with no horizontal overflow or unsafe markup", async ({
  page,
  context,
}) => {
  const state = ready();
  state.slots[0]!.card!.identity.title = "<img src=x onerror=alert(1)>";
  const backend = new FixtureBackend(state);
  await attach(context, backend);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await expect(page.getByRole("article")).toHaveCount(3);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await expect(
    page.getByRole("heading", {
      name: "<img src=x onerror=alert(1)>",
      exact: true,
    }),
  ).toBeVisible();
  await page.getByRole("button", { name: "修改需求", exact: true }).focus();
  await page.keyboard.press("Enter");
  const editor = page.getByRole("dialog", { name: "修改这次需求" });
  await expect(editor.getByLabel("这次想听的音乐")).toBeFocused();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await editor.getByLabel("这次想听的音乐").fill("键盘提交");
  await editor.getByRole("button", { name: "提交新需求" }).focus();
  await page.keyboard.press("Enter");
  await expect(editor).toBeHidden();
  await expect(page.getByRole("article")).toHaveCount(3);
  expect(backend.state.editable_input!.request_text).toBe("键盘提交");
  expect(
    await page.evaluate(() => [localStorage.length, sessionStorage.length]),
  ).toEqual([0, 0]);
});

test("expired cookie opens an empty editor with an expiry notice", async ({
  page,
  context,
}) => {
  const state = { ...idle(), message_key: "session.expired" };
  await attach(context, new FixtureBackend(state));
  await page.goto("/");
  await expect(
    page.getByText("上次发现已过期，已为你打开空白页面，可以重新开始。"),
  ).toBeVisible();
  await expect(page.getByLabel("这次想听的音乐")).toHaveValue("");
  await expect(
    page.getByRole("button", { name: "开始发现", exact: true }),
  ).toBeDisabled();
});

test("visual baselines for desktop, dark and mobile", async ({
  page,
  context,
  browser,
}) => {
  const backend = new FixtureBackend(ready());
  await attach(context, backend);
  await page.setViewportSize({ width: 1360, height: 900 });
  await page.goto("/");
  await expect(page.getByRole("article")).toHaveCount(3);
  await page.waitForTimeout(700);
  await page.screenshot({ path: "test-results/desktop.png" });
  const dark = await browser.newContext({
    colorScheme: "dark",
    viewport: { width: 1360, height: 900 },
    baseURL: "http://127.0.0.1:4187",
  });
  await attach(dark, new FixtureBackend(ready()));
  const darkPage = await dark.newPage();
  await darkPage.goto("/");
  await expect(darkPage.getByRole("article")).toHaveCount(3);
  await darkPage.waitForTimeout(700);
  await darkPage.screenshot({ path: "test-results/desktop-dark.png" });
  await dark.close();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(300);
  await page.screenshot({ path: "test-results/mobile.png", fullPage: true });
});
