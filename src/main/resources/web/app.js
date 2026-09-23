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
      renderCaptchaHelper($("#qres"), $("#srcsel").value);
    }
  };
  $("#qgo").onclick = run;
  $("#q").onkeydown = (e) => { if (e.key === "Enter") run(); };
  $("#srcsel").onchange = run;
  if (p.q) run();
}
function openDetail(source, url) { go("detail", { source, url }); }

/* ---------- 验证码辅助（对应 App 的 WebView 人工过验证） ---------- */
async function renderCaptchaHelper(container, source) {
  let st = {};
  try { st = await api("/api/captcha"); } catch (e) { return; }
  if (!st.pending) return;
  container.insertAdjacentHTML("beforeend", `
    <div class="captcha-box">
      <p><b>⚠ 该数据源触发了验证码</b></p>
      <p class="tip">1. 在浏览器新标签页打开下面的链接，完成人机验证；<br>
         2. 验证通过后把 Cookie 粘贴到下面：F12 → 网络 → 任意请求 → 复制请求头里的 Cookie 值，<br>
         或用浏览器插件导出的 Netscape 格式整段粘贴，两种都支持；<br>
         3. 保存后重试搜索。</p>
      <p><a href="${raw(st.url)}" target="_blank" rel="noopener">${raw(st.url)}</a></p>
      <textarea id="ck-input" rows="4" placeholder="粘贴 Cookie：请求头格式 xx=yy; zz=ww 或 Netscape 导出格式"></textarea>
      <div><button class="btn" id="ck-save">保存 Cookie</button></div>
      <div class="err" id="ck-err"></div>
    </div>`);
  $("#ck-save").onclick = async () => {
    const v = $("#ck-input").value.trim();
    if (!v) { $("#ck-err").textContent = "请先粘贴 Cookie"; return; }
    const r = await api("/api/captcha", { method: "POST", body: JSON.stringify({ cookies: v, source: source || st.source }) });
    $("#ck-err").style.color = "#7ee787";
    $("#ck-err").textContent = `已保存到 ${r.source}（${r.count} 条），请重试搜索`;
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
  const ST = await api("/api/settings");
  const subFontSize = (parseInt(ST.subFontSize) || 22);
  const subBgMode = ST.subBg || "shadow";
  const subTracks = v.subtitles || [];
  // 默认轨：英文优先（配合 AI 双语学习），无英文取第一轨
  const defaultTrack = subTracks.find(t => t.lang === "en") || subTracks[0] || null;
  const subStyleBase = { color: "#FFE082", fontSize: subFontSize + "px" };
  if (subBgMode === "bar") { /* 底条由 barCss 绘制 */ }
  else if (subBgMode === "outline") { subStyleBase["-webkit-text-stroke"] = "1.2px #000"; subStyleBase["text-shadow"] = "0 1px 3px #000"; }
  else { subStyleBase["text-shadow"] = "0 0 6px #000, 0 2px 6px #000, 1px 1px 2px #000"; }
  // bar 模式：底条贴合文字宽度（Artplayer 默认把底条画在整行宽度的容器上）
  const barCss = (ST.subBg === "bar") ? `<style>
    .art-subtitle { width: auto !important; left: 50% !important; transform: translateX(-50%) !important; max-width: 94% !important; }
    .art-subtitle p { display: inline !important; background: rgba(0,0,0,.55) !important; padding: 2px 12px !important; -webkit-box-decoration-break: clone; box-decoration-break: clone; border-radius: 3px; }
    .art-subtitle p:empty { display: none; }
  </style>` : "";
  view.innerHTML = `
    ${barCss}
    <div class="player-wrap"><div id="player"></div></div>
    <div class="row danmaku-toggle">
      <label style="color:var(--dim);font-size:14px"><input type="checkbox" id="dmk" checked> 弹幕</label>
      <span style="color:var(--dim);font-size:13px">${esc(p.title)} · ${esc(p.epName)}</span>
      <span id="subarea" style="display:flex;gap:8px;align-items:center"></span>
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
    subtitle: defaultTrack ? {
      url: defaultTrack.url, name: "animius", type: "vtt", escape: false, encoding: "utf-8",
      style: subStyleBase, onVttLoad: (v) => v,
    } : undefined,
    customType: {
      m3u8: function (video, url) {
        if (hls) { hls.destroy(); hls = null; }
        if (Hls.isSupported()) {
          hls = new Hls({ maxBufferLength: 60, maxMaxBufferLength: 300, backBufferLength: 30 });
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

  // 字幕区：开关 / 轨道选择 / AI 双语 / 进度（字幕轨由 HiAnime 源提供）
  const subarea = $("#subarea");
  if (subTracks.length) {
    const st = ST;
    const tracks = subTracks;
    let cur = defaultTrack;
    const zhTrack = tracks.find(t => t.lang === "zh");
    subarea.innerHTML = `
      <label style="color:var(--dim);font-size:14px"><input type="checkbox" id="subon" checked> 字幕</label>
      ${tracks.length > 1 ? `<select id="subtrack">${tracks.map(t => `<option value="${t.url}" ${t === cur ? "selected" : ""}>${esc(t.label)}</option>`).join("")}</select>` : ""}
      <label style="color:var(--dim);font-size:14px"><input type="checkbox" id="subai" ${st.aiSubEnabled ? "checked" : ""}> ${zhTrack ? "自带中文" : "AI双语"}</label>`;
    let aiOn = $("#subai").checked, aiGen = 0;
    const applySub = (url) => { try { art.subtitle.switch(url); } catch (e) {} };
    const showSub = (on) => {
      try { art.subtitle.show = on; } catch (e) {}
      const el = document.querySelector(".art-subtitle"); if (el) el.style.display = on ? "" : "none";
    };
    const setHint = (s) => { $("#subhint").textContent = s; };
    showSub(true);
    subarea.insertAdjacentHTML("beforeend", `<span id="subhint" style="color:var(--dim);font-size:13px">字幕: ${esc(cur.label)}</span>`);

    // 一次性签名失效时自动重新解析拿新地址（k 参数保证翻译缓存不失效）
    const refreshTrackUrl = async (track) => {
      try {
        const v2 = await api("/api/video?url=" + encodeURIComponent(p.epUrl) + "&source=" + p.source +
          "&title=" + encodeURIComponent(p.title || "") + "&ep=" + encodeURIComponent(p.epName || ""));
        const t2 = (v2.subtitles || []).find(x => x.label === track.label && x.lang === track.lang);
        if (t2 && t2.url !== track.url) {
          track.url = t2.url;
          const sel = document.querySelector("#subtrack");
          if (sel) { sel.value = t2.url; }
          return true;
        }
      } catch (e) {}
      return false;
    };
    const loadTrack = async (track, bilingual) => {
      const suffix = (bilingual && !zhTrack) ? "&translate=1" : "";
      let res = await fetch(track.url + suffix);
      if (!res.ok) {
        setHint("字幕地址过期，重新解析…");
        const refreshed = await refreshTrackUrl(track);
        res = await fetch(track.url + suffix);
        if (!res.ok) {
          setHint(refreshed
            ? "源站未提供「" + track.label + "」字幕（通常只有英文轨可用），已保留原字幕"
            : "字幕加载失败" + (bilingual && !zhTrack ? "（缓存里也没有双语字幕）" : ""));
          return false;
        }
      }
      applySub(track.url + suffix);  // 只在拿到 200 时才切换，避免把失败地址丢给播放器
      return true;
    };
    const subKey = (track) => {
      const q = new URLSearchParams(track.url.split("?")[1]);
      return "u=" + (q.get("u") || "") + (q.get("k") ? "&k=" + q.get("k") : "");
    };
    $("#subon").onchange = (e) => showSub(e.target.checked);
    const trSel = $("#subtrack");
    if (trSel) trSel.onchange = async (e) => {
      cur = tracks.find(t => t.url === e.target.value) || cur;
      setHint("字幕: " + cur.label);
      if (aiOn && !zhTrack) await startAI();
      else await loadTrack(cur, false);
    };
    $("#subai").onchange = async (e) => {
      aiOn = e.target.checked;
      if (aiOn && zhTrack) {
        cur = zhTrack;
        if (trSel) trSel.value = zhTrack.url;
        await loadTrack(zhTrack, false);
        setHint("自带中文字幕 ✓（无需 AI 翻译）");
      } else if (aiOn) await startAI();
      else { aiGen++; await loadTrack(cur, false); setHint("字幕: " + cur.label); }
    };
    async function startAI() {
      const gen = ++aiGen;   // 换轨/关闭时旧轮询自动作废
      if (!st.llmBaseUrl || !st.llmModel) {
        setHint("AI双语: 未配置 LLM（设置页填写）");
        $("#subai").checked = false; aiOn = false; return;
      }
      try {
        await api("/api/subtitle/prepare?" + subKey(cur));
        let idleRounds = 0;
        while (true) {
          if (gen !== aiGen) return;
          const prog = await api("/api/subtitle/progress?" + subKey(cur));
          if (gen !== aiGen) return;
          if (prog.status === "done") {
            const ok = await loadTrack(cur, true);
            setHint(ok ? "AI双语 ✓ 原文+中文" : "AI双语已就绪，字幕加载失败，请重试");
            break;
          }
          if (prog.status === "error") { setHint("AI双语失败: " + (prog.error || "")); $("#subai").checked = false; aiOn = false; break; }
          if (prog.status === "running") {
            idleRounds = 0;
            setHint(prog.total > 0 ? `AI双语: 翻译中 ${prog.done}/${prog.total} 批` : "AI双语: 翻译中…");
          } else if (++idleRounds > 24) {   // 约 60s 毫无进展就别死等了
            setHint("AI双语: 等待超时，可稍后重试");
            $("#subai").checked = false; aiOn = false; break;
          }
          await new Promise(r => setTimeout(r, 2500));
        }
      } catch (e) { setHint("AI双语失败: " + e.message); }
    }
    if (aiOn) {
      if (zhTrack) { cur = zhTrack; loadTrack(zhTrack, false).then(() => setHint("自带中文字幕 ✓（无需 AI 翻译）")); }
      else startAI();
    }
  } else {
    subarea.innerHTML = '<span id="subhint" style="color:var(--dim);font-size:13px">该源无字幕轨（字幕由 HiAnime 源提供）</span>';
  }
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
      <label>出站代理（可选，如 http://127.0.0.1:10800 或 socks://127.0.0.1:10808，留空直连）</label>
      <input type="text" id="s-proxy" value="${raw(s.outboundProxy || "")}">
      <h2 class="sect">AI 双语字幕（OpenAI 兼容接口）</h2>
      <label><input type="checkbox" id="s-aisub" ${s.aiSubEnabled ? "checked" : ""}> 播放时自动翻译为双语字幕（原文+中文）</label>
      <label>字幕字号（px）</label>
      <input type="text" id="s-subsize" value="${raw(s.subFontSize || "22")}">
      <label>字幕背景</label>
      <select id="s-subbg">
        <option value="shadow" ${s.subBg !== "bar" && s.subBg !== "outline" ? "selected" : ""}>纯阴影（不遮挡画面，推荐）</option>
        <option value="bar" ${s.subBg === "bar" ? "selected" : ""}>半透明底条</option>
        <option value="outline" ${s.subBg === "outline" ? "selected" : ""}>黑色描边</option>
      </select>
      <label>API Base（如 https://api.deepseek.com/v1 或 http://127.0.0.1:8080/v1）</label>
      <input type="text" id="s-llmurl" value="${raw(s.llmBaseUrl || "")}">
      <label>API Key</label>
      <input type="password" id="s-llmkey" value="${raw(s.llmApiKey || "")}">
      <label>模型名（如 deepseek-chat / glm-4-flash / qwen3-4b）</label>
      <input type="text" id="s-llmmodel" value="${raw(s.llmModel || "")}">
      <div style="margin-top:12px" class="row">
        <button class="btn" id="s-save">保存</button>
        <button class="btn ghost" id="s-test">测试 LLM 连接</button>
        <span id="s-testres" style="color:var(--dim);font-size:13px"></span>
      </div>
      <h2 class="sect">字幕缓存</h2>
      <p class="tip" id="s-subcache">统计中…</p>
      <button class="btn ghost" id="s-subclear">清空双语字幕缓存</button>
      <p class="tip">弹弹play 申请地址：https://api.dandanplay.net/register<br>
      字幕翻译走 OpenAI 兼容接口（chat/completions），本地 llama.cpp/ollama 也可以；字幕按集缓存，翻过的集秒开。</p>
    </div>`;
  $("#s-save").onclick = async () => {
    await api("/api/settings", { method: "POST", body: JSON.stringify({
      ddpAppId: $("#s-appid").value, ddpSecret: $("#s-secret").value,
      outboundProxy: $("#s-proxy").value,
      llmBaseUrl: $("#s-llmurl").value, llmApiKey: $("#s-llmkey").value,
      llmModel: $("#s-llmmodel").value, aiSubEnabled: $("#s-aisub").checked ? "true" : "false", subFontSize: $("#s-subsize").value, subBg: $("#s-subbg").value }) });
    $("#s-save").textContent = "已保存 ✓（代理需 anime restart）";
    setTimeout(() => $("#s-save").textContent = "保存", 2500);
  };
  api("/api/subtitle/cache").then(c => {
    $("#s-subcache").textContent = `已缓存 ${c.count} 集双语字幕，共 ${(c.bytes / 1048576).toFixed(1)} MB`;
  }).catch(() => {});
  $("#s-subclear").onclick = async () => {
    const r = await api("/api/subtitle/cache/clear", { method: "POST" });
    $("#s-subcache").textContent = `已清空（移除 ${r.removed} 个文件）`;
  };
  $("#s-test").onclick = async () => {
    await api("/api/settings", { method: "POST", body: JSON.stringify({
      llmBaseUrl: $("#s-llmurl").value, llmApiKey: $("#s-llmkey").value, llmModel: $("#s-llmmodel").value }) });
    $("#s-testres").textContent = "测试中…";
    try {
      const r = await api("/api/subtitle/test");
      $("#s-testres").textContent = r.ok ? "✓ 连通，模型回复: " + r.reply : "✗ " + (r.error || "失败");
      $("#s-testres").style.color = r.ok ? "#7ee787" : "var(--acc2)";
    } catch (e) { $("#s-testres").textContent = "✗ 请求失败"; }
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
