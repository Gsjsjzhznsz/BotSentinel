# BotSentinel (Fabric 分支)

**无感风控反机器人 · Fabric 1.21.4 服务端 mod**

> 进服前拦截 · 特征库自动更新 · 真人封禁网段追踪 · IP 归属地拦截 · QQ群零感知

[![Build](https://github.com/Gsjsjzhznsz/BotSentinel/actions/workflows/build.yml/badge.svg?branch=fabric)](https://github.com/Gsjsjzhznsz/BotSentinel/actions)
`fabric` `fabric-mod` `minecraft` `1.21.4` `anti-bot` `bot-filter` `server-mod` `geoblock`

> 本分支是 [main](https://github.com/Gsjsjzhznsz/BotSentinel/tree/main)(Folia/Paper/Spigot 插件) 的 Fabric 移植版。
> Forge 移植见 [forge](https://github.com/Gsjsjzhznsz/BotSentinel/tree/forge) 分支。

## 与插件版的差异

| 能力 | Fabric 版 (本分支) | 插件版 (main) |
|------|--------------------|---------------|
| 进服前拦截 | ✅ `checkCanJoin` mixin (登录阶段, join 消息零感知) | ✅ `AsyncPlayerPreLoginEvent` |
| 特征库自动更新 | ✅ 完整 | ✅ 完整 |
| 真人封禁(名+网段) | ✅ 完整 | ✅ 完整 |
| 客户端品牌指纹 | ⚠️ 暂不采集(网段追踪不受影响) | ✅ 完整 |
| IP 归属地拦截 | ✅ ip2region 离线库(Jar-in-Jar 内置) | ✅ 自动下载/自动更新 |
| 秒注册实锤 | ✅ `handleCommand` mixin | ✅ |
| 聊天广告守卫 | ✅ ServerMessageEvents | ✅ |
| 闪进闪退检测 | ✅ | ✅ |
| 命令 | `/botsentinel` 或 `/atb`(Brigadier, 等级≥2) | `/atb` |

## 安装

- Fabric 服务端 1.21.4 + Fabric Loader ≥0.16 + **Fabric API**
- 把 `BotSentinel-2.2-fabric.jar` 放进 `mods/`
- 配置/数据: `config/BotSentinel/{config.json, library.json, stats.json, geo/}`
- 开启地区拦截: `/botsentinel geo on`（首次自动检索内置库，无需下载）

## 从源码构建

```bash
gradle build   # 产物: build/libs/BotSentinel-2.2-fabric.jar
```

## 命令

```
/botsentinel stats                    风控总览
/botsentinel ban <名> [原因]          封真人(名+网段追踪)
/botsentinel pardon <名>              解封
/botsentinel banip <IP|CIDR> [分钟]   封IP(联动网段)
/botsentinel unbanip <IP|CIDR>        解封
/botsentinel learn <名|IP>            学习假人特征
/botsentinel trust <名>               加入真人名单
/botsentinel unlearn <类型> <值>      移除特征
/botsentinel undo [N] / history       撤销/历史
/botsentinel geo on|off|test <IP>     地区拦截
```

## License

MIT
