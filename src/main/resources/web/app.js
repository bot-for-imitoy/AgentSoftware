/* AgentCompany Web 界面 — 分组聊天 + 甲方对话 */
"use strict";

const LEADERSHIP_KEY = "Leadership Group";
const CLIENT_NAME = "甲方";

const $ = (id) => document.getElementById(id);

const AVATAR_COLORS = [
  "#12b7f5", "#5b8ff9", "#61c0a8", "#f6bd16", "#e8684a",
  "#6dc8ec", "#9270ca", "#ff9d4d", "#269a99", "#ff99c3",
];

const state = {
  groups: [],
  selectedKey: LEADERSHIP_KEY,
  lastSeq: 0,
  renderedSeqs: new Set(),
  messagesByGroup: new Map(), // groupKey -> [{...msg}]
  clientTalk: { active: false, holderName: null, holderRoleId: null },
  prevClientActive: false,
  inputEnabled: false,
  day: 1,
  tick: 0,
  describe: "",
};

let toastTimer = null;

// ── 工具函数 ───────────────────────────────

function avatarColor(key) {
  let h = 0;
  for (let i = 0; i < key.length; i++) h = (h * 31 + key.charCodeAt(i)) >>> 0;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}

function avatarChar(name) {
  return (name || "?").trim().charAt(0) || "?";
}

function fmtTime(ts) {
  const d = new Date(ts);
  const p = (n) => String(n).padStart(2, "0");
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

function toast(text) {
  const el = $("toast");
  el.textContent = text;
  el.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove("show"), 3000);
}

async function fetchJson(url, opts) {
  const r = await fetch(url, opts);
  let body;
  try { body = await r.json(); } catch { body = {}; }
  return { status: r.status, body };
}

function canInput() {
  return state.selectedKey === LEADERSHIP_KEY && state.clientTalk.active;
}

// ── 分组面板 ───────────────────────────────

function renderGroups() {
  const ul = $("groupList");
  ul.innerHTML = "";
  for (const g of state.groups) {
    const li = document.createElement("li");
    li.className = "group-item" + (g.key === state.selectedKey ? " active" : "");
    li.dataset.key = g.key;

    const avatar = document.createElement("div");
    avatar.className = "group-avatar";
    avatar.style.background = avatarColor(g.key || "未分组");
    avatar.textContent = (g.label || "?").charAt(0);

    const info = document.createElement("div");
    info.className = "group-info";
    const name = document.createElement("div");
    name.className = "group-name";
    name.textContent = g.label || "未分组";
    const meta = document.createElement("div");
    meta.className = "group-meta";
    meta.textContent = `${g.members.length} 名成员`;
    info.append(name, meta);

    const badge = document.createElement("span");
    badge.className = "unread-badge";
    badge.textContent = "0";

    li.append(avatar, info, badge);
    li.addEventListener("click", () => selectGroup(g.key));
    ul.appendChild(li);
  }
}

function updateGroupItemVisuals() {
  const items = document.querySelectorAll(".group-item");
  for (const li of items) {
    const key = li.dataset.key;
    li.classList.toggle("active", key === state.selectedKey);
    const unread = unreadCount(key);
    li.classList.toggle("has-unread", unread > 0);
    const badge = li.querySelector(".unread-badge");
    if (badge) badge.textContent = unread > 99 ? "99+" : String(unread);
    const talking = key === LEADERSHIP_KEY && state.clientTalk.active;
    li.classList.toggle("talking", talking);
  }
}

function selectGroup(key) {
  state.selectedKey = key;
  state.renderedSeqs.clear();
  renderGroups();
  renderHeader();
  renderMessages();
  applyInputState();
}

function unreadCount(key) {
  if (key === state.selectedKey) return 0;
  const list = state.messagesByGroup.get(key) || [];
  let n = 0;
  for (const m of list) if (m.seq && !state.renderedSeqs.has(m.seq)) n++;
  return n;
}

// ── 聊天头部 ───────────────────────────────

