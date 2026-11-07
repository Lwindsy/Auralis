# Auralis

Auralis aims to help you discover albums through genre preferences, favorite albums, or a description of what you want to hear. Provide at least one type of input, then explore short reviews and open detailed reviews when interested.

Auralis帮助你通过风格偏好、喜欢的专辑或自然语言描述发现专辑。三类输入至少提供一种，即可查看推荐与短评，并按需打开详细乐评。

## Availability / 当前状态

A standalone Python agent demo and an internal execution service are available. Java product integration and the browser application are still pending; there is no complete website release yet.

Python Agent 已有独立演示与内部执行服务。Java 产品接入与浏览器应用仍待完成，目前没有完整网站发布版本。

## Agent development / Agent 开发

Use Python 3.10+ with the dependencies in [pyproject.toml](apps/intelligence/pyproject.toml). From the repository root, run the complete first-batch flow with synthetic models and pages and the local A-V2 implementation. This performs no paid requests.

在 Python 3.10+ 环境安装应用清单中的依赖后，从仓库根运行。演示使用合成模型与网页、真实本地 A-V2 代码，不产生付费请求。

```powershell
python -m pip install -e apps/intelligence
python -m auralis_intelligence.agents demo --scenario mixed
python -m auralis_intelligence.agents demo --scenario mixed --background --r 3 --k 2 --n 1
python -m auralis_intelligence.agents demo --scenario genre --r 5 --k 4 --n 2 --include-calls
```

The optional `--background` mode also runs replenishment and closes with a round report. The service supports prioritized long reviews, preemption, explicit regeneration and continuation.

增加 `--background` 可运行后台补充并输出关闭报告。服务支持长评提优先级、抢占、显式重新生成与续接。

The output reports first-batch composition, publication counts, stage timing and optional safe call records. Synthetic timing and zero-price fixtures do not measure real model quality, speed or cost. The other input scenarios are `album`, `text` and `no-positive`; the last hands control back to Java's curated-list branch.

输出包括首批构成、发布数量、分段计时，以及可选的不含正文或密钥的调用记录。合成计时和零价替身不能证明真实质量、速度或费用。其他输入场景为 `album`、`text`、`no-positive`，最后一种交回 Java 固定名单分支。

For the loopback HTTP service, provide explicit local paths and credential environment-variable names using [agent-service.example.json](apps/intelligence/agent-service.example.json) and reviewed [model bindings](apps/intelligence/provider-bindings.example.json), then run `python -m auralis_intelligence.agents serve --config <config.json>`. A matching Java control service is required. The sample bindings keep live calls disabled.

启动 loopback HTTP 服务时，按配置样例显式填写获准资料路径、凭据环境变量名和已核实的模型绑定，再运行上述 `serve` 命令；它需要同版 Java 控制服务。配置样例默认关闭真实调用。短长评独立并行，首批等待全部实际短评结论，长评可随后交付；不会以8秒截断来缩小首批。

## Recall development / 召回开发

A-V2 is the product recall baseline. Python callers use the typed port in
[`auralis_intelligence.recall`](apps/intelligence/src/auralis_intelligence/recall/__init__.py).
Backend developers can exercise the local JSON adapter using the same
[request](apps/intelligence/src/auralis_intelligence/recall/request-v1.schema.json) and
[result](apps/intelligence/src/auralis_intelligence/recall/result-v1.schema.json) schemas.

A-V2 是产品召回基线。Python 调用方可装载快照并复用召回对象，后端可用本地 JSON 入口联调。下面以 PowerShell 运行合成样例，权重参数仅为示例。

```powershell
$env:PYTHONPATH = 'apps/intelligence/src'
python -m auralis_intelligence.recall --snapshot apps/intelligence/tests/fixtures/synthetic-snapshot.json --request apps/intelligence/tests/fixtures/request.json --special-weight 2 --focus-weight 2
```

Use Python 3.10+ and the dependencies in [pyproject.toml](apps/intelligence/pyproject.toml).
Pass a caller-authorized local snapshot explicitly. The adapter emits candidate IDs and
snapshot binding for integration. The Python execution service includes this stage; Java product integration is pending.

请使用 Python 3.10+ 和应用清单中的依赖。调用方显式指定已获准的本地快照，入口返回候选作品 ID 与快照标识。Python执行服务已接通此阶段，Java产品接入仍待完成。

This branch also supports the C-V0 comparison. Use `load_c_v0` from the same
Python port, or add `--c-v0-model <model.npz>` to the local command with the
matching snapshot. The trained artifact is supplied locally. A-V2 remains the default.

本分支另提供 C-V0 对照。可调用同一端口的 `load_c_v0`，或在本地命令增加
`--c-v0-model <model.npz>` 并传入匹配快照。训练权重由调用方本地提供，默认仍使用 A-V2。

## Planned experience / 计划中的体验

- Provide genre preferences, favorite albums, or natural-language requests without logging in or saving a persistent personal taste profile.
- Choose familiar, expand, or surprise exploration, then view album artwork, artist, main genres, release year and a short review. Detailed reviews open on request.
- Mark an album as heard, or request a new album with the separate replacement button. Replace the whole group or edit the prefilled request in place. A replenishing empty slot fills automatically when ready.
- Receive the available number of results. With no results or an error, return to your prefilled input using the provided button.

- 无需登录或保存个人持续偏好，提供风格、喜欢的专辑或本次文字需求。
- 选择熟悉、拓展或惊喜，查看封面、艺人、主要风格、推出年份和短评，按需打开详细乐评。
- “已听过”与“来张新的”分别提供单卡补位；可以整组换批，或在当前页编辑预填需求。空位补充中保留其他卡片，完成后自动填入。
- 结果不足按实际数量展示；零结果或失败均提供重新输入按钮，保留原输入供修改。
