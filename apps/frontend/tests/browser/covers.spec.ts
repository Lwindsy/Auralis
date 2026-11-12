import {
  expect,
  test,
  type BrowserContext,
  type Route,
} from "@playwright/test";
import { FixtureBackend, ready } from "../fixtures.ts";

const png = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAEUlEQVR4nGP4ryCAFTEMLQkA1TtLwVwHwvMAAAAASUVORK5CYII=",
  "base64",
);
const url = (n: number) =>
  `/v1/catalog/albums/00000000-0000-0000-0000-${String(n).padStart(12, "0")}/cover?size=500`;

async function attach(
  context: BrowserContext,
  backend: FixtureBackend,
  cover: (route: Route) => Promise<void>,
) {
  await context.route("**/v1/**", async (route) => {
    const request = route.request();
    if (new URL(request.url()).pathname.endsWith("/cover")) {
      await cover(route);
      return;
    }
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

test("slow search cover does not delay results or selection", async ({
  page,
  context,
}) => {
  const backend = new FixtureBackend();
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  let calls = 0;
  await attach(context, backend, async (route) => {
    calls++;
    await gate;
    await route.fulfill({ status: 200, contentType: "image/png", body: png });
  });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/");
  // The page opens in English; these checks read the Chinese copy.
  await page.getByRole("button", { name: "中文", exact: true }).click();
  await page.getByRole("button", { name: /^专辑/ }).click();
  await page.getByLabel("查找专辑").fill("Synthetic");
  await page.getByLabel("查找专辑").press("Enter");
  const shelf = page.getByRole("list", { name: "专辑搜索结果" });
  await expect(
    shelf.getByRole("button", { name: /^添加/ }).first(),
  ).toBeVisible();
  await expect.poll(() => calls).toBeGreaterThan(0);
  await expect(shelf.locator(".cover img")).toHaveCount(0);
  await shelf.getByRole("button", { name: /^添加/ }).first().click();
  await page.evaluate(() => document.fonts.ready);
  const bounds = await shelf
    .locator(".cover")
    .first()
    .evaluate((el) => ({ width: el.clientWidth, height: el.clientHeight }));
  release();
  await expect(shelf.locator(".cover img").first()).toBeVisible();
  const after = await shelf
    .locator(".cover")
    .first()
    .evaluate((el) => ({ width: el.clientWidth, height: el.clientHeight }));
  expect(after?.width).toBe(bounds?.width);
  expect(after?.height).toBe(bounds?.height);
});

test("the current cover retries in place after a temporary failure", async ({
  page,
  context,
}) => {
  const backend = new FixtureBackend(ready(2, 2));
  backend.state.slots.forEach((slot, i) => {
    slot.card!.cover_url = url(i + 1);
  });
  let currentCalls = 0;
  let missingCalls = 0;
  await attach(context, backend, async (route) => {
    if (new URL(route.request().url()).pathname.includes("000000000002")) {
      missingCalls++;
      await route.fulfill({ status: 404 });
      return;
    }
    currentCalls++;
    if (currentCalls === 1) {
      await route.fulfill({ status: 503, headers: { "Retry-After": "15" } });
      return;
    }
    await route.fulfill({ status: 200, contentType: "image/png", body: png });
  });
  await page.clock.install();
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/");
  await page.getByRole("button", { name: "中文", exact: true }).click();
  const first = page.getByRole("article", { name: "样例专辑 1", exact: true });
  await expect(first).toBeVisible();
  await expect.poll(() => [currentCalls, missingCalls]).toEqual([1, 1]);
  await expect(first.locator(".cover img")).toHaveCount(0);
  await page.clock.fastForward(16000);
  await expect(first.locator(".sliced__whole img")).toBeVisible();
  expect(currentCalls).toBe(2);

  await page.getByRole("button", { name: "下一张", exact: true }).click();
  const second = page.getByRole("article", { name: "样例专辑 2", exact: true });
  await expect(second).toBeVisible();
  await page.clock.fastForward(16000);
  await expect(second.locator(".cover img")).toHaveCount(0);
  expect(missingCalls).toBe(1);
});

test("slices and colour share one decoded cover; old response cannot repaint a new card", async ({
  page,
  context,
}, testInfo) => {
  const backend = new FixtureBackend();
  backend.state = ready(2, 2);
  backend.state.slots.forEach((slot, i) => {
    slot.card!.cover_url = url(i + 1);
  });
  const counts = new Map<string, number>();
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  await attach(context, backend, async (route) => {
    const path = new URL(route.request().url()).pathname;
    counts.set(path, (counts.get(path) ?? 0) + 1);
    if (path.includes("000000000001")) {
      await gate;
      await route.fulfill({ status: 200, contentType: "image/png", body: png });
    } else await route.fulfill({ status: 404 });
  });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/");
  // The page opens in English; these checks read the Chinese copy.
  await page.getByRole("button", { name: "中文", exact: true }).click();
  await expect(
    page.getByRole("article", { name: "样例专辑 1", exact: true }),
  ).toBeVisible();
  await expect.poll(() => counts.size).toBe(2); // next cover is prefetched
  await page.getByRole("button", { name: "下一张", exact: true }).click();
  const second = page.getByRole("article", { name: "样例专辑 2", exact: true });
  await expect(second).toBeVisible();
  release();
  await expect(second.locator(".cover img")).toHaveCount(0);
  await page.getByRole("button", { name: "上一张", exact: true }).click();
  const first = page.getByRole("article", { name: "样例专辑 1", exact: true });
  await expect(first.locator(".sliced__whole img")).toBeVisible();
  // One decoded image: the whole cover shows it, and any strips still on screen paint
  // the same object URL as their background instead of loading their own copy.
  await expect(first.locator(".cover img")).toHaveCount(1);
  const src = await first.locator(".sliced__whole img").getAttribute("src");
  const strips = await first
    .locator(".slice__art")
    .evaluateAll((items) =>
      items.map((item) => (item as HTMLElement).style.backgroundImage),
    );
  expect(strips.every((paint) => paint.includes(src!))).toBe(true);
  expect([...counts.values()]).toEqual([1, 1]);
  const sampled = await page.evaluate(async () => {
    const image = document.querySelector<HTMLImageElement>(
      'article[aria-label="样例专辑 1"] .sliced__whole img',
    )!;
    const canvas = document.createElement("canvas");
    canvas.width = canvas.height = 1;
    const ctx = canvas.getContext("2d")!;
    ctx.drawImage(image, 0, 0, 1, 1);
    return [...ctx.getImageData(0, 0, 1, 1).data];
  });
  expect(sampled.slice(0, 3)).toEqual([255, 32, 16]);
  await page.screenshot({ path: testInfo.outputPath("loaded-cover.png") });
});
