import { expect, test, type APIRequestContext } from "@playwright/test";

/**
 * The sync run history and connector freshness, against the real stack (#29, FR8).
 *
 * The compose stack configures no source system, so a run is made the way an operator would make
 * one: by asking the GitHub connector to sync. With no org and no token it fails at once, without
 * reaching the network, and records a FAILED run with the reason - which is exactly the kind of run
 * this page exists to explain.
 *
 * The stale badge needs an enabled connector whose last success is older than its threshold, and
 * the stack enables none, so it is covered by the component tests (FreshnessBadge, ConnectorsView)
 * against a mocked API. What is asserted here is the freshness a never-succeeded connector shows.
 */
const CONNECTOR = "github";

type Run = { id: string; status: string | null };

/** Starts a run and waits for it to finish, retrying while another run of the connector is going. */
const finishedRun = async (request: APIRequestContext): Promise<Run> => {
  let runId: string | undefined;
  await expect(async () => {
    const response = await request.post(
      `/api/v1/connectors/${CONNECTOR}/sync?mode=full`,
    );
    expect(response.status()).toBe(202);
    runId = (await response.json()).syncRunId;
  }).toPass({ timeout: 30_000 });

  let run: Run = { id: runId!, status: "RUNNING" };
  await expect(async () => {
    run = await (await request.get(`/api/v1/sync-runs/${runId}`)).json();
    expect(run.status).not.toBe("RUNNING");
  }).toPass({ timeout: 30_000 });
  return run;
};

// One connector can run once at a time, so the tests that start runs take turns.
test.describe.configure({ mode: "serial" });

test.describe("sync runs", () => {
  test("the page is reachable from the navigation", async ({ page }) => {
    await page.goto("/");
    await page.getByRole("link", { name: "Sync runs", exact: true }).click();

    await expect(page).toHaveURL(/\/sync-runs$/);
    await expect(
      page.getByRole("heading", { name: "Sync runs" }),
    ).toBeVisible();
  });

  test("lists runs with status chips, and filtering by status narrows the table", async ({
    page,
    request,
  }) => {
    const run = await finishedRun(request);
    expect(run.status).toBe("FAILED");

    await page.goto("/sync-runs");
    const row = page.getByTestId(`run-${run.id}`);
    await expect(row).toBeVisible();
    await expect(row.getByTestId("status-chip")).toHaveText("FAILED");
    await expect(row).toContainText(CONNECTOR);
    await expect(row).toContainText("FULL");

    await page.getByLabel("Status").selectOption("SUCCESS");
    await expect(row).toHaveCount(0);
    for (const chip of await page.getByTestId("status-chip").all()) {
      await expect(chip).toHaveText("SUCCESS");
    }

    await page.getByLabel("Status").selectOption("FAILED");
    await expect(row).toBeVisible();
  });

  test("a row opens the run in a drawer, and Re-run starts a new run at the top", async ({
    page,
    request,
  }) => {
    const run = await finishedRun(request);

    await page.goto("/sync-runs");
    await page.getByLabel("Connector").selectOption(CONNECTOR);
    await page.getByTestId(`run-${run.id}`).click();

    const drawer = page.getByRole("dialog");
    await expect(drawer).toBeVisible();
    await expect(drawer).toContainText(run.id);
    await expect(drawer.getByTestId("run-error")).toContainText(
      "needs at least one org",
    );
    await expect(drawer.getByTestId("run-details")).toHaveText("{}");

    await drawer.getByRole("button", { name: "Re-run" }).click();
    const started = drawer.getByTestId("rerun-result");
    await expect(started).toContainText("Started run");
    const newId = ((await started.getAttribute("data-run-id")) ?? "").trim();
    expect(newId).not.toBe("");
    expect(newId).not.toBe(run.id);

    await drawer.getByRole("button", { name: "Close" }).click();
    await expect(drawer).toHaveCount(0);
    await expect(page.locator("tbody tr").first()).toHaveAttribute(
      "data-test",
      `run-${newId}`,
    );
  });

  test("the connectors screen shows freshness and the last run status", async ({
    page,
    request,
  }) => {
    await finishedRun(request);

    await page.goto("/connectors");

    // Never succeeded, so there is no age to show: "never" rather than a made-up one, and not stale,
    // because a disabled connector is never stale.
    await expect(page.getByTestId(`freshness-${CONNECTOR}`)).toContainText(
      "never",
    );
    await expect(page.getByTestId(`stale-${CONNECTOR}`)).toHaveCount(0);
    await expect(page.getByTestId(`last-run-${CONNECTOR}`)).toContainText(
      "FAILED",
    );
  });
});
