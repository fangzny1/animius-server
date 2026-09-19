# Animius Web v1.0.0

[Animius](https://github.com/lanlinju/Animius)（安卓追番 App）的 Linux 网页端移植版。单 jar 文件，专为低内存环境设计（手机里的 Linux 容器实测运行内存 ~100MB）。

## 下载

- `animius-server-v1.0.0.jar` — 直接运行，见下方快速开始

环境要求：**Java 21+**（容器里 `apt install openjdk-21-jre-headless` 或已有）

## 快速开始

```bash
java -jar animius-server-v1.0.0.jar --port 8090 --data ./data
```

首次启动会在日志里打印**自动生成的管理员密码**（Alist 风格），浏览器访问 `http://<ip>:8090` 登录。改密码：

```bash
java -jar animius-server-v1.0.0.jar admin set <新密码>
```

## 功能

- 🖥️ 网页端：首页推荐 / 时间表 / 搜索 / 多线路选集 / 播放 / 历史（断点续播）/ 收藏
- 📺 HLS(m3u8) + MP4 播放，弹幕（弹弹play），倍速 / 画中画
- 🈶 **AI 双语字幕**：自动提取 HiAnime 源字幕，走 OpenAI 兼容接口翻译成"原文+中文"，按集缓存（设置页配置 API，本地 llama.cpp/ollama 也行）
- 🔄 视频全走服务端流式代理（Referer / Range / m3u8 重写），规避防盗链和 CORS
- 🌐 出站代理可配（被墙 CDN 的源依赖它）
- 🔐 风控验证码辅助：网页引导人工过验证，Cookie 支持请求头格式和浏览器插件导出的 Netscape 格式

## 数据源

- **HiAnime**（ani-cli 同源，hianime.at）：多画质 + 英文字幕，AI 翻译适用，**推荐**
- **Silisili**（默认源）：国内站，全链路可用
- 另有 AGE动漫、次元城等 10 个源：网页抓取类源站存活率波动大，部分已失效

## 部署提示（Linux 容器 / systemd）

```ini
[Service]
ExecStart=/usr/bin/java -Djava.net.preferIPv4Stack=true -Xmx128m -XX:MaxMetaspaceSize=64m -XX:+UseSerialGC -jar /opt/animius/animius-server.jar --port 8090 --data /opt/animius/data
MemoryMax=300M
Restart=on-failure
```

设置页可配：弹弹play 凭据、出站代理（如 `http://127.0.0.1:10800`）、AI 字幕的 API Base / Key / 模型。改出站代理后需重启服务。

## 已知限制

- 视频地址有时效性，过期了重新解析即可
- HiAnime 的字幕/视频 CDN 在部分地区直连被墙，需在设置页配出站代理
- OpenRouter 免费模型有限额（约 50 请求/天），一整集字幕 ≈ 12 个请求，翻过的集有缓存不重复消耗

## 协议

GPL-3.0（因复用上游 Animius 代码）。解析器移植自 [lanlinju/Animius](https://github.com/lanlinju/Animius)，弹幕数据来自 [弹弹play](https://www.dandanplay.com) 开放 API。
