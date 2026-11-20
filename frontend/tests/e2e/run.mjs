import { spawn, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

// Playwright 的 webServer 在 Windows 上收不掉 Next 进程，会让测试结束后一直挂住。
// 这里自己启动、自己结束，测试进程只负责跑测试。
const frontendDir = fileURLToPath(new URL("../../", import.meta.url));
const nextBin = path.join(frontendDir, "node_modules", "next", "dist", "bin", "next");
const playwrightBin = path.join(frontendDir, "node_modules", "@playwright", "test", "cli.js");

function run(args) {
    return new Promise(resolve => {
        const child = spawn(process.execPath, args, { cwd: frontendDir, stdio: "inherit" });
        child.on("error", () => resolve(1));
        child.on("exit", code => resolve(code ?? 1));
    });
}

async function waitForServer() {
    for (let attempt = 0; attempt < 80; attempt++) {
        try {
            const response = await fetch("http://127.0.0.1:3000/login");
            if (response.ok) return;
        } catch { /* 还没启动。 */ }
        await new Promise(resolve => setTimeout(resolve, 250));
    }
    throw new Error("Next 服务未能在 127.0.0.1:3000 启动；请先释放端口。");
}

const buildCode = await run([nextBin, "build"]);
if (buildCode !== 0) process.exit(buildCode);

const server = spawn(process.execPath, [nextBin, "start", "-H", "127.0.0.1", "-p", "3000"], {
    cwd: frontendDir,
    stdio: "inherit"
});

let stopping = false;
function stopServer() {
    if (stopping) return;
    stopping = true;
    if (process.platform === "win32") {
        spawnSync("taskkill", ["/pid", String(server.pid), "/T", "/F"], { stdio: "ignore" });
    } else {
        server.kill("SIGTERM");
    }
}

process.on("SIGINT", () => {
    stopServer();
    process.exit(130);
});

try {
    await waitForServer();
    const testCode = await run([playwrightBin, "test", ...process.argv.slice(2)]);
    stopServer();
    process.exit(testCode);
} catch (error) {
    console.error(error.message);
    stopServer();
    process.exit(1);
}
