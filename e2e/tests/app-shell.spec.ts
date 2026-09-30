import { expect, test } from "./fixtures";

/**
 * The application shell (#6): navigation built from the ontology rather than hard-coded, a footer
 * saying which ontology and build are serving, and a page for a route that does not exist.
 *
 * The nav is compared with what `/api/v1/ontology` serves at the time of the test, so a type added to
 * the registry is covered on the day it is added, without editing this spec.
 */
test.describe("application shell", () => {
  test("the navigation offers every node type the ontology declares, except its meta types", async ({
    page,
    request,
  }) => {
    const ontology = await (await request.get("/api/v1/ontology")).json();
    const browsable: string[] = ontology.nodeTypes
      .filter((t: { meta: boolean }) => !t.meta)
      .map((t: { name: string }) => t.name);
    const meta: string[] = ontology.nodeTypes
      .filter((t: { meta: boolean }) => t.meta)
      .map((t: { name: string }) => t.name);
    expect(meta).toEqual(expect.arrayContaining(["Ontology", "SyncRun"]));

    await page.goto("/");
    const nav = page.getByRole("navigation", { name: "Node types" });

    for (const type of browsable) {
      await expect(
        nav.getByRole("link", { name: type, exact: true }),
      ).toHaveAttribute("href", `/nodes/${type}`);
    }
    for (const type of meta) {
      await expect(
        nav.getByRole("link", { name: type, exact: true }),
      ).toHaveCount(0);
    }
  });

  test("the footer names the ontology version and the build", async ({
    page,
    request,
  }) => {
    const ontology = await (await request.get("/api/v1/ontology")).json();
    const info = await (await request.get("/actuator/info")).json();

    await page.goto("/");
    const footer = page.getByRole("contentinfo");

    await expect(footer).toContainText(`ontology v${ontology.version}`);
    const build =
      info.deployment.commit === "unknown"
        ? "unknown"
        : info.deployment.commit.slice(0, 7);
    await expect(footer).toContainText(`build ${build}`);
  });

  test("a route that does not exist says so and leads back home", async ({
    page,
  }) => {
    await page.goto("/does-not-exist");

    await expect(
      page.getByRole("heading", { name: "Page not found" }),
    ).toBeVisible();
    await page.getByRole("link", { name: "Back to the graph" }).click();
    await expect(page).toHaveURL(/\/nodes\/Repository$/);
  });
});
