# LearnLoop

LearnLoop 是一个本地学习工具。上传自己的学习资料后，可以读原文、做笔记、按考点练习和复习，也可以请小栗老师结合自己的学习记录讲解。

| 功能 | 在哪里使用 |
| --- | --- |
| PDF 阅读、目录、缩放、高亮和笔记 | 资料阅读 |
| 对选中的原文提问，保存高亮和回答 | PDF 阅读的 AI 问答侧栏 |
| 按资料范围提炼考点并出题 | 个人中心 → 学习工作台 |
| 做题、回看讲解和历史答案 | 题目中心与练习、答题记录 |
| 按复习计划回顾知识点 | 今日复习 |
| 查看辅导记录、修改反馈和管理提醒 | 小栗老师 |

## 本地启动

下面以 Windows 为例。需要 MySQL 8.4、JDK 21、Python 3.13、Node.js 24、npm 和 PowerShell 7。命令中的路径以这个仓库的根目录为起点。

### 1. 创建新数据库

在 **命令提示符（cmd.exe）** 中，从仓库根目录执行。脚本创建 `learnloop` 库和空表，适用于同名库尚不存在的实例。MySQL 客户端会提示输入管理员密码。

```cmd
mysql --no-defaults --host=127.0.0.1 --port=3306 --user=root --password --default-character-set=utf8mb4 < backend\db\init.sql
```

在 MySQL 管理员会话中为应用新建一个专用账号。MySQL 会现场生成并显示密码，请留存于本机安全位置。已有同名数据库或账号时，使用单独的 MySQL 实例完成首次安装。

```sql
CREATE USER 'learnloop_app'@'localhost' IDENTIFIED BY RANDOM PASSWORD;
GRANT SELECT, INSERT, UPDATE, DELETE ON learnloop.* TO 'learnloop_app'@'localhost';
```

### 2. 初始化本机凭据，启动 Python 服务

在 PowerShell 7 中运行。从仓库根目录执行：

```powershell
.\scripts\Initialize-LocalSecrets.ps1
Set-Location ai-service
py -3.13 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.lock
$env:LEARNLOOP_BKT_ENABLED = 'true'
$env:LEARNLOOP_GENERATION_ENABLED = 'true'
.\scripts\manage.ps1 start
```

### 3. 构建并启动 Java 服务

另开一个 PowerShell 窗口，从仓库根目录执行。提前将 `JAVA_HOME` 指向已安装的 JDK 21。

```powershell
$env:LEARNLOOP_DB_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'MySQL 应用账号密码' -AsSecureString)).Password
$env:LEARNLOOP_LEARNING_ENABLED = 'true'
$env:LEARNLOOP_GENERATION_ENABLED = 'true'
$env:LEARNLOOP_MODEL_SETTINGS_ENABLED = 'true'
Set-Location backend
.\build.cmd
.\start.cmd
```

### 4. 启动前端，注册自己的账号

再开一个终端，从仓库根目录执行：

```powershell
Set-Location frontend
npm ci
npm run build
npm run start
```

打开 [LearnLoop 注册页](http://127.0.0.1:3000/register)，创建自己的账号。可以先上传[示例学习资料](examples/间隔复习入门.md)，验证解析和阅读。在个人中心的「模型设置」输入自己的 DeepSeek API Key 后，可用出题、AI 讲解和小栗聊天。这些操作会按服务商规则计费。

## 配置和数据

- [配置样例](.env.example)列出环境变量。服务读取启动进程的环境变量，这个样例只用于查阅。
- 默认本地地址为前端 `127.0.0.1:3000`、Java `127.0.0.1:8080`、Python `127.0.0.1:8001`，MySQL 使用 `127.0.0.1:3306`。状态入口是 Java 的 `/actuator/health` 和 Python 的 `/health`。
- 资料原件默认保存在用户主目录下的 `.learnloop/materials`，可以用绝对路径覆盖。账号、答题、笔记、辅导记录和加密后的模型凭据保存在自己的 MySQL 库中。内部服务密钥和加密主密钥由本机脚本生成，保存在被 Git 忽略的 `data/private/`。
- 停止服务时，在前端终端按 Ctrl+C，在 `backend/` 运行 `.\stop.cmd`，在 `ai-service/` 用 PowerShell 7 运行 `.\scripts\manage.ps1 stop`。

## 开发与验证

- `frontend/`：Next.js、React、PDF.js。用 `npm run test:unit` 执行单测。启动前端后，可用 `npm run test:e2e` 运行模拟 API 的浏览器测试。
- `backend/`：Spring Boot、MyBatis、MySQL。用 `.\mvnw.cmd verify` 验证。需要数据库的集成测试默认跳过，应单独使用隔离测试库。
- `ai-service/`：FastAPI、资料解析、出题、复习和小栗老师。安装 `requirements-dev.lock` 后执行 `python -m pytest tests`。

## 许可证

LearnLoop 源码采用 GNU General Public License v3.0（`GPL-3.0-only`），全文见 [LICENSE](LICENSE)。通过依赖管理器安装的第三方依赖各自遵守其许可证。

随仓库附带的 Maven Wrapper 脚本保留其 [Apache 2.0 许可证](licenses/Apache-2.0.txt)和[版权声明](licenses/Maven-Wrapper-NOTICE.txt)。
