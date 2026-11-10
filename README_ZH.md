# Auralis

[English](README.md)

Auralis帮助你通过风格偏好、喜欢的专辑或自然语言描述发现专辑。三类输入至少提供一种，即可查看推荐与短评，并按需打开详细乐评。

## 当前状态

Java推荐API与Python生产流程已连通，并通过隔离PostgreSQL及合成模型／页面适配器验证。浏览器推荐交互也已通过真实Java验证；已有真实供应商请求及本机容量测量；广泛推荐质量和完整网站体验仍待验收，产品尚未公开部署。

Java在线底座使用`mvn -f apps/backend/pom.xml verify`构建，再运行`java -jar apps/backend/target/auralis-core-database-0.1.0-SNAPSHOT-online.jar`；环境变量须显式提供数据库地址与运行凭据、产品同源地址、Python loopback地址、服务凭据及当前内部协议修订。使用已由离线CLI迁移的PostgreSQL 18及无DDL权限的运行账号，默认监听127.0.0.1:8080。健康探针、匿名会话与推荐API已实现。推荐还需显式提供`AURALIS_RECOMMENDATION_CONFIG`并设置`AURALIS_DISPATCH_ENABLED=true`，批准工件、模型绑定与硬预算配置须一致。

启动前须由离线迁移器应用V1～V15；Java与Python使用同版0.32协议配置。

## Linux部署

[容器部署说明](apps/runtime/deployment.txt)介绍单机Compose服务及需要独立准备的数据和密钥。[CI/CD工作流](.github/workflows/ci.yml)检查PR，并在main更新通过检查后经SSH部署，包含数据库备份、迁移和就绪检查。音乐数据和凭据不打入镜像。

## 前端开发

使用 Node.js 22.12 或更新版本，在仓库根执行：

```powershell
npm --prefix apps/frontend ci
npm --prefix apps/frontend run build
npm --prefix apps/frontend test
npm --prefix apps/frontend run check
npm --prefix apps/frontend run test:browser
npm --prefix apps/frontend run dev:fixture
```

打开 `http://127.0.0.1:4173`。显式样例模式展示合成数据提示，不调用模型。接 Java 时使用 `npm --prefix apps/frontend run dev`，将 `AURALIS_JAVA_ORIGIN` 指向 Java 回环地址，并把 Java 的 `AURALIS_PRODUCT_ORIGIN` 设为前端地址；本地 HTTP 另需显式设置 `AURALIS_ALLOW_INSECURE_COOKIE=true`。Vite提供开发模块或构建预览，并转发 `/v1/`；生产环境需自行配置同源API路由。搜索依赖 Java 已核验的完整目录索引。显式配置推荐策略、调度器和批准数据后，推荐命令可用。

路由与校验器随产品提交，普通构建只需产品仓库。`npm --prefix apps/frontend run generate` 还需接入同版私有 OpenAPI 工作区，从共享 Schema 再生成，禁止手改生成物。浏览器测试使用已安装 Chrome 及独立端口4187。真实 Java 目录浏览器测试需设置 `AURALIS_JAVA_ORIGIN` 后执行 `npm --prefix apps/frontend run test:java`；Java须允许前端 Origin `http://127.0.0.1:4188`。

## 匿名会话

通过`AURALIS_SUBJECT_SECRET`提供独立的安全随机签名密钥，至少43位URL-safe字符；普通重启保持一致，与数据库备份分开保存。HTTPS使用Secure／HttpOnly／SameSite=Lax Cookie；本地loopback HTTP必须显式设置`AURALIS_ALLOW_INSECURE_COOKIE=true`。内部协议配置须匹配打包的Schema（当前0.32）。

`POST /v1/bootstrap`只接受配置同源的空JSON对象，返回CSRF令牌和可恢复状态。`GET /v1/current-state`及`GET /v1/discovery-sessions/{session_id}`核验凭证归属，读取不续期。成功的主动操作将当前过程保留固定30天；过期凭证通过bootstrap自动得到新的空白身份。应用生命周期端口支持修改需求、已听过与删除，推荐命令已用合成供应商与Python生产流程联调。

V8增加撤销收据及publication拥有的恢复投影。删除先提交屏障，再有界分批清理；仍占槽的执行等待调度器确认物理释放。恢复旧数据库前先隔离服务，并重放备份之后的撤销记录，再开放访问。若无法补齐备份之后的删除记录，保持隔离并轮换独立签名密钥，使所有旧凭证失效、用户从空白开始，旧凭证代次进入分批清理。普通重启仍保持原密钥；完整备份恢复仍是独立部署职责。

## 持久化开发

后端包含V7在线状态迁移，以及会话、执行、预算、证据、发布、偏好和听众计数各模块拥有的存储端口。先用离线迁移身份应用迁移，再启动在线应用；运行身份只校验结构并使用显式授予的表权限。产品API与短有界调度已在此底座上实现，启动前须核验完整当前迁移链。不要修改已应用迁移，也不要通过删表把V7数据库回退到旧版本。

`mvn -f apps/backend/pom.xml verify`执行格式、单元／契约和数据库集成测试。数据库测试须显式提供`AURALIS_TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:<端口>/auralis_core_verify`及`AURALIS_TEST_POSTGRES_ADMIN_PASSWORD`，并使用**新建的一次性PostgreSQL 18实例**。测试会创建合成数据库与角色、验证迁移校验和拒绝；每轮结束销毁该实例。缺少变量时数据库测试会跳过。独立worktree可用`AURALIS_RECALL_PYTHON`指定已有Python智能服务环境。测试不调用付费模型。

