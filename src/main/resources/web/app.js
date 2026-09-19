/* Animius Web - vanilla JS SPA */
"use strict";

const $ = (s) => document.querySelector(s);
const view = $("#view");
let ME = null;
let SOURCES = [];
let detailCtx = null;   // {source, url, title, img, channels, activeChan}
let watchCtx = null;    // {source, title, img, animeUrl, episodeName, episodeUrl, upstream}
let art = null, hls = null, danmakuOn = true, saveTimer = null;

/* ---------- api ---------- */
async function api(path, opts = {}) {
  const res = await fetch(path, Object.assign({ headers: { "Content-Type": "application/json" } }, opts));
  if (res.status === 401) { renderLogin(); throw new Error("unauthorized"); }
  const text = await res.text();
  try { return JSON.parse(text); } catch (e) { return text; }
}

/* ---------- 路由 ---------- */
function go(name, params = {}) {
  location.hash = "#" + name + (Object.keys(params).length ? "?" + new URLSearchParams(params) : "");
}
function route() {
  const h = location.hash.replace(/^#\/?/, "") || "home";
  const [name, qs] = h.split("?");
  const p = Object.fromEntries(new URLSearchParams(qs || ""));
  stopPlayer();
  $("#topbar").classList.toggle("hidden", !ME);
  if (!ME) return renderLogin();
  document.querySelectorAll("#topbar nav button").forEach(b => b.classList.toggle("active", b.dataset.nav === name));
  ({ home: renderHome, week: renderWeek, search: renderSearch, detail: renderDetail,
     watch: renderWatch, history: renderHistory, favourites: renderFavourites,
     settings: renderSettings }[name] || renderHome)(p);
}
window.addEventListener("hashchange", route);

/* ---------- 登录 ---------- */
function renderLogin() {
  $("#topbar").classList.add("hidden");
  view.innerHTML = `
    <div class="login-box">
      <h1>▲ Animius Web</h1>
      <p class="hint">首次部署的管理员密码在服务端日志里<br>（也可在容器内执行 admin set 重设）</p>
      <div class="err" id="login-err"></div>
      <input type="text" id="lu" placeholder="账号" value="admin">
      <input type="password" id="lp" placeholder="密码" autofocus>
      <button class="btn" id="lgo">登 录</button>
    </div>`;
  const doLogin = async () => {
    try {
      const r = await api("/api/login", { method: "POST", body: JSON.stringify({ username: $("#lu").value, password: $("#lp").value }) });
      ME = r.user; $("#whoami").textContent = ME;
      go("home"); route();
    } catch (e) { $("#login-err").textContent = "账号或密码错误"; }
  };
  $("#lgo").onclick = doLogin;
  $("#lp").onkeydown = (e) => { if (e.key === "Enter") doLogin(); };
}
async function logout() { await api("/api/logout", { method: "POST" }); ME = null; renderLogin(); }

/* ---------- 组件 ---------- */
function card(a, opts = {}) {
  return `<div class="card" onclick="${opts.onclick || `openDetail('${a.source}','${esc(a.url)}')`}">
    <div class="pic">
      ${opts.deleteBtn ? `<button class="delete-x" onclick="event.stopPropagation();${opts.deleteBtn}">✕</button>` : ""}
      ${a.img ? `<img loading="lazy" src="${esc(a.img)}" onerror="this.style.visibility='hidden'">` : ""}
      ${a.episode ? `<span class="ep">${esc(a.episode)}</span>` : ""}
    </div>
    <div class="t">${esc(a.title)}</div>
    ${opts.progress ? `<div class="prog" style="width:${Math.min(100, a.progress)}%"></div>` : ""}
  </div>`;
}
function esc(s) { return String(s ?? "").replace(/'/g, "\\'").replace(/"/g, "&quot;").replace(/</g, "&lt;"); }
function raw(s) { return String(s ?? ""); }

async function loadSources(select) {
  SOURCES = await api("/api/sources");
  if (select) return select;
  return (SOURCES.find(s => s.current) || {}).id;
}
function sourceSelect(current, onchange) {
  return `<select id="srcsel">${SOURCES.map(s =>
    `<option value="${s.id}" ${s.id === current ? "selected" : ""}>${s.name}</option>`).join("")}</select>`;
}

/* ---------- 首页 ---------- */
async function renderHome(p) {
  view.innerHTML = `<div class="loading">加载中…</div>`;
  await loadSources();
  const cur = p.source || (SOURCES.find(s => s.current) || {}).id;
  const data = await api(`/api/home?source=${cur}`);
  view.innerHTML = `
    <div class="row">
      <h2 class="sect" style="margin:0">首页推荐</h2>
      ${sourceSelect(cur)}
    </div>
    ${data.map(sec => `<h2 class="sect">${esc(sec.title)}</h2>
      <div class="grid">${sec.animes.map(a => card(a)).join("")}</div>`).join("")}`;
  $("#srcsel").onchange = (e) => go("home", { source: e.target.value });
}

/* ---------- 时间表 ---------- */
async function renderWeek(p) {
  view.innerHTML = `<div class="loading">加载中…</div>`;
  await loadSources();
  const cur = p.source || (SOURCES.find(s => s.current) || {}).id;
  const data = await api(`/api/week?source=${cur}`);
  const days = Object.keys(data).sort((a, b) => a - b);
  const day = p.day && data[p.day] ? p.day : days.find(d => data[d].length) || days[0];
  const names = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];
  view.innerHTML = `
    <div class="row"><h2 class="sect" style="margin:0">更新时间表</h2>${sourceSelect(cur)}</div>
    <div class="chan-tabs" style="margin-top:12px">${days.map(d =>
      `<button class="${d == day ? "active" : ""}" onclick="go('week',{source:'${cur}',day:'${d}'})">${names[d] ?? d}</button>`).join("")}</div>
    <div class="grid">${(data[day] || []).map(a => card(a)).join("")}</div>`;
  $("#srcsel").onchange = (e) => go("week", { source: e.target.value });
}

