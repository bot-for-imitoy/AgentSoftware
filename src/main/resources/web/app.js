/* AgentSoftware Web UI - group chat + live agent activity (thinking / tool calls / final output) */
"use strict";

const LEADERSHIP_KEY = "Leadership Group";
const ALL_KEY = "*";                 // pseudo channel: every message across all groups, in seq order
const ALL_LABEL = "All Activity";    // display label of the pseudo channel
const CLIENT_NAME = "Client A";
const MAX_RENDER = 400;              // max message rows kept in the DOM per channel

const $ = (id) => document.getElementById(id);

const AVATAR_COLORS = [
  "#12b7f5", "#5b8ff9", "#61c0a8", "#f6bd16", "#e8684a",
  "#6dc8ec", "#9270ca", "#ff9d4d", "#269a99", "#ff99c3",
];

const state = {
  groups: [],
  selectedKey: ALL_KEY,
  lastSeq: 0,
  renderedSeqs: new Set(),
  messagesByGroup: new Map(), // groupKey -> [{...msg}]
  messagesAll: [],            // every message, ascending by seq (All Activity feed)
  clientTalk: { active: false, holderName: null, holderRoleId: null },
  prevClientActive: false,
  inputEnabled: false,
  day: 1,
  tick: 0,
  describe: "",
};

let toastTimer = null;

// ── Utility functions ───────────────────────────────

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

function extraOf(m) {
  return m.extra && typeof m.extra === "object" ? m.extra : {};
}

// ── Group panel (All Activity pseudo channel on top) ───────

function allUnread() {
  if (state.selectedKey === ALL_KEY) return 0;
  let n = 0;
  for (const m of state.messagesAll) {
    if (m.seq && !state.renderedSeqs.has(m.seq)) n++;
  }
  return n;
}