## Agent 开发

在 Python 3.10+ 环境安装应用清单中的依赖后，从仓库根运行。演示使用合成模型与网页、真实本地 A-V2 代码，不产生付费请求。

```powershell
python -m pip install -e apps/intelligence
python -m auralis_intelligence.agents demo --scenario mixed
python -m auralis_intelligence.agents demo --scenario mixed --background --r 3 --k 2 --n 1
python -m auralis_intelligence.agents demo --scenario genre --r 5 --k 4 --n 2 --include-calls
```

增加 `--background` 可运行后台补充并输出关闭报告。服务支持长评提优先级、抢占、显式重新生成与续接。

输出包括首批构成、发布数量、分段计时，以及可选的不含正文或密钥的调用记录。合成计时和零价替身不能证明真实质量、速度或费用。其他输入场景为 `album`、`text`、`no-positive`，最后一种交回 Java 固定名单分支。

启动 loopback HTTP 服务时，按[配置样例](apps/intelligence/agent-service.example.json)显式填写获准资料路径、凭据环境变量名和已核实的[模型绑定](apps/intelligence/autonomous-bindings.example.json)，再运行 `python -m auralis_intelligence.agents serve --config <config.json>`；它需要同版 Java 控制服务。配置样例默认关闭真实调用。短长评独立并行，首批等待全部实际短评结论，长评可随后交付；不会以8秒截断来缩小首批。

## 召回开发

A-V2 是产品召回基线。Python 调用方可装载快照并复用召回对象，后端可用本地 JSON 入口联调。下面以 PowerShell 运行合成样例，权重参数仅为示例。

```powershell
$env:PYTHONPATH = 'apps/intelligence/src'
python -m auralis_intelligence.recall --snapshot apps/intelligence/tests/fixtures/synthetic-snapshot.json --request apps/intelligence/tests/fixtures/request.json --special-weight 2 --focus-weight 2
```

请使用 Python 3.10+ 和应用清单中的依赖。调用方显式指定已获准的本地快照，入口返回候选作品 ID 与快照标识。Python执行服务已接通此阶段，Java产品链路已接通，完整页面体验仍待验收。

本分支另提供 C-V0 对照。可调用同一端口的 `load_c_v0`，或在本地命令增加
`--c-v0-model <model.npz>` 并传入匹配快照。训练权重由调用方本地提供，默认仍使用 A-V2。

## 当前页面流程

- 通过三个可展开入口填写文字、风格偏好或喜欢的专辑，选择熟悉／拓展／惊喜模式。传播度滑杆对应1～500位样本独立听众，初始值为50。
- 提交后显示真实排队／等待状态，再逐张浏览专辑；长评在原位展开，可像播放轨道一样拖动阅读进度，不播放音频。
- “我喜欢／一般般”只保留在本页面，不写入后端偏好。“听过了”写入服务端，但专辑继续留在当前页面；最近一次单卡操作可以撤销。
- 组末可以继续要推荐或回到预填首页。回首页本身不提交新请求；未提交草稿只保存在页面内存，不写浏览器存储。
- 页面背景是响应鼠标的等高线地形，房间色由封套配色推导。真实封面查询尚未接通，当前仍使用占位封套。

## 完整目录搜索

Java目录接口通过本地只读Lucene索引搜索完整MusicBrainz作品组快照，支持专辑、艺人署名和别名；公开搜索结果排除Single，过滤后补齐分页，并利用已准入听众统计优先展示常见专辑，统计缺失不排除作品；不会把召回核心子集当成完整目录。启动时用`AURALIS_CATALOG_SEARCH_INDEX`和`AURALIS_CATALOG_SEARCH_MANIFEST_SHA256`提供离线核验索引及对应候选manifest摘要。未配置时目录返回503；错误索引拒绝启动。

匿名bootstrap后可读取`/v1/catalog/albums?query=...`、`/v1/catalog/albums/{mb_release_group_id}`和`/v1/catalog/genres`；GET不续期，未知作品与无匹配分别处理。库外作品可选择，只有已核验可用信息进入召回；封面暂用占位。索引由离线`io.auralis.catalog.adapters.CatalogIndexBuild`从完整候选导入，输入目录、全新输出目录和manifest摘要必须显式提供。更新在维护窗口换路径及摘要后重启，失败恢复旧完整索引；没有在线下载或远端回退。目录与推荐API已实现，广泛推荐质量和完整网站验收仍待完成。

### 本地固定策展推荐

无正向信号时使用固定10张有序候选池，短评和长评独立保存。Java显式配置 `AURALIS_CURATED_CONTENT_DIRECTORY`，Windows运行包则设置 `curated_content_directory`。该分支跳过召回、在线乐评生成，并在用户未明确填写听众门槛时免默认门槛，保留目录身份、本轮限制与去重。内容只在本地保存，不随Git分发。将准备好的 `auralis-curated-v1.json` 用后端运行classpath中的 `io.auralis.discovery.adapters.CuratedContentFiles <输入JSON> <新目录>` 导入。