/* ---------- 搜索 ---------- */
async function renderSearch(p) {
  await loadSources();
  const cur = p.source || (SOURCES.find(s => s.current) || {}).id;
  view.innerHTML = `
    <h2 class="sect">搜索</h2>
    <div class="row">
      <input type="search" id="q" placeholder="输入番剧名…" value="${raw(p.q || "")}" style="flex:1;min-width:200px">
      <select id="srcsel">${SOURCES.map(s =>
        `<option value="${s.id}" ${s.id === cur ? "selected" : ""}>${s.name}</option>`).join("")}</select>
      <button class="btn" id="qgo">搜索</button>
    </div>
    <div id="qres"></div>`;
  const run = async () => {
    const q = $("#q").value.trim();
    if (!q) return;
    $("#qres").innerHTML = `<div class="loading">搜索中…</div>`;
    const list = await api(`/api/search?q=${encodeURIComponent(q)}&page=${p.page || 1}&source=${$("#srcsel").value}`);
    if (list.length) {
      $("#qres").innerHTML = `<div class="grid">${list.map(a => card(a)).join("")}</div>`;
    } else {
      $("#qres").innerHTML = `<div class="empty">「${esc(q)}」在当前源没有结果，可换上面的数据源重试，或检查是否触发了风控</div>`;
      renderCaptchaHelper($("#qres"));
    }
  };
  $("#qgo").onclick = run;
  $("#q").onkeydown = (e) => { if (e.key === "Enter") run(); };
  $("#srcsel").onchange = run;
  if (p.q) run();
}
function openDetail(source, url) { go("detail", { source, url }); }

/* ---------- 验证码辅助（对应 App 的 WebView 人工过验证） ---------- */
async function renderCaptchaHelper(container) {
  let st = {};
  try { st = await api("/api/captcha"); } catch (e) { return; }
  if (!st.pending) return;
  container.insertAdjacentHTML("beforeend", `
    <div class="captcha-box">
      <p><b>⚠ 该数据源触发了验证码</b></p>
      <p class="tip">1. 在浏览器新标签页打开下面的链接，完成人机验证；<br>
         2. 验证通过后按 F12 → 网络(Network) → 任意请求 → 复制请求头里完整的 Cookie 值；<br>
         3. 粘贴到下面并保存，然后重试搜索。</p>
      <p><a href="${raw(st.url)}" target="_blank" rel="noopener">${raw(st.url)}</a></p>
      <textarea id="ck-input" rows="2" placeholder="粘贴 Cookie，如: xx=yy; zz=ww"></textarea>
      <div><button class="btn" id="ck-save">保存 Cookie</button></div>
      <div class="err" id="ck-err"></div>
    </div>`);
  $("#ck-save").onclick = async () => {
    const v = $("#ck-input").value.trim();
    if (!v) { $("#ck-err").textContent = "请先粘贴 Cookie"; return; }
    await api("/api/captcha", { method: "POST", body: JSON.stringify({ cookies: v }) });
    $("#ck-err").style.color = "#7ee787";
    $("#ck-err").textContent = "已保存，请重试搜索";
  };
}

