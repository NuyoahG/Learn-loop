# LearnLoop Java 后端

[本地安装步骤](../README.md#本地启动)说明如何初始化数据库、生成本机凭据和启动服务。

- [空库初始化](db/init.sql)包含当前版本的新装表结构。历史增量脚本位于 [db/manual](src/main/resources/db/manual)，用于了解旧版本升级顺序。
- [应用配置](src/main/resources/application.properties)包含端口、数据库、原件目录和功能开关。
- 开发命令为 `.\mvnw.cmd verify`。带 `LEARNLOOP_RUN_*_DB_TESTS` 开关的数据库集成测试使用隔离数据库。
