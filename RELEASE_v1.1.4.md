# Animius Web v1.1.4

修复 **无字幕源黑屏** 与 **AI 双语"翻完没中文"** 两个问题（均经无头浏览器/端到端实测定位验证）。

## 修复

- 🩹 **Silisili 等无字幕轨的源：播放页黑屏、控件不出来**
  - 根因：Artplayer 5.4 对 `subtitle: undefined` 的类型校验直接抛 `Type Error`，播放器构造失败 → 黑屏无控件（HiAnime 有字幕轨所以没触发）
  - 现在无字幕轨时干脆不传 `subtitle` 字段。无头 Chrome 复现 → 修复 → 验证：控件正常、1080p 正在播放
- 🩹 **AI 双语翻完了却没中文（纯原文被当成品缓存）**
  - 根因：LLM 调用失败（如模型在 OpenRouter 下架返回 404）被静默吞掉，空结果照样"完成"并落缓存，界面上完全看不出来
  - 现在：翻译失败**明确报错**（如 `LLM 翻译失败: No endpoints found for xxx`）；一条译文都没翻出来时**不落缓存**；批次失败保留断点（`.part.json`），重试自动续翻不重复花钱
- 💡 若你的设置页里模型报 404/不可用，请换模型：实测可用 `deepseek/deepseek-chat`（便宜、中文自然）或 `qwen/qwen-2.5-72b-instruct`

## 升级

```bash
# 替换 jar 后重启即可（数据目录兼容，断点续翻快照也兼容）
java -jar animius-server-v1.1.4.jar --port 8090 --data /opt/animius/data
```
