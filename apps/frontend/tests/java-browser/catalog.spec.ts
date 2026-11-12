import { expect, test } from "@playwright/test";
import { validate } from "../../src/api/client.ts";
import type { AlbumSearchResult, BootstrapState } from "../../src/api/types.ts";

test("real Java bootstrap, genres, full catalog selection and read-only recovery", async ({
  page,
  context,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  const bootstrap = page.waitForResponse((response) =>
    response.url().endsWith("/v1/bootstrap"),
  );
  await page.goto("/");
  // The page opens in English; these checks read the Chinese copy.
  await page.getByRole("button", { name: "中文", exact: true }).click();
  const initial = validate(
    "BootstrapState",
    await (await bootstrap).json(),
  ) as BootstrapState;
  await page.getByRole("button", { name: /^风格/ }).click();
  // A root genre without sub-genres is listed under the display-only Unique group.
  const rock = page.getByRole("button", { name: "喜欢Rock", exact: true });
  const unique = page.getByRole("button", { name: "展开Unique", exact: true });
  await expect(rock.or(unique).first()).toBeVisible();
  if (!(await rock.isVisible())) await unique.click();
  await expect(rock).toBeVisible();
  await page.getByRole("button", { name: "喜欢Rock", exact: true }).click();
  await page.getByRole("button", { name: "Rock特别喜欢", exact: true }).click();
  await page.getByRole("button", { name: "Rock本次重点", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Rock特别喜欢", exact: true }),
  ).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: /^专辑/ }).click();
  const albumSearch = page.waitForResponse((response) =>
    response.url().includes("/v1/catalog/albums?"),
  );
  await page.getByLabel("查找专辑").fill("Kind of Blue");
  await page.getByLabel("查找专辑").press("Enter");
  const response = await albumSearch;
  expect(response.status()).toBe(200);
  const matches = validate(
    "AlbumSearchResult",
    await response.json(),
  ) as AlbumSearchResult;
  expect(matches.items.length).toBeGreaterThan(0);
  const album = matches.items[0]!;
  const name = `${album.identity.title} — ${album.identity.artist_credit}`;
  await page
    .getByRole("button", { name: `添加${name}`, exact: true })
    .first()
    .click();
  await expect(
    page.getByRole("button", { name: `${name}特别喜欢`, exact: true }),
  ).toHaveAttribute("aria-pressed", "false");
  const resolved = await context.request.get(
    `/v1/catalog/albums/${album.mb_release_group_id}`,
  );
  expect(resolved.status()).toBe(200);
  validate("ResolvedAlbum", await resolved.json());
  const cookies = await context.cookies();
  expect(
    cookies.find((cookie) => cookie.name === "auralis_session")!.httpOnly,
  ).toBe(true);
  const state = await context.request.get("/v1/current-state");
  expect(state.status()).toBe(200);
  expect(state.headers()["set-cookie"]).toBeUndefined();
  // PostgreSQL stores microseconds; Java may serialize the initial Instant at nanosecond precision.
  expect(Date.parse((await state.json()).expires_at)).toBe(
    Date.parse(initial.state.expires_at),
  );
  await page.screenshot({
    path: "test-results/java-catalog.png",
    fullPage: true,
  });
  // Draft selections are deliberately unsaved until submission.
  const restoredResponse = page.waitForResponse((response) =>
    response.url().endsWith("/v1/bootstrap"),
  );
  await page.reload();
  const restored = (await (await restoredResponse).json()) as BootstrapState;
  expect(Date.parse(restored.state.expires_at)).toBe(
    Date.parse(initial.state.expires_at),
  );
  expect(
    (await context.cookies()).find(
      (cookie) => cookie.name === "auralis_session",
    )!.value,
  ).toBe(cookies.find((cookie) => cookie.name === "auralis_session")!.value);
  await page.getByRole("button", { name: /^专辑/ }).click();
  await page.getByLabel("查找专辑").fill("zzzznotfoundfe001zzzz");
  await page.getByLabel("查找专辑").press("Enter");
  await expect(page.getByText("没有匹配结果。")).toBeVisible();
  expect(
    await page.evaluate(() => [localStorage.length, sessionStorage.length]),
  ).toEqual([0, 0]);
  expect(errors).toEqual([]);
});
