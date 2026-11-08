# Auralis

[English](README.md)

Auralis帮助你通过风格偏好、喜欢的专辑或自然语言描述发现专辑。三类输入至少提供一种，即可查看推荐与短评，并按需打开详细乐评。

## 当前状态

Python Agent 已有独立演示与内部执行服务。Java 产品接入与浏览器应用仍待完成，目前没有完整网站发布版本。

Java在线底座可用上述Maven命令构建和jar命令启动；环境变量须显式提供数据库地址与运行凭据、产品同源地址、Python loopback地址、服务凭据及当前内部协议修订。使用已由离线CLI迁移的PostgreSQL 18及无DDL权限的运行账号，默认监听127.0.0.1:8080。存活与就绪探针已实现；产品会话和推荐API仍待后续任务。

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

启动 loopback HTTP 服务时，按[配置样例](apps/intelligence/agent-service.example.json)显式填写获准资料路径、凭据环境变量名和已核实的[模型绑定](apps/intelligence/provider-bindings.example.json)，再运行 `python -m auralis_intelligence.agents serve --config <config.json>`；它需要同版 Java 控制服务。配置样例默认关闭真实调用。短长评独立并行，首批等待全部实际短评结论，长评可随后交付；不会以8秒截断来缩小首批。

## 召回开发

A-V2 是产品召回基线。Python 调用方可装载快照并复用召回对象，后端可用本地 JSON 入口联调。下面以 PowerShell 运行合成样例，权重参数仅为示例。

```powershell
$env:PYTHONPATH = 'apps/intelligence/src'
python -m auralis_intelligence.recall --snapshot apps/intelligence/tests/fixtures/synthetic-snapshot.json --request apps/intelligence/tests/fixtures/request.json --special-weight 2 --focus-weight 2
```

请使用 Python 3.10+ 和应用清单中的依赖。调用方显式指定已获准的本地快照，入口返回候选作品 ID 与快照标识。Python执行服务已接通此阶段，Java产品接入仍待完成。

本分支另提供 C-V0 对照。可调用同一端口的 `load_c_v0`，或在本地命令增加
`--c-v0-model <model.npz>` 并传入匹配快照。训练权重由调用方本地提供，默认仍使用 A-V2。

## 计划中的体验

- 无需登录或保存个人持续偏好，提供风格、喜欢的专辑或本次文字需求。
- 选择熟悉、拓展或惊喜，查看封面、艺人、主要风格、推出年份和短评，按需打开详细乐评。
- “已听过”与“来张新的”分别提供单卡补位；可以整组换批，或在当前页编辑预填需求。空位补充中保留其他卡片，完成后自动填入。
- 结果不足按实际数量展示；零结果或失败均提供重新输入按钮，保留原输入供修改。
