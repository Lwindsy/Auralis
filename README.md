# Auralis

Auralis aims to help you discover albums through genre preferences, favorite albums, or a description of what you want to hear. Provide at least one type of input, then explore short reviews and open detailed reviews when interested.

Auralis帮助你通过风格偏好、喜欢的专辑或自然语言描述发现专辑。三类输入至少提供一种，即可查看推荐与短评，并按需打开详细乐评。

## Availability / 当前状态

Frontend functions were provisionally frozen on November 5, 2026. The product has no runnable recommendation application or installable release yet. Visual design and backend integration are still pending.

前端功能已于2026-11-05暂时冻结，目前仍没有可运行的推荐应用或可安装版本。最终视觉设计和后端接入尚未完成。

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
snapshot binding for integration. The online execution service is still pending.

请使用 Python 3.10+ 和应用清单中的依赖。调用方显式指定已获准的本地快照，入口返回候选作品 ID 与快照标识。在线执行服务仍待接入。

This branch also supports the C-V0 comparison. Use `load_c_v0` from the same
Python port, or add `--c-v0-model <model.npz>` to the local command with the
matching snapshot. The trained artifact is supplied locally. A-V2 remains the default.

本分支另提供 C-V0 对照。可调用同一端口的 `load_c_v0`，或在本地命令增加
`--c-v0-model <model.npz>` 并传入匹配快照。训练权重由调用方本地提供，默认仍使用 A-V2。

## Planned experience / 计划中的体验

- Provide genre preferences, favorite albums, or natural-language requests without logging in or saving a persistent personal taste profile.
- Choose familiar, expand, or surprise exploration, then view album artwork, artist, main genres, release year and a short review. Detailed reviews open on request.
- Mark an album as heard for an immediate replacement, or like/dislike it and choose a replacement afterward. Replace the whole displayed group or edit your prefilled request in place.
- Receive the available number of results. With no results or an error, return to your prefilled input using the provided button.

- 无需登录或保存个人持续偏好，提供风格、喜欢的专辑或本次文字需求。
- 选择熟悉、拓展或惊喜，查看封面、艺人、主要风格、推出年份和短评，按需打开详细乐评。
- 已听过立即补位；喜欢／不喜欢后可选择来张新的。可以整组换批，或在当前页编辑预填需求。
- 结果不足按实际数量展示；零结果或失败均提供重新输入按钮，保留原输入供修改。
