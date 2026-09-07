# BotSentinel

**无感风控反机器人插件 / Minecraft Anti-Bot & Anti-Griefer Sentinel for Folia / Paper / Spigot**

> 进服前拦截 · 特征库自动更新 · 真人封禁四重追踪 · IP 归属地拦截 · QQ群零感知

[![Build](https://github.com/Gsjsjzhznsz/BotSentinel/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/Gsjsjzhznsz/BotSentinel/actions)
`minecraft-plugin` `folia` `paper` `spigot` `purpur` `anti-bot` `bot-filter` `geoblock` `ip2region` `java-21` `1.21.4`

---

## 这是什么

BotSentinel 是为 **Folia 系内核（Lophine / Luminol / Folia）** 与传统 Paper/Spigot 服务器设计的无感风控插件。
针对机器人压测/刷广告（随机用户名 + 聊天广告），以及**被封后重启路由器换 IP、换用户名重进的真人**，
提供"进服前拦截 + 进服后实锤 + 特征库自动进化"的完整闭环。

**核心卖点：纯无感**。所有拦截都发生在登录阶段（`AsyncPlayerPreLoginEvent`），
被拦的机器人不会产生任何 join/quit 消息 —— QQ 群桥（easyBOT 等）全程无感知，群里静悄悄。

## 功能特性

| 功能 | 说明 |
|------|------|
| 🔇 进服前拦截 | 登录阶段评分判定，QQ 群零感知 |
| 🧠 特征库自动更新 | 拦截/实锤双反哺：每只落网 bot 让下一只在进服前被拦；置信度 30 天自动衰减 |
| 🚫 真人封禁四重追踪 | `/atb ban 封真人`：账号名 + /16 网段连坐 + 客户端指纹 + 逃避计数，换 IP/换名/换热点都进不来 |
| 📈 逐犯加重 | 同一网段反复被封/逃避，封禁时长 120→360→1080 分钟自动递增（封顶 7 天） |
| 🌍 IP 归属地拦截 | 基于 ip2region 离线库：首次自动下载（~11MB）、每 7 天自动更新、全内存检索、**零 API 调用无限流**；一键只放行中国大陆，支持黑/白名单与 CIDR 豁免 |
| ⚡ 闪进闪退检测 | 进服 3 秒就断、反复进出的新漏网模式（window 内累计，自动提升 IP 风险） |
| 🛡️ 真人零误伤 | 拼音名/数字名数学上不可能被误拦（随机性保险丝）；已登记真人被 IP 封禁连坐自动豁免（移动 CGNAT 场景） |
| ↩️ 特征库撤销 | `/atb undo` 撤销最近添加、`/atb unlearn` 精确移除、`/atb history` 查看改动历史 —— 手动加错不再抓瞎 |
| 📳 Folia 原生 | 全异步调度（Async/Entity/Region Scheduler），不碰主线程 |

## 环境要求

- Java 21+
- Folia / Lophine / Luminol / Paper / Spigot 1.21.4（其他版本可自行改 pom 中的 paper-api 重新构建）
- 可选：ViaVersion（自动采集协议版本指纹）

## 快速开始

1. 把 `BotSentinel-2.2.jar` 放进 `plugins/`，重启服务器
2. 特征库会从 `usercache.json` 自动播种已登记真人（过滤随机名），老玩家零感知
3. 需要地区拦截时执行 `/atb geo on`（首次自动下载归属地库）

## 命令一览（权限 `botsentinel.admin`，默认 OP）

```
/atb stats                     风控总览
/atb check <玩家名>            评分细节(在线含归属地/闪进闪退)
/atb lookup <IP>               查询任意IP归属地
/atb mode <block|observe>      切换拦截/观察模式

/atb geo on|off                一键开关地区拦截(写回配置)
/atb geo status                归属地库状态/规则/拦截量
/atb geo test <IP>             测试某IP的地区判定

/atb ban <玩家名> [原因]       封真人(名+网段+指纹+逃避计数)
/atb pardon <玩家名>           解封真人
/atb banlist                   封禁列表与逃避记录
/atb banip <IP|CIDR> [分钟]    封IP/网段(自动联动/16)
/atb unbanip <IP|CIDR>         解封(含联动网段)
/atb evasion                   生效中的网段封禁

/atb learn <名|IP> [原因]      手动确认假人并学习
/atb learnmsg <广告原文>       手动学习广告签名
/atb trust <玩家名>            加入真人名单(永不拦截)
/atb unlearn <类型> <值>       移除特征(name/shape/sig/ip/prefix/trust)
/atb undo [N]                  撤销最近N次特征库改动
/atb history [N]               查看特征库最近改动
/atb library                   特征库概览
/atb reload                    重载配置
```

## 它是怎么识别机器人的

1. **名字随机性评分**（纯数学，离线可测）：随机串（如 `WnyrLuSkBHhWO`）得分极高；
   拼音名（`XiaoMing`）、带数字名（`23451qwert`）、纯数字名（`415411`）等真人习惯一律 0~25 分。
2. **拦截硬条件** = 评分 ≥ 70 **且** 随机性 ≥ 50 —— 真人名在数学上不可能同时满足。
3. **特征库反哺**：拦截、进服后实锤（秒发注册指令 / 被限速器踢 / 发广告 / 闪进闪退）
   都会自动学习名字形态、IP、/16 网段、广告签名 → 同源 bot 下一只直接在进服前被拦。
4. **真人自动信任**：在线满 10 分钟无异常 → 进入 verified 名单 → 以后所有检查全部豁免。

## 移植分支

| 分支 | 平台 | 构建产物 | 状态 |
|------|------|----------|------|
| `main` | Folia / Paper / Spigot 插件 | `BotSentinel-2.2.jar` | ✅ 完整功能 |
| `fabric` | Fabric 1.21.4 服务端 mod | `-fabric.jar` | 🧩 核心风控+地区拦截+聊天守卫 |
| `forge` | Forge 1.21.4 服务端 mod | `-forge.jar` | 🧩 核心风控+地区拦截+聊天守卫 |

mod 端通过登录协商阶段拒绝（Fabric: `checkCanJoin` mixin / Forge: `PlayerNegotiationEvent`），
同样做到 join 消息零感知。克隆对应分支即可用 Gradle 构建，GitHub Actions 会自动出包。

## 从源码构建

```bash
# main 分支 (Maven)
mvn clean package   # 产物: target/BotSentinel-2.2.jar

# fabric / forge 分支 (Gradle)
gradle build        # 产物: build/libs/*.jar
```

## License

MIT
