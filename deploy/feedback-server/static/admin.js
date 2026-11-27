// Served at /admin/app.js. Kept in its own file so the admin page can run under a strict
// `script-src 'self'` policy (see nginx-raven-feedback.conf) instead of a brittle inline hash.
// Every ticket field is user supplied: it only ever reaches the DOM through textContent.
const $ = (id) => document.getElementById(id);
let selected = null;

function el(tag, text, className) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined && text !== null) node.textContent = String(text);
  return node;
}

function table(rows) {
  const wrap = document.createElement("div");
  wrap.className = "toolbar";
  for (const row of rows) wrap.append(row);
  return wrap;
}

async function api(path, options = {}) {
  const response = await fetch(path, { credentials: "same-origin", headers: { "Content-Type": "application/json" }, ...options });
  if (response.status === 401) { showLogin(); throw new Error("unauthorized"); }
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || "请求失败");
  return data;
}

function showLogin() { $("login").classList.remove("hidden"); $("app").classList.add("hidden"); }
function showApp() { $("login").classList.add("hidden"); $("app").classList.remove("hidden"); }

async function boot() {
  try { await api("/api/admin/session"); showApp(); await refresh(); }
  catch { showLogin(); }
}

$("login").addEventListener("submit", async (event) => {
  event.preventDefault();
  $("loginError").textContent = "";
  try {
    await api("/api/admin/login", { method: "POST", body: JSON.stringify({ password: $("password").value }) });
    $("password").value = "";
    showApp();
    await refresh();
  } catch (error) { $("loginError").textContent = error.message === "unauthorized" ? "口令不正确" : error.message; }
});

$("logout").addEventListener("click", async () => { await api("/api/admin/logout", { method: "POST", body: "{}" }); showLogin(); });
$("exportHint").addEventListener("click", () => navigator.clipboard.writeText(".\\deploy\\pull-feedback.ps1"));
for (const id of ["status", "source", "query"]) $(id).addEventListener("input", refresh);

async function refresh() {
  const params = new URLSearchParams({ status: $("status").value, source: $("source").value, q: $("query").value });
  const data = await api("/api/admin/tickets?" + params);

  const stats = $("stats");
  stats.replaceChildren();
  for (const [name, count] of Object.entries(data.counts || {})) stats.append(el("span", `${name} ${count}`));

  const list = $("list");
  list.replaceChildren();
  for (const item of data.items) {
    const button = el("button", null, "card" + (item.id === selected ? " active" : ""));
    button.type = "button";
    button.append(
      el("strong", item.id),
      document.createElement("br"),
      el("span", item.title),
      document.createElement("br"),
      el("small", `${item.source} · ${item.status} · ${item.app_version || "无版本"}`),
    );
    button.addEventListener("click", () => openTicket(item.id));
    list.append(button);
  }
  if (!data.items.length) list.append(el("p", "没有匹配的反馈。", "muted"));
}

function selectField(label, id, values, current) {
  const field = el("label", label);
  const select = document.createElement("select");
  select.id = id;
  for (const value of values) {
    const option = el("option", value);
    option.value = value;
    option.selected = value === current;
    select.append(option);
  }
  field.append(select);
  return field;
}

async function openTicket(id) {
  selected = id;
  const data = await api("/api/admin/tickets/" + encodeURIComponent(id));
  const item = data.ticket;
  const detail = $("detail");
  detail.replaceChildren();

  detail.append(table([
    el("strong", item.id),
    el("span", item.source, "pill"),
    el("span", item.category, "pill"),
  ]));
  detail.append(el("h2", item.title));
  const body = el("p", item.body);
  body.style.whiteSpace = "pre-wrap";
  detail.append(body);
  const facts = [item.app_version, item.device_model, "Android " + (item.android_release || "未知"), item.contact || "未留联系方式"];
  detail.append(el("p", facts.filter(Boolean).join(" · "), "muted"));

  detail.append(selectField("状态", "editStatus", ["new", "triaged", "in_progress", "resolved", "wontfix", "duplicate"], item.status));
  detail.append(selectField("优先级", "editPriority", ["low", "normal", "high"], item.priority));
  const noteField = el("label", "维护备注");
  const note = document.createElement("textarea");
  note.id = "editNote";
  note.value = item.admin_note || "";
  noteField.append(note);
  detail.append(noteField);

  const save = el("button", "保存");
  save.type = "button";
  save.id = "save";
  save.addEventListener("click", saveTicket);
  const actions = table([save]);
  if (item.hasLog) {
    const link = el("a", "下载日志", "linkish");
    link.href = "/api/admin/tickets/" + encodeURIComponent(item.id) + "/log";
    actions.append(link);
  } else {
    actions.append(el("span", "无日志", "muted"));
  }
  detail.append(actions);

  const events = (data.events || []).map((event) => event.kind + " · " + event.detail).join("\n") || "无事件";
  detail.append(el("pre", events));
  await refresh();
}

async function saveTicket() {
  await api("/api/admin/tickets/" + encodeURIComponent(selected), {
    method: "POST",
    body: JSON.stringify({ status: $("editStatus").value, priority: $("editPriority").value, adminNote: $("editNote").value }),
  });
  await openTicket(selected);
}

boot();