/* ---------- 详情 ---------- */
async function renderDetail(p) {
  if (!p.url) { go("home"); return; }
  view.innerHTML = `<div class="loading">加载中…</div>`;
  const d = await api(`/api/detail?url=${encodeURIComponent(p.url)}&source=${p.source || ""}`);
  detailCtx = { source: d.source, url: p.url, title: d.title, img: d.img, channels: d.channels, fav: d.favourited };
  const chans = Object.keys(d.channels).sort();
  const chan = p.chan && d.channels[p.chan] ? p.chan : chans[0];
  view.innerHTML = `
    <div class="detail">
      <div class="poster"><img src="${raw(d.img)}" onerror="this.style.visibility='hidden'"></div>
      <div class="info">
        <h1>${esc(d.title)}</h1>
        <div class="tags">${d.tags.map(t => `<span>${esc(t)}</span>`).join("")}</div>
        <div class="desc">${esc(d.desc)}</div>
        <div class="row">
          <button class="btn ${d.favourited ? "ghost" : ""}" id="favbtn">${d.favourited ? "★ 已收藏" : "☆ 收藏"}</button>
        </div>
        <div class="episodes">
          ${chans.length > 1 ? `<div class="chan-tabs">${chans.map(c =>
            `<button class="${c === chan ? "active" : ""}" onclick="go('detail',{url:'${esc(p.url)}',source:'${d.source}',chan:'${c}'})">线路 ${+c + 1}</button>`).join("")}</div>` : ""}
          <div class="eplist">${d.channels[chan].map((ep, i) =>
            `<button onclick="openWatch('${d.source}','${esc(d.title)}','${esc(ep.url)}','${esc(ep.name)}','${esc(p.url)}','${i}')">${esc(ep.name)}</button>`).join("")}</div>
        </div>
      </div>
    </div>
    <h2 class="sect">相关推荐</h2>
    <div class="grid">${d.related.map(a => card(a)).join("")}</div>`;
  $("#favbtn").onclick = async () => {
    const method = detailCtx.fav ? "/api/favourites/delete" : "/api/favourites";
    await api(method, { method: "POST", body: JSON.stringify({ source: detailCtx.source, title: detailCtx.title, url: detailCtx.url, img: detailCtx.img }) });
    detailCtx.fav = !detailCtx.fav;
    $("#favbtn").textContent = detailCtx.fav ? "★ 已收藏" : "☆ 收藏";
    $("#favbtn").classList.toggle("ghost", detailCtx.fav);
  };
}

/* ---------- 播放 ---------- */
function openWatch(source, title, epUrl, epName, animeUrl, epIndex) {
  go("watch", { source, title, epUrl, epName, animeUrl, epIndex });
}
async function renderWatch(p) {
  view.innerHTML = `<div class="loading">解析视频地址…</div>`;
  let v;
  try {
    v = await api(`/api/video?url=${encodeURIComponent(p.epUrl)}&source=${p.source}&title=${encodeURIComponent(p.title)}&ep=${encodeURIComponent(p.epName)}`);
    if (typeof v !== "object" || !v.playUrl) throw new Error("bad response");
  } catch (e) {
    view.innerHTML = `<div class="empty">❌ 该线路解析失败（线路可能已失效、超时或需要浏览器内核）<br><br>
      <button class="btn" onclick="history.back()">← 返回详情，换一条线路试试</button></div>`;
    return;
  }
  watchCtx = { source: p.source, title: p.title, img: "", animeUrl: p.animeUrl, episodeName: p.epName, episodeUrl: p.epUrl, upstream: v.upstream };
  const isHls = (v.upstream || "").split("?")[0].endsWith(".m3u8");
  view.innerHTML = `
    <div class="player-wrap"><div id="player"></div></div>
    <div class="row danmaku-toggle">
      <label style="color:var(--dim);font-size:14px"><input type="checkbox" id="dmk" checked> 弹幕</label>
      <span style="color:var(--dim);font-size:13px">${esc(p.title)} · ${esc(p.epName)}</span>
    </div>`;
  $("#playerbar").classList.remove("hidden");

  const history = await api("/api/history");
  const prev = (history || []).find(h => h.animeUrl === p.animeUrl && h.episodeUrl === p.epUrl);
  const startPos = prev ? prev.position : 0;

  art = new Artplayer({
    container: "#player", url: v.playUrl, type: isHls ? "m3u8" : "mp4",
    volume: 0.7, autoplay: true, setting: true, playbackRate: true, aspectRatio: true, flip: true,
    fullscreen: true, fullscreenWeb: true, miniProgressBar: true, airplay: true, pip: true,
    autoOrientation: true, autoSize: false,
    customType: {
      m3u8: function (video, url) {
        if (hls) { hls.destroy(); hls = null; }
        if (Hls.isSupported()) {
          hls = new Hls({ maxBufferLength: 30 });
          hls.loadSource(url);
          hls.attachMedia(video);
          hls.on(Hls.Events.MANIFEST_PARSED, () => video.play());
        } else if (video.canPlayType("application/vnd.apple.mpegurl")) {
          video.src = url;
        }
      },
    },
  });

  art.on("ready", () => { if (startPos > 5) art.seek = startPos; });

  // 弹幕
  try {
    const dm = await api(`/api/danmaku?title=${encodeURIComponent(p.title)}&episode=${encodeURIComponent(p.epName)}`);
    if (dm.enabled && dm.comments.length) {
      const items = dm.comments.map(c => ({
        time: c.time, text: c.text,
        mode: c.mode === 4 ? 1 : c.mode === 5 ? 2 : 0,
        color: "#" + (c.color || 16777215).toString(16).padStart(6, "0"),
      }));
      const plugin = ArtplayerPluginDanmuku({ danmaku: items, speed: 6, margin: [8, "25%"], opacity: 0.9, antiOverlap: true });
      art.plugins.add(plugin);
      $("#dmk").onchange = (e) => { danmakuOn = e.target.checked; art.plugins[plugin.name].show = danmakuOn; };
    } else { $("#dmk").parentElement.style.display = "none"; }
  } catch (e) { $("#dmk").parentElement.style.display = "none"; }

  // 进度保存（每5秒 + 暂停/退出时）
  const save = () => {
    if (!art || art.currentTime < 5) return;
    api("/api/history", { method: "POST", body: JSON.stringify({
      source: watchCtx.source, animeTitle: watchCtx.title, animeUrl: watchCtx.animeUrl,
      img: watchCtx.img, episodeName: watchCtx.episodeName, episodeUrl: watchCtx.episodeUrl,
      position: art.currentTime }) });
  };
  clearInterval(saveTimer);
  saveTimer = setInterval(save, 5000);
  art.on("video:pause", save);
  art.on("destroy", save);
}

