# Animius Web v1.1.3

修复 **字幕 403 拉不到** 与 **Silisili 偶发"线路解析失败"**。

## 修复

- 🩹 **HiAnime 字幕 403**（必定复现，非偶然）：字幕 CDN 开了反盗链，只认**播放页/embed 站来源**的 Referer，之前发的是字幕域名自来源 → 必 403
  - 实测矩阵：无 Referer / UA / 自来源 → 403；embed 来源 / hianime.at → 200 WEBVTT ✅
  - 现在 `/api/video` 把 embed 来源随字幕 URL 一路带下去（`ref` 参数），拉取失败还会依次兜底试其它候选 Referer
- 🩹 **Silisili「该线路解析失败」**（偶发）：取播放地址的加密 POST 用的是默认超时的裸 OkHttp（read 10s），站点偶发 10s+ 才响应 → SocketTimeout。实测解密链路本身正常
  - 超时加长到 45s + 自动重试 3 次
- 🧹 与上游 [lanlinju/Animius](https://github.com/lanlinju/Animius) 全量比对过：全部解析器一致（仅 Girigiri/网络层有 web 端适配差异），不是移植落后的问题

## 升级

```bash
# 替换 jar 后重启即可
java -jar animius-server-v1.1.3.jar --port 8090 --data /opt/animius/data
```
