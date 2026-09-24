# Animius Web v1.1.5

修复 **Silisili 等源视频黑屏（有控件、无画面）**：出站代理"一刀切"导致的 CDN 选路错误。

## 修复

- 🩹 **拉流/字幕按域名自动选路**（直连优先、代理兜底，试一次记住）
  - 根因：之前**所有**拉流都强制走出站代理，但 CDN 需求是相反的——
    | | ffzy（Silisili 视频） | hls.1embed（HiAnime） |
    |---|---|---|
    | 直连 | ✅ 200 | ❌ 不通 |
    | 走代理 | ❌ **403** | ✅ 200 |
  - Silisili 分片被代理到国外出口 → ffzy 反手 403 → 播放器永远等不到分片（控制台表现为 `AbortError: play() was interrupted by a new load request`，黑屏）
  - 现在首次请求自动探测走哪条路并**按域名记忆**，两个源同时可用；字幕拉取同理
- 🧪 实测：ffzy 分片/m3u8 直连 200（TS 数据）、1embed 分片走代理 200，双向全通

## 升级

```bash
# 替换 jar 后重启即可（数据目录与设置兼容）
java -jar animius-server-v1.1.5.jar --port 8090 --data /opt/animius/data
```