function stopPlayer() {
  clearInterval(saveTimer);
  if (art) { try { art.destroy(false); } catch (e) {} art = null; }
  if (hls) { try { hls.destroy(); } catch (e) {} hls = null; }
  $("#playerbar").classList.add("hidden");
}

/* ---------- 历史 ---------- */
async function renderHistory() {
  const list = await api("/api/history");
  if (!list.length) { view.innerHTML = `<div class="empty">还没有观看记录</div>`; return; }
  view.innerHTML = `<h2 class="sect">观看历史</h2>
    <div class="grid">${list.map(h => card(
      { source: h.source, url: h.animeUrl, img: h.img, title: h.title || h.animeTitle, episode: h.episodeName },
      { progress: h.position ? Math.round((h.position % 1e5) / 24) : 0,
        onclick: `openWatch('${h.source}','${esc(h.animeTitle)}','${esc(h.episodeUrl)}','${esc(h.episodeName)}','${esc(h.animeUrl)}',0)`,
        deleteBtn: `delHistory('${esc(h.animeUrl)}')` }
    )).join("")}</div>`;
}
async function delHistory(url) { await api("/api/history/delete", { method: "POST", body: JSON.stringify({ animeUrl: url }) }); route(); }

/* ---------- 收藏 ---------- */
async function renderFavourites() {
  const list = await api("/api/favourites");
  if (!list.length) { view.innerHTML = `<div class="empty">还没有收藏</div>`; return; }
  view.innerHTML = `<h2 class="sect">我的收藏</h2>
    <div class="grid">${list.map(a => card(a, { deleteBtn: `delFav('${esc(a.url)}')` })).join("")}</div>`;
}
async function delFav(url) { await api("/api/favourites/delete", { method: "POST", body: JSON.stringify({ url }) }); route(); }

/* ---------- 设置 ---------- */
async function renderSettings() {
  const s = await api("/api/settings");
  view.innerHTML = `
    <div class="settings-box">
      <h2 class="sect">设置</h2>
      <label>弹弹play AppId（用于弹幕匹配，留空则不加载弹幕）</label>
      <input type="text" id="s-appid" value="${raw(s.ddpAppId || "")}">
      <label>弹弹play AppSecret</label>
      <input type="password" id="s-secret" value="${raw(s.ddpSecret || "")}">
      <div style="margin-top:16px"><button class="btn" id="s-save">保存</button></div>
      <p class="tip">申请地址：https://api.dandanplay.net/register<br>
      服务端数据目录含 settings.json（数据源域名等）与 animius.db（历史/收藏）。</p>
    </div>`;
  $("#s-save").onclick = async () => {
    await api("/api/settings", { method: "POST", body: JSON.stringify({ ddpAppId: $("#s-appid").value, ddpSecret: $("#s-secret").value }) });
    $("#s-save").textContent = "已保存 ✓";
    setTimeout(() => $("#s-save").textContent = "保存", 1500);
  };
}

/* ---------- 启动 ---------- */
(async function boot() {
  try {
    const r = await api("/api/sources");
    SOURCES = r;
    const me = await fetch("/api/me").then(x => x.json()).catch(() => ({}));
    ME = me.user || null;
    if (ME) $("#whoami").textContent = ME;
  } catch (e) { ME = null; }
  route();
})();