function renderGroups() {
  const ul = $("groupList");
  ul.innerHTML = "";

  // Pseudo channel: all live activity (thinking / tools / answers / chats of every group)
  const allLi = document.createElement("li");
  allLi.className = "group-item all-item" + (ALL_KEY === state.selectedKey ? " active" : "");
  allLi.dataset.key = ALL_KEY;
  const allAvatar = document.createElement("div");
  allAvatar.className = "group-avatar";
  allAvatar.style.background = "linear-gradient(135deg,#667eea,#764ba2)";
  allAvatar.textContent = "◉";
  const allInfo = document.createElement("div");
  allInfo.className = "group-info";
  const allName = document.createElement("div");
  allName.className = "group-name";
  allName.textContent = ALL_LABEL;
  const allMeta = document.createElement("div");
  allMeta.className = "group-meta";
  allMeta.textContent = "live feed: thinking · tool calls · output";
  allInfo.append(allName, allMeta);
  const allBadge = document.createElement("span");
  allBadge.className = "unread-badge";
  allBadge.textContent = "0";
  allLi.append(allAvatar, allInfo, allBadge);
  allLi.addEventListener("click", () => selectGroup(ALL_KEY));
  ul.appendChild(allLi);

  // Real groups from the roster
  for (const g of state.groups) {
    const li = document.createElement("li");
    li.className = "group-item" + (g.key === state.selectedKey ? " active" : "");
    li.dataset.key = g.key;

    const avatar = document.createElement("div");
    avatar.className = "group-avatar";
    avatar.style.background = avatarColor(g.key || "Unassigned");
    avatar.textContent = (g.label || "?").charAt(0);

    const info = document.createElement("div");
    info.className = "group-info";
    const name = document.createElement("div");
    name.className = "group-name";
    name.textContent = g.label || "Unassigned";
    const meta = document.createElement("div");
    meta.className = "group-meta";
    meta.textContent = `${g.members.length} members`;
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
  if (key === ALL_KEY) return allUnread();
  if (key === state.selectedKey) return 0;
  const list = state.messagesByGroup.get(key) || [];
  let n = 0;
  for (const m of list) if (m.seq && !state.renderedSeqs.has(m.seq)) n++;
  return n;
}

// ── Chat header ───────────────────────────────

function currentGroup() {
  if (state.selectedKey === ALL_KEY) {
    return { key: ALL_KEY, label: ALL_LABEL, members: [] };
  }
  return state.groups.find((g) => g.key === state.selectedKey) || null;
}

function renderHeader() {
  const g = currentGroup();
  if (!g) {
    $("chatTitle").textContent = "Select a group on the left";
    $("chatMembers").textContent = "";
    return;
  }
  $("chatTitle").textContent = g.label || "Unassigned";
  if (state.selectedKey === ALL_KEY) {
    $("chatMembers").textContent =
      "Live activity of every role across all groups — chain of thought (🧠), tool calls (🛠) and final outputs";
  } else {
    const names = g.members.map((m) => m.name).join(", ");
    $("chatMembers").textContent = `${g.members.length} members: ${names}`;
  }
}

// ── Message storage & rendering ───────────────────────────────

function groupOf(msg) {
  return msg.group || "";
}

function pushMessage(msg) {
  // per-group list
  const key = groupOf(msg);
  if (!state.messagesByGroup.has(key)) state.messagesByGroup.set(key, []);
  const list = state.messagesByGroup.get(key);
  if (msg.seq) {
    const seen = list.some((m) => m.seq === msg.seq);
    if (!seen) {
      list.push(msg);
      list.sort((a, b) => (a.seq || 0) - (b.seq || 0));
    }
  }
  // global All Activity feed
  if (msg.seq) {
    const seen = state.messagesAll.some((m) => m.seq === msg.seq);
    if (!seen) {
      state.messagesAll.push(msg);
      state.messagesAll.sort((a, b) => (a.seq || 0) - (b.seq || 0));
    }
  }
}

function messagesFor(key) {
  if (key === ALL_KEY) return state.messagesAll;
  return state.messagesByGroup.get(key) || [];
}

function renderMessages() {
  const listEl = $("messageList");
  listEl.innerHTML = "";
  let list = messagesFor(state.selectedKey);
  if (list.length > MAX_RENDER) list = list.slice(list.length - MAX_RENDER);
  if (list.length === 0) {
    const tip = document.createElement("div");
    tip.className = "empty-tip";
    tip.textContent = state.selectedKey === ALL_KEY
      ? "No activity yet — role thoughts, tool calls and outputs will appear here in real time"
      : "No messages yet — select a group on the left to see that group's chat";
    listEl.appendChild(tip);
    return;
  }
  const nearBottom = isNearBottom(listEl);
  const frag = document.createDocumentFragment();
  const showGroup = state.selectedKey === ALL_KEY;
  for (const m of list) {
    if (m.seq) state.renderedSeqs.add(m.seq);
    frag.appendChild(buildMessageEl(m, showGroup));
  }
  listEl.appendChild(frag);
  if (nearBottom || !autoScrolledOnce) scrollToBottom(listEl);
}

let autoScrolledOnce = false;

function appendNewMessages(list) {
  const listEl = $("messageList");
  const nearBottom = isNearBottom(listEl);
  const frag = document.createDocumentFragment();
  const showGroup = state.selectedKey === ALL_KEY;
  for (const m of list) {
    if (m.seq) state.renderedSeqs.add(m.seq);
    frag.appendChild(buildMessageEl(m, showGroup));
  }
  listEl.appendChild(frag);
  // keep the DOM bounded (drop the oldest rows beyond MAX_RENDER)
  while (listEl.children.length > MAX_RENDER) {
    listEl.removeChild(listEl.firstChild);
  }
  if (nearBottom) scrollToBottom(listEl);
}

// ── Message element builders (kind-aware) ─────────────────────

function headRow(m, showGroup, chipText) {
  const head = document.createElement("div");
  head.className = "msg-head";
  const name = document.createElement("span");
  name.className = "msg-name";
  name.textContent = m.fromName || "Unknown";
  head.appendChild(name);
  if (showGroup && m.group) {
    const g = document.createElement("span");
    g.className = "group-chip";
    g.textContent = m.group;
    g.title = m.group;
    head.appendChild(g);
  }
  if (chipText) {
    const chip = document.createElement("span");
    chip.className = "kind-chip";
    chip.textContent = chipText;
    head.appendChild(chip);
  }
  const time = document.createElement("span");
  time.textContent = fmtTime(m.ts);
  head.appendChild(time);
  return head;
}

function rowShell(m, cls) {
  const div = document.createElement("div");
  div.className = "msg trace " + cls;
  const avatar = document.createElement("div");
  avatar.className = "msg-avatar";
  avatar.style.background = avatarColor(m.fromRoleId || m.fromName || "?");
  avatar.textContent = avatarChar(m.fromName);
  const body = document.createElement("div");
  body.className = "msg-body";
  div.append(avatar, body);
  return { div, body };
}

/** Chain of thought (reasoning_content) / mid-round narration. */
function buildThinkEl(m, showGroup, kind) {
  const label = kind === "note" ? "note · narration" : "thinking · chain of thought";
  const { div, body } = rowShell(m, "kind-" + kind);
  const head = headRow(m, showGroup, (kind === "note" ? "✎ " : "🧠 ") + label);
  const box = document.createElement("div");
  box.className = kind === "note" ? "trace-box think-box note-box" : "trace-box think-box";
  box.textContent = m.text;
  if (m.extra && m.extra.round != null) box.dataset.round = String(m.extra.round);
  body.append(head, box);
  return div;
}

/** One tool invocation: name + arguments + result. */
function buildToolEl(m, showGroup) {
  const ex = extraOf(m);
  const { div, body } = rowShell(m, "kind-tool");
  const head = headRow(m, showGroup, "🛠 tool call");
  const card = document.createElement("div");
  card.className = "trace-card tool-card";

  const title = document.createElement("div");
  title.className = "tool-title";
  const fn = document.createElement("span");
  fn.className = "tool-name";
  fn.textContent = ex.tool || m.text || "tool";
  const argsHint = document.createElement("span");
  argsHint.className = "tool-args-hint";
  argsHint.textContent = "arguments";
  title.append(fn, argsHint);
  card.appendChild(title);

  const argsPre = document.createElement("pre");
  argsPre.className = "tool-args";
  argsPre.textContent = prettyJson(ex.args);
  card.appendChild(argsPre);

  if (ex.result !== undefined && ex.result !== null && ex.result !== "") {
    const resTitle = document.createElement("div");
    resTitle.className = "tool-res-title";
    resTitle.textContent = "result";
    const resPre = document.createElement("pre");
    resPre.className = "tool-result";
    resPre.textContent = String(ex.result);
    if (/^(error|tool error|fail)/i.test(String(ex.result).trim())) {
      resPre.classList.add("error-text");
    }
    card.append(resTitle, resPre);
  }
  body.append(head, card);
  return div;
}

/** Final output (task result): normal bubble-like card, prominent. */
function buildAnswerEl(m, showGroup) {
  const ex = extraOf(m);
  const failed = ex.status === "failed" || String(m.text || "").startsWith("[ERROR]");
  const { div, body } = rowShell(m, "kind-answer" + (failed ? " failed" : ""));
  const head = headRow(m, showGroup,
    failed ? "✗ final output · failed" : "✔ final output");
  const box = document.createElement("div");
  box.className = "trace-box answer-box";
  box.textContent = m.text;
  if (ex.status) box.dataset.status = String(ex.status);
  body.append(head, box);
  if (ex.tokens != null) {
    const meta = document.createElement("div");
    meta.className = "answer-meta";
    meta.textContent = (ex.taskId ? "task " + ex.taskId : "") + (ex.tokens ? " · " + ex.tokens + " tokens" : "");
    body.appendChild(meta);
  }
  return div;
}

function prettyJson(v) {
  if (v === undefined || v === null || v === "") return "{}";
  const s = String(v);
  try {
    return JSON.stringify(JSON.parse(s), null, 2);
  } catch {
    return s;
  }
}

function buildMessageEl(m, showGroup) {
  // trace kinds: reason / note / tool / answer
  if (m.kind === "reason") return buildThinkEl(m, showGroup, "reason");
  if (m.kind === "note") return buildThinkEl(m, showGroup, "note");
  if (m.kind === "tool") return buildToolEl(m, showGroup);
  if (m.kind === "answer") return buildAnswerEl(m, showGroup);

  // chat kinds: talk / client (regular bubbles)
  const isClient = m.fromName === CLIENT_NAME || (m.kind === "client" && m.fromRoleId === "");
  const div = document.createElement("div");
  div.className = "msg" + (isClient ? " from-client" : "");

  const avatar = document.createElement("div");
  avatar.className = "msg-avatar";
  avatar.style.background = avatarColor(m.fromRoleId || m.fromName || "?");
  avatar.textContent = avatarChar(m.fromName);

  const body = document.createElement("div");
  body.className = "msg-body";

  const head = headRow(m, showGroup, null);

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

function isNearBottom(el) {
  return el.scrollHeight - el.scrollTop - el.clientHeight < 80;
}

function scrollToBottom(el) {
  el.scrollTop = el.scrollHeight;
}

// ── Input state ─────────────────────────────

function applyInputState() {
  const enabled = canInput();
  const wasEnabled = state.inputEnabled;
  state.inputEnabled = enabled;
  $("replyInput").disabled = !enabled;
  $("sendBtn").disabled = !enabled;
  $("talkBanner").classList.toggle("hidden", !enabled);
  $("inputHint").classList.toggle("enabled", enabled);
  if (enabled) {
    const who = state.clientTalk.holderName || "member";
    $("talkBannerText").textContent = `${who} is talking to you (Client A) — type your reply below`;
    $("replyInput").placeholder = "Type your reply…";
    $("inputHint").textContent = "Input enabled";
    if (!wasEnabled) $("replyInput").focus();
  } else {
    $("replyInput").placeholder = "Input disabled — enabled only when a Leadership Group member is talking to you";
    $("inputHint").textContent = "Disabled";
  }
}

// ── Polling ───────────────────────────────────

async function pollState() {
  const { body } = await fetchJson("/api/state");
  if (!body.ok) return;
  state.day = body.day;
  state.tick = body.tick;
  state.describe = body.describe || "";
  $("sysInfo").textContent = `Day ${body.day} · Tick ${body.tick} · ${state.describe}`;

  const ct = body.clientTalk || { active: false };
  state.clientTalk = ct;

  // Rebuild the sidebar when the group roster changes
  const groupsJson = JSON.stringify(body.groups || []);
  if (groupsJson !== JSON.stringify(state.groups)) {
    state.groups = body.groups || [];
    if (state.selectedKey !== ALL_KEY && !state.groups.some((g) => g.key === state.selectedKey)) {
      state.selectedKey = state.groups.length ? state.groups[0].key : ALL_KEY;
      state.renderedSeqs.clear();
      renderMessages();
    }
    renderGroups();
    renderHeader();
  }

  // Client talk activated: if the user is not in the Leadership Group, switch over automatically and notify
  if (ct.active && !state.prevClientActive) {
    if (state.selectedKey !== LEADERSHIP_KEY) {
      toast("A Leadership Group member is talking to you; switched to the Leadership Group automatically");
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
    if (state.selectedKey === ALL_KEY || key === state.selectedKey) newOnes.push(m);
  }
  if (newOnes.length > 0) appendNewMessages(newOnes);
  updateGroupItemVisuals();
}

// ── Send reply ───────────────────────────────

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
    if (state.selectedKey === ALL_KEY || groupOf(body.message) === state.selectedKey) {
      const frag = document.createDocumentFragment();
      if (body.message.seq) state.renderedSeqs.add(body.message.seq);
      frag.appendChild(buildMessageEl(body.message, state.selectedKey === ALL_KEY));
      listEl.appendChild(frag);
      if (nearBottom) scrollToBottom(listEl);
    }
  } else {
    toast(body.reason || "Failed to send, please try again");
  }
  await pollState(); // Refresh input state (usually disabled immediately after replying)
}

// ── Initialization ─────────────────────────────────

function init() {
  $("sendBtn").addEventListener("click", sendReply);
  $("replyInput").addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      sendReply();
    }
  });
  pollState().then(() => {
    if (!state.groups.length && state.selectedKey === ALL_KEY) {
      // roster may be empty; All Activity still works
    }
    renderMessages();
    renderHeader();
  });
  setInterval(pollState, 2000);
  setInterval(pollMessages, 1500);
}

init();
