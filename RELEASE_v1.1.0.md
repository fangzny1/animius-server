# Animius Web v1.1.0

自 v1.0.0 以来的重点更新：**AI 双语字幕** 与 **HiAnime 源**。

## 新功能

- 🈶 **AI 双语字幕**（实验性）
  - HiAnime 源自动提取字幕轨（VTT）
  - 通过 OpenAI 兼容接口（DeepSeek / GLM / OpenRouter / 本地 llama.cpp 等均可）分批翻译，生成"原文 + 中文"双语字幕
  - 按集缓存：同一集只翻译一次，之后秒开；设置页可管理/清空缓存
  - 播放页字幕独立开关、多轨道选择、字号与背景样式（纯阴影 / 半透明底条 / 描边）可调
  - LLM 连接测试按钮；源站自带中文字幕时自动使用，不消耗翻译额度
- 🎬 **HiAnime 源**（ani-cli 同源，hianime.at）：搜索 / 详情 / 多剧集 / 多画质 HLS
- 🌐 **出站代理**：可为抓源/字幕配置 HTTP/SOCKS 代理（设置页，改后需重启服务）
- 🔧 稳定性：字幕一次性签名 URL 自动重试、403 错误页识别（不再误当字幕缓存）、抓取客户端信任所有证书、强制 IPv4

## 修复

- 数据源切换后验证码 Cookie 错位的问题
- 部分 CDN 对非常见 User-Agent 返回空壳页的问题（固定 Chrome UA）
- Girigiri 等源站改版导致的搜索失效（部分）

## 升级

```bash
# 替换 jar 后重启即可（数据目录兼容）
java -jar animius-server-v1.1.0.jar --port 8090 --data /opt/animius/data
# 重设管理员密码
java -jar animius-server-v1.1.0.jar admin set <新密码>
```

## 已知限制

- HiAnime 的字幕 CDN 仅开放中文轨下载，英文/日文轨为源站限制（AI 双语在中文轨基础上做繁→简）
- OpenRouter 免费模型有限额（约 50 请求/天），一整集字幕约 12 个请求
- 其他网页抓取源存活率波动大

## 协议

GPL-3.0（因复用上游 [lanlinju/Animius](https://github.com/lanlinju/Animius) 代码）
