# Server Leaderboard

Minecraft 1.21.7 Fabric 服务端模组：自动统计玩家数据并生成排行榜。
纯服务端实现，玩家客户端无需安装任何模组。

## 功能

- **自动生成排行榜**：服务器启动 30 秒后首次统计，之后每小时刷新一次，在服务器根目录输出 `leaderboard.html` 网页和 `leaderboard.json` 结构化数据
- **游戏内可视化界面**：`/leaderboard` 弹出箱子 GUI，含 排行榜 / 通用 / 物品 / 生物 / 食物与饮品使用排行 五个分类，物品与生物名称支持中文翻译
- **排除假人**：自动排除 `bot_` 前缀的 Carpet 假人，支持白名单/黑名单强制包含/排除
- **四种显示模式**：精简 / 普通 / 全部 / 自定义（自定义模式通过 `custom_display.txt` 逐项控制）
- **个人侧边计分板**：玩家可用 `/leaderboard scoreboard on` 开关显示自己的 9 项核心数据
- **可调刷新间隔**：`/leaderboard refresh interval 30s` 支持 t/s/m/h 单位，0 关闭自动刷新

## 指令

| 指令 | 权限 | 说明 |
|------|------|------|
| `/leaderboard` | 所有人 | 打开排行榜 GUI（控制台执行则输出文字版） |
| `/leaderboard refresh` | OP | 立即重新生成 |
| `/leaderboard refresh interval <数字>[t\|s\|m\|h]` | OP | 设置自动刷新间隔，0 为关闭 |
| `/leaderboard mode <compact\|normal\|full\|custom>` | OP | 切换显示模式 |
| `/leaderboard player list` / `list all` | OP | 查看排行榜包含的玩家 / 全部玩家 |
| `/leaderboard player add <玩家>` | OP | 加入白名单（强制包含） |
| `/leaderboard player remove <玩家>` | OP | 加入黑名单（强制排除） |
| `/leaderboard scoreboard on\|off` | 所有人 | 开关个人侧边计分板 |
| `/leaderboard help` | 所有人 | 显示指令帮助（OP 追加显示管理指令） |

## 配置文件

首次运行后在服务器根目录生成 `leaderboard/` 文件夹：

```
leaderboard/
├── config.json          # 显示模式与自动刷新间隔
├── whitelist.json       # 白名单玩家名数组
├── blacklist.json       # 黑名单玩家名数组
├── scoreboard.json      # 开启侧边计分板的玩家
├── custom_display.txt   # 自定义模式逐项开关（stat_id true/false）
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

产物在 `build/libs/server-leaderboard-1.1.0.jar`，放入服务端 `mods/` 即可。
依赖：Fabric Loader >= 0.19.3、Fabric API（0.129.0+1.21.7）、Minecraft 1.21.7。

## 许可

MIT
