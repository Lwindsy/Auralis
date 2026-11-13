# Auralis

[English](README.md)

Auralis 帮你找到下一张想听的专辑。可以从一张喜欢的唱片、几个常听的风格出发，也可以直接说今天想听什么。比如：“深夜想听点安静的，带一点爵士就好。”

**[打开 Auralis](https://auralis-94-250-202-196.sslip.io/)** · 无需注册

## 找一张专辑

1. 添加喜欢的专辑，选几个喜欢或想避开的风格，或者写一句你想听什么。任选一种就能开始。
2. 选**熟悉**，从接近你口味的音乐找起；选**拓展**，听听相邻风格；想走得更远一点，就选**惊喜**。
3. 专辑会陆续出现。先看几句短评，感兴趣就打开长评，读读这张专辑听起来怎样。已经听过的专辑可以点**听过了**，系统会尝试补一张。

页面左上角可以切换中英文。关掉页面后，用同一个浏览器回来还能继续看。Auralis 会把提交的偏好和推荐记录保留到最后一次主动操作后 30 天。页面底部的隐私详情说明了保存哪些信息，以及何时可能由 OpenAI 处理相关输入。

## 在本地试用界面

使用 Node.js 22.12 或更新版本，在仓库根目录运行以下命令。

```sh
npm --prefix apps/frontend ci
npm --prefix apps/frontend run dev:fixture
```

打开 [http://127.0.0.1:4173](http://127.0.0.1:4173)，用样例专辑体验页面。样例模式无需数据库或 API 密钥，也不会调用模型。

## 参与开发

- [前端](apps/frontend/package.json)使用 React 和 TypeScript，负责专辑搜索、浏览和乐评界面。运行 `npm --prefix apps/frontend test` 可执行前端单元测试。
- [后端](apps/backend/pom.xml)使用 Java 21 和 Spring Boot，提供目录查询，管理会话和推荐。
- [推荐服务](apps/intelligence/pyproject.toml)使用 Python 查找候选专辑、核对资料并生成乐评。

运行完整服务需要目录索引、配套音乐数据、PostgreSQL 和模型凭据。公开仓库提供应用代码和可独立运行的样例界面。所需文件、配置和数据库迁移步骤见 [Linux 部署说明](apps/runtime/deployment.txt)或 [Windows 运行说明](apps/runtime/usage.txt)。

## 许可证

[MIT](LICENSE)