function currentGroup() {
  return state.groups.find((g) => g.key === state.selectedKey) || null;
}

function renderHeader() {
  const g = currentGroup();
  if (!g) {
    $("chatTitle").textContent = "请选择左侧分组";
    $("chatMembers").textContent = "";
    return;
  }
  $("chatTitle").textContent = g.label || "未分组";
  const names = g.members.map((m) => m.name).join("、");
  $("chatMembers").textContent = `${g.members.length} 名成员：${names}`;
}

// ── 消息渲染 ───────────────────────────────

function groupOf(msg) {
  return msg.group || "";
}

function pushMessage(msg) {
  const key = groupOf(msg);
  if (!state.messagesByGroup.has(key)) state.messagesByGroup.set(key, []);
  const list = state.messagesByGroup.get(key);
  if (msg.seq) {
    const seen = list.some((m) => m.seq === msg.seq);
    if (seen) return;
  }
  list.push(msg);
  list.sort((a, b) => (a.seq || 0) - (b.seq || 0));
}

function renderMessages() {
  const listEl = $("messageList");
  listEl.innerHTML = "";
  const list = state.messagesByGroup.get(state.selectedKey) || [];
  if (list.length === 0) {
    const tip = document.createElement("div");
    tip.className = "empty-tip";
    tip.textContent = "暂无消息 — 选择左侧分组查看该组成员之间的聊天";
    listEl.appendChild(tip);
    return;
  }
  const nearBottom = isNearBottom(listEl);
  const frag = document.createDocumentFragment();
  for (const m of list) {
    if (m.seq) state.renderedSeqs.add(m.seq);
    frag.appendChild(buildMessageEl(m));
  }
  listEl.appendChild(frag);
  if (nearBottom || !autoScrolledOnce) scrollToBottom(listEl);
}

let autoScrolledOnce = false;

function buildMessageEl(m) {
  const isClient = m.fromName === CLIENT_NAME || m.kind === "client" && m.fromRoleId === "";
  const div = document.createElement("div");
  div.className = "msg" + (isClient ? " from-client" : "");

  const avatar = document.createElement("div");
  avatar.className = "msg-avatar";
  avatar.style.background = avatarColor(m.fromRoleId || m.fromName || "?");
  avatar.textContent = avatarChar(m.fromName);

  const body = document.createElement("div");
  body.className = "msg-body";

  const head = document.createElement("div");
  head.className = "msg-head";
  const name = document.createElement("span");
  name.className = "msg-name";
  name.textContent = m.fromName || "未知";
  const time = document.createElement("span");
  time.textContent = fmtTime(m.ts);
  head.append(name, time);

  const bubble = document.createElement("div");
  bubble.className = "msg-bubble";
  bubble.textContent = m.text;
  if (m.urgency && m.urgency !== "NORMAL") {
    const badge = document.createElement("span");
    badge.className = "msg-urgency " + m.urgency;
    badge.textContent = m.urgency;
    bubble.appendChild(badge);
  }

  body.append(head, bubble);
  div.append(avatar, body);
  return div;
}

function appendNewMessages(list) {
  const listEl = $("messageList");
  const nearBottom = isNearBottom(listEl);
  const frag = document.createDocumentFragment();
  for (const m of list) {
    if (m.seq) state.renderedSeqs.add(m.seq);
    frag.appendChild(buildMessageEl(m));
  }
  listEl.appendChild(frag);
  if (nearBottom) scrollToBottom(listEl);
}

function isNearBottom(el) {
  return el.scrollHeight - el.scrollTop - el.clientHeight < 80;
}

function scrollToBottom(el) {
  el.scrollTop = el.scrollHeight;
}

// ── 输入框状态 ─────────────────────────────

