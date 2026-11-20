# LearnLoop 前端

Next.js App Router 前端，负责资料阅读、题库、复习、笔记和小栗老师界面。PDF 通过 PDF.js 在页面内显示，API 请求由 Next.js 同源代理到 Java。

按照 [仓库根目录的启动说明](../README.md#本地启动)安装依赖并启动。修改 Java 地址时，请在构建 Next.js 前设置 `LEARNLOOP_BACKEND_URL`。

运行 `npm run test:unit` 执行前端单元测试。运行 `npm run test:proxy` 检查代理契约。启动前端后运行 `npm run test:e2e`，使用模拟 API 验证浏览器交互。
