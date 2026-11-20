import { defineConfig } from "@playwright/test";

export default defineConfig({
    testDir: "./tests/e2e",
    fullyParallel: true,
    forbidOnly: !!process.env.CI,
    retries: process.env.CI ? 1 : 0,
    reporter: [["list"]],
    use: {
        baseURL: "http://127.0.0.1:3000",
        channel: process.env.LEARNLOOP_E2E_BROWSER ?? "chrome",
        headless: true,
        trace: "retain-on-failure",
        screenshot: "only-on-failure"
    }
});