function applyInputState() {
  const enabled = canInput();
  const wasEnabled = state.inputEnabled;
  state.inputEnabled = enabled;
  $("replyInput").disabled = !enabled;
  $("sendBtn").disabled = !enabled;
  $("talkBanner").classList.toggle("hidden", !enabled);
  $("inputHint").classList.toggle("enabled", enabled);
  if (enabled) {
    const who = state.clientTalk.holderName || "成员";
    $("talkBannerText").textContent = `${who} 正在与您（甲方）对话 — 请在下方输入您的回复`;
    $("replyInput").placeholder = "请输入您的回复…";
    $("inputHint").textContent = "输入框已启用";
    if (!wasEnabled) $("replyInput").focus();
  } else {
    $("replyInput").placeholder = "输入框已禁用 — 仅当「领导组」成员与您对话时启用";
    $("inputHint").textContent = "已禁用";
  }
}

// ── 轮询 ───────────────────────────────────

async function pollState() {
  const { body } = await fetchJson("/api/state");
  if (!body.ok) return;
  state.day = body.day;
  state.tick = body.tick;
  state.describe = body.describe || "";
  $("sysInfo").textContent = `第 ${body.day} 天 · Tick ${body.tick} · ${state.describe}`;

  const ct = body.clientTalk || { active: false };
  state.clientTalk = ct;

  // 分组花名册变化时重建侧栏
  const groupsJson = JSON.stringify(body.groups || []);
  if (groupsJson !== JSON.stringify(state.groups)) {
    state.groups = body.groups || [];
    if (!state.groups.some((g) => g.key === state.selectedKey)) {
      state.selectedKey = state.groups.length ? state.groups[0].key : null;
      state.renderedSeqs.clear();
      renderMessages();
    }
    renderGroups();
    renderHeader();
  }

  // 甲方对话激活: 若用户不在领导组, 自动切换过去并提示
  if (ct.active && !state.prevClientActive) {
    if (state.selectedKey !== LEADERSHIP_KEY) {
      toast("领导组有成员正在与您对话，已自动切换到「领导组」");
      selectGroup(LEADERSHIP_KEY);
    }
  }
  state.prevClientActive = ct.active;

  updateGroupItemVisuals();
  applyInputState();
}

async function pollMessages() {
  const { body } = await fetchJson(`/api/messages?since=${state.lastSeq}`);
  if (!body.ok) return;
  state.lastSeq = body.lastSeq || state.lastSeq;
  const msgs = body.messages || [];
  if (msgs.length === 0) return;
  const newOnes = [];
  for (const m of msgs) {
    const key = groupOf(m);
    const list = state.messagesByGroup.get(key) || [];
    if (m.seq && list.some((x) => x.seq === m.seq)) continue;
    if (state.renderedSeqs.has(m.seq)) continue;
    pushMessage(m);
    if (key === state.selectedKey) newOnes.push(m);
  }
  if (newOnes.length > 0) appendNewMessages(newOnes);
  updateGroupItemVisuals();
}

// ── 发送回复 ───────────────────────────────

async function sendReply() {
  if (!canInput()) return;
  const input = $("replyInput");
  const text = input.value.trim();
  if (!text) return;
  const { status, body } = await fetchJson("/api/reply", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ text }),
  });
  if (status === 200 && body.ok && body.message) {
    pushMessage(body.message);
    input.value = "";
    const listEl = $("messageList");
    const nearBottom = isNearBottom(listEl);
    const frag = document.createDocumentFragment();
    if (body.message.seq) state.renderedSeqs.add(body.message.seq);
    frag.appendChild(buildMessageEl(body.message));
    listEl.appendChild(frag);
    if (nearBottom) scrollToBottom(listEl);
  } else {
    toast(body.reason || "发送失败，请重试");
  }
  await pollState(); // 刷新输入框状态 (回复后通常立即禁用)
}

// ── 初始化 ─────────────────────────────────

function init() {
  $("sendBtn").addEventListener("click", sendReply);
  $("replyInput").addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      sendReply();
    }
  });
  pollState().then(() => {
    if (!state.groups.length) return;
    if (!state.groups.some((g) => g.key === state.selectedKey)) {
      state.selectedKey = state.groups[0].key;
    }
    renderMessages();
    renderHeader();
  });
  setInterval(pollState, 2000);
  setInterval(pollMessages, 1500);
}

init();
