# Animius Web

[Animius](https://github.com/lanlinju/Animius)（安卓追番 App）的 **Linux 网页端移植版**：Kotlin + Ktor 服务端，复用原 App 的数据源解析器，在浏览器里看番，专为低内存环境（如手机里的 Linux 容器）设计。

![技术栈](https://img.shields.io/badge/Kotlin-2.2-7c5cff) ![Ktor](https://img.shields.io/badge/Ktor-3.2-087cfa) ![License](https://img.shields.io/badge/License-GPL--3.0-blue)

## 功能

- 🖥️ 网页端：首页推荐 / 更新时间表 / 搜索 / 详情（多线路选集）/ 播放 / 历史（断点续播）/ 收藏
- 📺 播放器：HLS(m3u8) + MP4，内置弹幕层（弹弹play），倍速 / 画幅 / 画中画
- 🔄 视频全走服务端流式代理（自动带 Referer、Range 支持、m3u8 地址重写），规避防盗链与 CORS
- 🔐 Alist 式首次启动：无管理员时自动生成随机密码打印到日志，`admin set` 命令可改密
- 📦 单 fat jar 部署，SQLite 存储，实测运行内存 ~100-140MB（`-Xmx128m` + systemd `MemoryMax=300M`）
- 🛡️ 风控验证码辅助：源站触发验证码时网页端引导人工过验证并回贴 Cookie

## 快速开始

```bash
# 构建（JDK 21）
./gradlew shadowJar
java -jar build/libs/animius-server.jar --port 8090 --data ./data
# 首次启动日志会打印自动生成的 admin 密码
```

浏览器访问 `http://<ip>:8090`。

### 修改管理员密码

```bash
java -jar animius-server.jar admin set <新密码>
```

### 命令行参数

| 参数 | 说明 | 默认 |
|---|---|---|
| `--port` | 监听端口 | 8090 |
| `--data` | 数据目录（SQLite + 设置） | `./data` |

## 部署到 Linux 容器（Droidspaces / chroot / LXC 均可）

容器里只需要 Java 21。以 systemd 为例：

```ini
# /etc/systemd/system/animius.service
[Unit]
Description=Animius Web
After=network.target

[Service]
WorkingDirectory=/opt/animius
ExecStart=/usr/bin/java -Xmx128m -XX:MaxMetaspaceSize=64m -XX:+UseSerialGC -jar /opt/animius/animius-server.jar --port 8090 --data /opt/animius/data
Restart=on-failure
MemoryMax=300M

[Install]
WantedBy=multi-user.target
```

## 数据源

复用 Animius 的 13 个番剧数据源解析器（`data/remote/parse/`）。网页抓取类源站存活率波动很大，
部分源的播放页依赖浏览器内核执行 JS（原 App 用 WebView 解决），服务端对这类源会解析失败并提示换线路。
用哪个源在网页上下拉切换即可。

## 致谢与协议

- 解析器、DTO、弹幕客户端移植自 [lanlinju/Animius](https://github.com/lanlinju/Animius)（GPL-3.0）
- 弹幕数据来自 [弹弹play](https://www.dandanplay.com) 开放 API
- 本项目因此以 **GPL-3.0** 协议开源
