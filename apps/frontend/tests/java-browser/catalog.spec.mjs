import { test, expect } from "@playwright/test";
import { validate } from "../../src/api.js";

test("real Java bootstrap, genres, full catalog selection and read-only recovery", async ({
  page,
  context,
}) => {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  const bootstrap = page.waitForResponse((response) =>
    response.url().endsWith("/v1/bootstrap"),
  );
  await page.goto("/");
  const initial = await (await bootstrap).json();
  validate("BootstrapState", initial);
  await expect(
    page.getByRole("button", { name: "喜欢Rock", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "喜欢Rock", exact: true }).click();
  await page
    .getByLabel("Rock喜欢程度", { exact: true })
    .selectOption("special");
  await page.getByLabel("本次重点", { exact: true }).check();
  const albumSearch = page.waitForResponse((response) =>
    response.url().includes("/v1/catalog/albums?"),
  );
  await page.getByLabel("查找专辑").fill("Kind of Blue");
  await page.getByLabel("查找专辑").press("Enter");
  const response = await albumSearch;
  expect(response.status()).toBe(200);
  const matches = await response.json();
  validate("AlbumSearchResult", matches);
  expect(matches.items.length).toBeGreaterThan(0);
  const album = matches.items[0];
  await page
    .getByRole("button", {
      name: `添加${album.identity.title} — ${album.identity.artist_credit}`,
      exact: true,
    })
    .first()
    .click();
  await expect(
    page.getByLabel(
      `${album.identity.title} — ${album.identity.artist_credit}喜欢程度`,
      { exact: true },
    ),
  ).toHaveValue("normal");
  const resolved = await context.request.get(
    `/v1/catalog/albums/${album.mb_release_group_id}`,
  );
  expect(resolved.status()).toBe(200);
  validate("ResolvedAlbum", await resolved.json());
  const cookies = await context.cookies();
  expect(
    cookies.find((cookie) => cookie.name === "auralis_session").httpOnly,
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
  // Draft selections are deliberately unsaved until submission; BE-003 is not provided here.
  const restoredResponse = page.waitForResponse((response) =>
    response.url().endsWith("/v1/bootstrap"),
  );
  await page.reload();
  const restored = await (await restoredResponse).json();
  expect(Date.parse(restored.state.expires_at)).toBe(
    Date.parse(initial.state.expires_at),
  );
  expect(
    (await context.cookies()).find(
      (cookie) => cookie.name === "auralis_session",
    ).value,
  ).toBe(cookies.find((cookie) => cookie.name === "auralis_session").value);
  await page.getByLabel("查找专辑").fill("zzzznotfoundfe001zzzz");
  await page.getByLabel("查找专辑").press("Enter");
  await expect(page.getByText("没有匹配结果。")).toBeVisible();
  expect(
    await page.evaluate(() => [localStorage.length, sessionStorage.length]),
  ).toEqual([0, 0]);
  expect(errors).toEqual([]);
});
