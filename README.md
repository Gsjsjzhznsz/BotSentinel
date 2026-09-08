# BotSentinel

**无感风控反机器人 / Minecraft Anti-Bot Sentinel — Plugin (Folia/Paper/Spigot) + Fabric/Forge Mod**

> 支持 MC **1.20 ~ 26.2** · Java **17 ~ 25** · 一个工作流自动构建全平台并发布 Release

> 进服前拦截 · 特征库自动更新 · 真人封禁四重追踪 · IP 归属地拦截 · QQ群零感知

[![Build](https://github.com/Gsjsjzhznsz/BotSentinel/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/Gsjsjzhznsz/BotSentinel/actions)
`minecraft-plugin` `minecraft-mod` `folia` `paper` `spigot` `purpur` `fabric` `forge` `anti-bot` `bot-filter` `anti-griefer` `geoblock` `ip2region` `geoip` `risk-scoring` `strategy-pattern` `online-learning` `machine-learning` `java-17` `java-21` `1.20.1` `1.21.4` `26.2`

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
| 🤖 AI 策略评分引擎 (v2.3) | 策略模式四策略：规则启发式 + bigram 语言模型 + 信息熵 + 在线逻辑回归小模型；加权融合；真人名实锤事件/自动信任自动训练权重（model.json 持久化）—— 越用越懂你的服务器 |
| 🔄 配置自动迁移 (v2.3) | config-version 机制：v2.0~v2.2 旧配置升级自动备份、保留你的全部设置、自动补新键带注释 —— 以后更新永不再手改配置 |
| 🧩 登录命令自适应 (v2.3) | 不绑定特定登录插件：盯防期内任意命令+随机参数即可作为信号，实锤 bot 的命令自动学进本服命令库 —— AuthMe/CatSeedLogin/模组登录环境通吃 |
| 🚀 波次快速封禁 (v2.3) | 同 IP 被拦机器人达 N 次立即封 IP（默认 3，比新账号计数更快掐断刷波） |

## 环境要求

- **Java 17 ~ 25**（单个 jar 全兼容：编译目标 Java 17，GraalVM/虚拟线程等新 JVM 均可）
- **MC 1.20.1 ~ 26.2**：Folia / Lophine / Luminol / Paper / Purpur / Spigot
- 可选：ViaVersion（自动采集协议版本指纹）

## 版本兼容与升级

- 从 v2.0 / v2.1 / v2.2 升级：**直接换 jar 重启**。配置文件自动迁移（原文件备份为 `config.yml.bak-v*`，你改过的值全部保留，新选项自动补齐）
- IP 归属地库（`plugins/BotSentinel/geo/ip2region.xdb`）首次启动自动下载（GitHub/jsDelivr/gitee/ghproxy 多镜像），每 7 天自动更新，与地区拦截开关无关 —— `/atb geo on` 即开即用
- AI 评分模型自动训练数据存于 `plugins/BotSentinel/model.json`，随特征库一起保存

## 快速开始

1. 把 `BotSentinel-2.3.jar` 放进 `plugins/`，重启服务器（旧配置自动迁移；归属地库后台自动下载）
2. 特征库会从 `usercache.json` 自动播种已登记真人（过滤随机名），老玩家零感知
3. 需要地区拦截时执行 `/atb geo on` —— 本地库已就绪，立即生效
4. `/atb engine <玩家名>` 可查看 AI 评分引擎对该名字的四策略明细

## 命令一览（权限 `botsentinel.admin`，默认 OP）

```
/atb stats                     风控总览
/atb check <玩家名>            评分细节(在线含归属地/闪进闪退)
/atb lookup <IP>               查询任意IP归属地
/atb engine <玩家名|info>      AI评分引擎: 四策略明细/权重/训练量
/atb mode <block|observe>      切换拦截/观察模式

/atb geo on|off                一键开关地区拦截(写回配置)
/atb geo status                归属地库状态/规则/拦截量
/atb geo download              手动重新下载归属地库
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
/atb unlearn <类型> <值>       移除特征(name/shape/sig/ip/prefix/trust/cmd)
/atb undo [N]                  撤销最近N次特征库改动
/atb history [N]               查看特征库最近改动
/atb library                   特征库概览
/atb reload                    重载配置
```

## 它是怎么识别机器人的

1. **AI 策略评分引擎**（v2.3，纯 Java 可离线测试）：
   - `heuristic` 规则启发式：随机串（如 `WnyrLuSkBHhWO`）得分极高；拼音名（`XiaoMing`）、带数字名（`23451qwert`）、纯数字名（`415411`）等真人习惯一律 0~25 分
   - `markov` 字符 bigram 语言模型：从已验证真人名在线学习"人类命名习惯"，随机串转移概率天然极低
   - `entropy` 信息熵/重复度统计特征
   - `logistic` 在线逻辑回归小模型：实锤 bot=正样本、验证真人=负样本，SGD 自动更新权重并持久化
   - 融合规则：**保险丝只用校准过的规则分**（真人零误伤数学不变），模型分作为 0-25 加分参与总分 —— 边缘 bot 无需 IP 证据即可进入观察区
2. **拦截硬条件** = 评分 ≥ 70 **且** 随机性 ≥ 50 —— 真人名在数学上不可能同时满足。
3. **特征库反哺**：拦截、进服后实锤（秒发注册指令 / 被限速器踢 / 发广告 / 闪进闪退）
   都会自动学习名字形态、IP、/16 网段、广告签名 → 同源 bot 下一只直接在进服前被拦。
4. **真人自动信任**：在线满 10 分钟无异常 → 进入 verified 名单 → 以后所有检查全部豁免。

## 移植分支

| 分支 | 平台 | 构建产物 | 状态 |
|------|------|----------|------|
| `main` | Folia / Paper / Spigot 插件 (MC 1.20-26.2) | `BotSentinel-2.3.jar` | ✅ 完整功能 |
| `fabric` | Fabric 1.21.4 服务端 mod | `-fabric.jar` | 🧩 核心风控+地区拦截+聊天守卫 |
| `forge` | Forge 1.21.4 服务端 mod | `-forge.jar` | 🧩 核心风控+地区拦截+聊天守卫 |

mod 端通过登录协商阶段拒绝（Fabric: `checkCanJoin` mixin / Forge: `PlayerNegotiationEvent`），
同样做到 join 消息零感知。三个分支共享 `cn.botsentinel.core.*` 纯 Java 核心（评分引擎/特征库/归属地），
平台层各自适配 —— 新功能先在 core 实现再三端同步。

## CI/CD

**一个工作流**（`.github/workflows/build.yml`）构建全部平台：push 到 main 自动出三平台构建产物；
打 tag（`v*`）自动创建 GitHub Release，三个平台 jar + 排版好的下载说明一起发布。
手动触发时可指定 Fabric/Forge 的目标 MC 版本。

## 从源码构建

```bash
# main 分支 (Maven)
mvn clean package   # 产物: target/BotSentinel-2.3.jar

# fabric / forge 分支 (Gradle)
gradle build        # 产物: build/libs/*.jar
```

## License

MIT
