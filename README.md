# Server Leaderboard

Minecraft 1.21.7 Fabric 服务端模组：自动统计玩家数据并生成排行榜。
纯服务端实现，玩家客户端无需安装任何模组。

## 功能

- **自动生成排行榜**：服务器启动 30 秒后首次统计，之后每小时刷新一次，在服务器根目录输出 `leaderboard.html` 网页和 `leaderboard.json` 结构化数据；统计在后台线程异步执行，不阻塞游戏主线程
- **游戏内可视化界面**：`/leaderboard` 弹出箱子 GUI，含 排行榜 / 通用 / 物品 / 生物 / 食物与饮品使用排行 五个分类，物品与生物名称支持中文翻译，玩家头颅显示真实皮肤
- **排除假人**：自动排除 `bot_` 前缀的 Carpet 假人（前缀/后缀特征可用 `/leaderboard screen` 自定义），支持白名单/黑名单强制包含/排除；无法解析名字的孤儿 stats 文件自动跳过并提示清理
- **四种显示模式**：精简 / 普通 / 全部 / 自定义（自定义模式通过 `custom_display.txt` 逐项控制）；综合得分只统计当前模式实际显示的分类与统计项（精简模式只算 9 项核心数据，普通模式物品/生物每类只算前 36 项）
- **个人侧边计分板**：玩家可用 `/leaderboard scoreboard on` 开关显示自己的 9 项核心数据，OP 可用 `/leaderboard allowscoreboard false` 全局禁止；默认跟随排行榜数据更新，可用 `/leaderboard scoreboard refresh interval` 设置独立的实时刷新间隔；`scoreboard.json` 手动编辑后 5 秒内自动生效
- **可调刷新间隔**：`/leaderboard refresh interval 30s` 支持 t/s/m/h 单位，0 关闭自动刷新；`/leaderboard refresh broadcast false` 可关闭刷新广播
- **历史快照归档**：每次生成后把 `leaderboard.json` 归档到 `leaderboard/history/`，默认保留 30 份，可用 `/leaderboard history` 调整；JSON 内含玩家 UUID 与全服 9 项核心数据总和

## 假人筛除策略

按优先级从高到低依次判定：

1. **白名单**（最高优先级）：在名单中的玩家永远计入排行榜，无视其他所有规则
2. **黑名单**：在名单中的玩家永远排除
3. **名称特征筛除**：名字匹配配置的前缀或后缀即排除，默认前缀 `bot_`，不区分大小写，可用 `/leaderboard screen` 系列指令自定义
4. **Carpet 假人类检测**：生成排行榜时检查在线玩家的实体类名，含 `FakePlayer` 的自动排除，即使名字不带筛除特征；仅对当时在线的假人生效，白名单可豁免

另有一条兜底规则：名字完全无法解析的 UUID（孤儿统计文件）直接跳过不计入，并在服务端日志中提示清理。

## 指令

| 指令 | 权限 | 说明 |
|------|------|------|
| `/leaderboard` | 所有人 | 打开排行榜 GUI（控制台执行则输出文字版） |
| `/leaderboard refresh` | OP | 立即重新生成 |
| `/leaderboard refresh interval [数字[t\|s\|m\|h]]` | OP | 设置自动刷新间隔，设为 0 时关闭；无参数时查看当前间隔 |
| `/leaderboard refresh broadcast [true\|false]` | OP | 开关自动刷新的聊天提示；无参数时查看当前开关 |
| `/leaderboard mode [compact\|normal\|full\|custom]` | OP | 切换显示模式；无参数时查看当前模式 |
| `/leaderboard player list` / `list all` | OP | 查看排行榜包含的玩家 / 全部玩家 |
| `/leaderboard player whitelist add\|remove <玩家>` | OP | 管理白名单（强制包含；加入时自动移出黑名单） |
| `/leaderboard player blacklist add\|remove <玩家>` | OP | 管理黑名单（强制排除；加入时自动移出白名单） |
| `/leaderboard screen add prefix\|suffix <特征>` | OP | 添加名称筛除前缀/后缀（默认前缀 bot_，可多次添加） |
| `/leaderboard screen remove prefix\|suffix <特征>` | OP | 按类型移除名称筛除特征 |
| `/leaderboard screen list` | OP | 查看当前名称筛除特征 |
| `/leaderboard allowscoreboard [true\|false]` | OP | 设置是否允许普通玩家开启侧边计分板；无参数时查看当前状态 |
| `/leaderboard reload` | OP | 重新加载全部配置文件并后台重新生成排行榜 |
| `/leaderboard history [数量]` | OP | 查看或设置历史快照保留数量，0 为关闭；快照保存在 `leaderboard/history/` |
| `/leaderboard scoreboard on\|off` | 所有人 | 开关个人侧边计分板 |
| `/leaderboard scoreboard refresh interval [数字[t\|s\|m\|h]]` | OP | 设置计分板主动刷新间隔，设为 0 时跟随排行榜数据更新；无参数时查看当前间隔 |
| `/leaderboard help` | 所有人 | 显示指令帮助（OP 追加显示管理指令） |

## 配置文件

首次运行后在服务器根目录生成 `leaderboard/` 文件夹：

```
leaderboard/
├── config.json          # 显示模式、刷新间隔、广播与计分板开关、筛除特征、历史快照保留数量、计分板刷新间隔
├── whitelist.json       # 白名单玩家名数组
├── blacklist.json       # 黑名单玩家名数组
├── scoreboard.json      # 开启侧边计分板的玩家（手动编辑后 5 秒内自动生效）
├── stat_names.json      # 通用分类统计项中文名（可改，改动自动热重载）
├── custom_display.txt   # 自定义模式逐项开关（stat_id true/false）
├── history/             # 历史快照归档（leaderboard.json 的带时间戳副本，默认保留 30 份）
└── lang/zh_cn.json      # 中文翻译表（需自行放置，见下）
```

## 中文翻译表

物品/生物中文名从 `leaderboard/lang/zh_cn.json` 读取，该文件是 Minecraft 客户端官方语言文件（版权属于 Mojang，故不包含在本仓库中）。获取方式：

1. 打开客户端版本目录的 `assets/indexes/<对应索引>.json`，找到 `minecraft/lang/zh_cn.json` 的 hash
2. 在 `assets/objects/<hash前两位>/<hash>` 找到该文件
3. 复制为服务器根目录下 `leaderboard/lang/zh_cn.json`

不放置也可以正常使用，物品名会回退为英文。

## 构建

需要 JDK 21：

```
gradlew build
```

产物在 `build/libs/server-leaderboard-1.2.0+mc1.21.7.jar`，放入服务端 `mods/` 即可。
依赖：Fabric Loader >= 0.19.3、Fabric API >= 0.129.0、Minecraft 1.21.7。

## 许可

MIT
