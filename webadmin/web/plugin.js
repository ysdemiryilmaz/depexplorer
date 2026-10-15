// web/plugin.tsx
var platform;
var React;
var BASE = "/extensions/depexplorer";
var PKG = "com.mirth.connect.plugins.depexplorer.";
function unwrap(v, cls) {
  if (v && typeof v === "object" && v[PKG + cls]) return v[PKG + cls];
  return v;
}
function list(v, key) {
  if (v == null || v === "") return [];
  const inner = typeof v === "object" && !Array.isArray(v) && key in v ? v[key] : v;
  if (inner == null || inner === "") return [];
  return Array.isArray(inner) ? inner : [inner];
}
function str(v) {
  return v == null ? "" : String(v);
}
function normRef(r) {
  return {
    ...r,
    step: r.step == null ? null : String(r.step),
    key: r.key == null ? null : String(r.key),
    ownerName: str(r.ownerName),
    line: Number(r.line || 0),
    connector: r.connector == null ? -1 : Number(r.connector),
    snippet: r.snippet == null ? "" : String(r.snippet)
  };
}
async function getOverview(refresh = false) {
  const o = unwrap(await platform.api.get(BASE + "/overview", { refresh: String(refresh) }), "DepOverview") || {};
  return {
    scannedAt: Number(o.scannedAt || 0),
    scanMillis: Number(o.scanMillis || 0),
    cacheKb: Number(o.cacheKb || 0),
    errors: Number(o.errors || 0),
    warnings: Number(o.warnings || 0),
    unused: Number(o.unused || 0),
    channels: list(o.channels, PKG + "DepNode").map((n) => ({
      ...n,
      name: str(n.name),
      enabled: n.enabled !== false && n.enabled !== "false",
      errors: Number(n.errors || 0),
      warnings: Number(n.warnings || 0),
      destinations: list(n.destinations, "string").map(str)
    })),
    edges: list(o.edges, PKG + "DepEdge").map((e) => ({
      ...e,
      enabled: e.enabled !== false && e.enabled !== "false",
      broken: e.broken === true || e.broken === "true",
      targetRef: str(e.targetRef),
      connector: Number(e.connector ?? -1)
    })),
    configKeys: list(o.configKeys, "string").map(str),
    missingConfigKeys: list(o.missingConfigKeys, "string").map(str),
    functions: list(o.functions, "string").map(str),
    mapVariables: list(o.mapVariables, "string").map(str)
  };
}
async function getReport() {
  const r = unwrap(await platform.api.get(BASE + "/report"), "DepReport") || {};
  return {
    problems: list(r.problems, PKG + "DepRef").map(normRef),
    unused: list(r.unused, PKG + "DepRef").map(normRef),
    truncated: r.truncated === true || r.truncated === "true"
  };
}
async function runQuery(type, key) {
  const r = unwrap(await platform.api.post(BASE + "/_query", { type, key }, { wrapKey: PKG + "DepQuery" }), "DepResult") || {};
  return {
    type: str(r.type),
    key: str(r.key),
    title: str(r.title),
    subtitle: str(r.subtitle),
    truncated: r.truncated === true || r.truncated === "true",
    rows: list(r.rows, PKG + "DepRef").map(normRef)
  };
}
function errText(e) {
  const m = e && (e.message || e.text || String(e));
  return m && !/^\[object/.test(m) ? m : "The request failed. See the server log.";
}
var C = {
  text: "var(--text)",
  dim: "var(--text-dim)",
  faint: "var(--text-faint)",
  line: "var(--line)",
  bg1: "var(--bg1)",
  bg2: "var(--bg2)",
  bg3: "var(--bg3)",
  accent: "var(--accent)",
  err: "var(--err)",
  warn: "var(--warn)",
  ok: "var(--ok)",
  violet: "var(--violet, #8b5cf6)",
  mono: "var(--font-mono, monospace)"
};
var TYPE_LABEL = { CHANNEL: "Channel", CONFIG: "Config Map", FUNCTION: "Function", MAPVAR: "Map variable", TEXT: "Text" };
var TYPE_COLOR = { CHANNEL: "#2f6fb3", CONFIG: "#007c6e", FUNCTION: "#7b3fb5", MAPVAR: "#b26a00", TEXT: "#6b7280" };
var EDGE_COLOR = {
  "Channel Writer": "#2f6fb3",
  "router.routeMessage": "#7b3fb5",
  "Channel id in code": "#788696",
  "Deploy dependency": "#2e8b57",
  "Shared map variable": "#d97a00"
};
var SEV_COLOR = { Error: C.err, Warning: C.warn, Info: C.accent };
function Badge({ type }) {
  return /* @__PURE__ */ React.createElement("span", { style: { background: TYPE_COLOR[type] || "#6b7280", color: "#fff", borderRadius: 4, padding: "1px 7px", fontSize: 10.5, fontWeight: 600, whiteSpace: "nowrap" } }, TYPE_LABEL[type] || type);
}
function SevDot({ severity }) {
  return /* @__PURE__ */ React.createElement("span", { style: { display: "inline-block", width: 9, height: 9, borderRadius: 9, background: SEV_COLOR[severity] || C.dim, marginRight: 6, flex: "none" } });
}
function plural(n, w) {
  return n + " " + w + (n === 1 ? "" : "s");
}
function canOpen(r) {
  return r && r.ownerId && ["Channel", "Code Template", "Global Script", "Configuration Map", "Code Template Library"].includes(r.ownerType);
}
function clickText(texts, timeoutMs = 6e3, exact = true) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve) => {
    const tick = () => {
      const root = document.querySelector("main") || document.body;
      for (const t of texts) {
        if (!t) continue;
        const want = t.trim();
        const nodes = Array.from(root.querySelectorAll("[role=tab],button,[role=row],tr,li,.step-item,[role=treeitem],td,span,div"));
        let best = null;
        for (const n of nodes) {
          if (n.closest("[data-depexplorer]")) continue;
          const txt = (n.textContent || "").trim();
          const hit = exact ? txt === want : txt.startsWith(want);
          if (hit && n.offsetParent !== null && (!best || best.contains(n))) best = n;
        }
        if (!best) {
          for (const inp of Array.from(root.querySelectorAll("input"))) {
            if (inp.closest("[data-depexplorer]") || inp.offsetParent === null) continue;
            const v = (inp.value || "").trim();
            if (exact ? v === want : v.startsWith(want)) {
              const row = inp.closest("[role=row],tr,.step-item,[role=treeitem],li");
              if (row) {
                best = row;
                break;
              }
            }
          }
        }
        if (best) {
          const target = best.closest("[role=tab],button,[role=row],tr,.step-item,[role=treeitem],li") || best;
          target.scrollIntoView({ block: "nearest" });
          press(target);
          resolve(true);
          return;
        }
      }
      if (Date.now() > deadline) resolve(false);
      else setTimeout(tick, 200);
    };
    setTimeout(tick, 300);
  });
}
function chooseOption(label, timeoutMs = 4e3) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve) => {
    const tick = () => {
      for (const sel of Array.from(document.querySelectorAll("main select"))) {
        if (sel.closest("[data-depexplorer]") || sel.offsetParent === null) continue;
        const opt = Array.from(sel.options).find((o) => o.text.trim() === label);
        if (!opt) continue;
        if (sel.value !== opt.value) {
          const setter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, "value").set;
          setter.call(sel, opt.value);
          sel.dispatchEvent(new Event("change", { bubbles: true }));
        }
        resolve(true);
        return;
      }
      if (Date.now() > deadline) resolve(false);
      else setTimeout(tick, 200);
    };
    setTimeout(tick, 200);
  });
}
function press(el) {
  const o = { bubbles: true, cancelable: true, view: window, button: 0 };
  const P = window.PointerEvent || MouseEvent;
  el.dispatchEvent(new P("pointerdown", { ...o, pointerType: "mouse", isPrimary: true }));
  el.dispatchEvent(new MouseEvent("mousedown", o));
  el.dispatchEvent(new P("pointerup", { ...o, pointerType: "mouse", isPrimary: true }));
  el.dispatchEvent(new MouseEvent("mouseup", o));
  el.dispatchEvent(new MouseEvent("click", o));
}
function highlightLine(snippet, line, timeoutMs = 6e3) {
  let needle = (snippet || "").trim().replace(/…$/, "");
  if (needle.length > 80) needle = needle.slice(0, 80);
  if (needle.length < 2) return Promise.resolve(false);
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve) => {
    const tick = () => {
      const mon = window.monaco;
      const eds = mon && mon.editor && mon.editor.getEditors ? mon.editor.getEditors() : [];
      for (const ed of eds) {
        const dom = ed.getDomNode && ed.getDomNode();
        const model = ed.getModel && ed.getModel();
        if (!dom || !model || dom.offsetParent === null || dom.closest("[data-depexplorer]")) continue;
        const lines = model.getValue().split(/\r?\n/);
        let ln = line > 0 && lines[line - 1] && lines[line - 1].includes(needle) ? line : lines.findIndex((l) => l.includes(needle)) + 1;
        if (ln <= 0) continue;
        const col = lines[ln - 1].indexOf(needle) + 1;
        const range = new mon.Range(ln, col, ln, col + needle.length);
        ed.setSelection(range);
        ed.revealLineInCenter(ln);
        ed.focus();
        setTimeout(() => {
          const still = mon.editor.getEditors().includes(ed) && dom.isConnected && dom.offsetParent !== null;
          if (still || Date.now() > deadline) resolve(true);
          else tick();
        }, 700);
        return;
      }
      const fields = Array.from(document.querySelectorAll("main textarea, main input[type=text], main input:not([type])"));
      for (const f of fields) {
        if (f.offsetParent === null || f.closest("[data-depexplorer]")) continue;
        const v = f.value || "";
        const i = v.indexOf(needle);
        if (i < 0) continue;
        f.focus();
        f.setSelectionRange(i, i + needle.length);
        f.scrollIntoView({ block: "center" });
        resolve(true);
        return;
      }
      if (Date.now() > deadline) resolve(false);
      else setTimeout(tick, 250);
    };
    setTimeout(tick, 400);
  });
}
async function openInAdmin(r) {
  const nav = (p) => platform.router.navigate(p);
  const where = r.location && r.location !== "Channel" ? r.location + (r.line > 0 ? " (line " + r.line + ")" : "") : "";
  if (r.ownerType === "Channel") {
    const id = encodeURIComponent(r.ownerId);
    const seg = r.part === "FILTER" ? "filter" : r.part === "TRANSFORMER" ? "transformer" : r.part === "RESPONSE" ? "response" : null;
    if (seg) {
      nav(`/channels/${id}/${seg}/${r.connector < 0 ? 0 : r.connector}`);
      if (r.stepName) await clickText([r.stepName]);
    } else {
      nav(`/channels/${id}/edit`);
      if (r.part === "SCRIPTS") {
        if (await clickText(["Scripts"], 6e3, false)) await chooseOption(String(r.field || "").replace(/ Script$/, "").replace("Preprocessor", "Preprocessor").trim());
      } else if (r.part === "SETTINGS" && r.connector > 0) {
        if (await clickText(["Destinations"], 6e3, false)) await clickText([r.connectorName]);
      } else if (r.part === "SETTINGS") {
        await clickText(["Source"]);
      }
    }
  } else if (r.ownerType === "Code Template" || r.ownerType === "Code Template Library") {
    nav("/code-templates");
    const name = String(r.ownerName || "").split(" \u203A ").pop();
    await clickText([name]);
  } else if (r.ownerType === "Global Script") {
    nav("/global-scripts");
    await clickText([String(r.field || "")]);
  } else if (r.ownerType === "Configuration Map") {
    nav("/settings?tab=configurationmap");
    await clickText(["Configuration Map"], 5e3);
    await highlightLine(r.ownerId, 0, 5e3);
    return;
  }
  const found = r.snippet ? await highlightLine(r.snippet, r.line) : false;
  if (where && !found) platform.ui.toast(where, "info", 6e3);
}
function ResultTree({ rows, grouping, empty, actions, columns }) {
  const [collapsed, setCollapsed] = React.useState({});
  const [sel, setSel] = React.useState(null);
  React.useEffect(() => {
    setSel(null);
    setCollapsed({});
  }, [rows]);
  const groups = React.useMemo(() => {
    const g1 = [];
    const m1 = {};
    for (const r of rows) {
      const k1 = grouping.level1(r);
      let a = m1[k1];
      if (!a) {
        a = m1[k1] = { key: k1, rows: [], sub: [], subMap: {} };
        g1.push(a);
      }
      a.rows.push(r);
      const k2 = grouping.level2(r);
      if (k2 == null) {
        a.sub.push({ leaf: r });
        continue;
      }
      let b = a.subMap[k2];
      if (!b) {
        b = a.subMap[k2] = { key: k2, rows: [], first: r };
        a.sub.push(b);
      }
      b.rows.push(r);
    }
    return g1;
  }, [rows, grouping]);
  if (!rows.length) return /* @__PURE__ */ React.createElement("div", { style: { padding: 40, textAlign: "center", color: C.dim } }, empty);
  const toggle = (k) => setCollapsed({ ...collapsed, [k]: !collapsed[k] });
  const isOpen = (k, size, level) => collapsed[k] === void 0 ? level === 1 ? size <= 60 || groups.length === 1 : rows.length <= 150 : !collapsed[k];
  const out = [];
  const cellCode = { fontFamily: C.mono, fontSize: 11.5, color: C.dim, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis", maxWidth: 1 };
  const leafRow = (r, label, depth, key) => /* @__PURE__ */ React.createElement(
    "tr",
    {
      key,
      onClick: () => setSel(r),
      onDoubleClick: () => actions.canOpen(r) && actions.open(r),
      style: { cursor: "default", background: sel === r ? "var(--accent-glow, rgba(47,111,179,.15))" : void 0 }
    },
    /* @__PURE__ */ React.createElement("td", { style: { paddingLeft: 10 + depth * 18, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis", maxWidth: 1 }, title: label }, r.severity ? /* @__PURE__ */ React.createElement(SevDot, { severity: r.severity }) : null, label),
    /* @__PURE__ */ React.createElement("td", { style: { whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis", maxWidth: 1 } }, columns.how(r)),
    /* @__PURE__ */ React.createElement("td", { style: { textAlign: "right", color: C.faint } }, r.line > 0 ? r.line : ""),
    /* @__PURE__ */ React.createElement("td", { style: cellCode, title: r.snippet }, r.snippet)
  );
  for (const g of groups) {
    const open1 = isOpen(g.key, g.rows.length, 1);
    out.push(
      /* @__PURE__ */ React.createElement("tr", { key: "g:" + g.key, onClick: () => toggle(g.key), style: { cursor: "pointer", background: C.bg2 } }, /* @__PURE__ */ React.createElement("td", { colSpan: 4, style: { fontWeight: 600 } }, /* @__PURE__ */ React.createElement("span", { style: { display: "inline-block", width: 14, color: C.dim } }, open1 ? "\u25BE" : "\u25B8"), grouping.icon1 ? grouping.icon1(g) : null, g.key, " ", /* @__PURE__ */ React.createElement("span", { style: { color: C.faint, fontWeight: 400 } }, "(", g.rows.length, ")")))
    );
    if (!open1) continue;
    g.sub.forEach((s, i) => {
      if (s.leaf) {
        out.push(leafRow(s.leaf, grouping.leaf(s.leaf), 1, g.key + ":l" + i));
        return;
      }
      if (s.rows.length === 1) {
        const r = s.rows[0];
        const l = grouping.leaf(r);
        out.push(leafRow(r, l && l !== s.key && !l.startsWith(s.key) ? s.key + "  \u203A  " + l : l || s.key, 1, g.key + ":" + s.key));
        return;
      }
      const k = g.key + "\0" + s.key;
      const open2 = isOpen(k, s.rows.length, 2);
      out.push(
        /* @__PURE__ */ React.createElement("tr", { key: "s:" + k, onClick: () => toggle(k), style: { cursor: "pointer" } }, /* @__PURE__ */ React.createElement("td", { colSpan: 4, style: { paddingLeft: 28 } }, /* @__PURE__ */ React.createElement("span", { style: { display: "inline-block", width: 14, color: C.dim } }, open2 ? "\u25BE" : "\u25B8"), s.key, " ", /* @__PURE__ */ React.createElement("span", { style: { color: C.faint } }, "(", s.rows.length, ")")))
      );
      if (open2) s.rows.forEach((r, j) => out.push(leafRow(r, grouping.leaf(r), 2, k + ":" + j)));
    });
  }
  return /* @__PURE__ */ React.createElement("div", { style: { display: "flex", flexDirection: "column", flex: 1, minHeight: 0 } }, /* @__PURE__ */ React.createElement("div", { style: { flex: 1, minHeight: 0, overflow: "auto", borderTop: "1px solid " + C.line } }, /* @__PURE__ */ React.createElement("table", { className: "dt", style: { width: "100%", tableLayout: "fixed" } }, /* @__PURE__ */ React.createElement("colgroup", null, /* @__PURE__ */ React.createElement("col", { style: { width: "42%" } }), /* @__PURE__ */ React.createElement("col", { style: { width: "16%" } }), /* @__PURE__ */ React.createElement("col", { style: { width: 60, flex: "none" } }), /* @__PURE__ */ React.createElement("col", null)), /* @__PURE__ */ React.createElement("thead", null, /* @__PURE__ */ React.createElement("tr", null, /* @__PURE__ */ React.createElement("th", null, "Where"), /* @__PURE__ */ React.createElement("th", null, columns.howTitle), /* @__PURE__ */ React.createElement("th", { style: { textAlign: "right" } }, "Line"), /* @__PURE__ */ React.createElement("th", null, "Code"))), /* @__PURE__ */ React.createElement("tbody", null, out))), /* @__PURE__ */ React.createElement(Detail, { r: sel, actions }));
}
function Detail({ r, actions }) {
  const box = { borderTop: "1px solid " + C.line, padding: "10px 14px", background: C.bg1, minHeight: 120 };
  if (!r) return /* @__PURE__ */ React.createElement("div", { style: { ...box, color: C.dim } }, "Select a row to see the details. Double-click opens the place in the editor.");
  const title = (r.category || r.relation || "") + (r.key && r.category ? ": " + r.key : "");
  const ch = r.targetType === "Channel" && r.targetId ? { id: r.targetId, name: r.targetName } : r.ownerType === "Channel" ? { id: r.ownerId, name: r.ownerName } : null;
  const showCh = ch && !actions.isCurrentChannel(ch.id);
  const search = actions.searchOf(r);
  return /* @__PURE__ */ React.createElement("div", { style: box }, /* @__PURE__ */ React.createElement("div", { style: { fontWeight: 600, display: "flex", alignItems: "center" } }, r.severity ? /* @__PURE__ */ React.createElement(SevDot, { severity: r.severity }) : null, title), /* @__PURE__ */ React.createElement("div", { style: { color: C.dim, fontSize: 12, margin: "3px 0 6px" } }, r.ownerType, " '", r.ownerName, "'", r.location && r.location !== "Channel" && r.location !== r.ownerName ? "  \u203A  " + r.location : "", r.line > 0 ? "  (line " + r.line + ")" : ""), r.message ? /* @__PURE__ */ React.createElement("div", { style: { marginBottom: 6 } }, r.message) : null, r.snippet ? /* @__PURE__ */ React.createElement("pre", { style: { fontFamily: C.mono, fontSize: 12, background: C.bg2, border: "1px solid " + C.line, borderRadius: 4, padding: "6px 9px", margin: "0 0 8px", whiteSpace: "pre-wrap" } }, (r.line > 0 ? r.line + " \u2502 " : "") + r.snippet) : null, /* @__PURE__ */ React.createElement("div", { style: { display: "flex", gap: 6, flexWrap: "wrap" } }, /* @__PURE__ */ React.createElement("button", { className: "btn btn-primary btn-sm", disabled: !actions.canOpen(r), onClick: () => actions.open(r) }, "Open in editor"), showCh ? /* @__PURE__ */ React.createElement("button", { className: "btn btn-sm", onClick: () => actions.query("CHANNEL", ch.id) }, "Dependencies of '", ch.name, "'") : null, search ? /* @__PURE__ */ React.createElement("button", { className: "btn btn-sm", onClick: () => actions.query(search[0], search[1]) }, "Who uses ", search[0] === "FUNCTION" ? search[1] + "()" : "'" + search[1] + "'", "?") : null));
}
var W = 200;
var H = 58;
var HGAP = 110;
var VGAP = 26;
function isFlow(k) {
  return k === "Channel Writer" || k === "router.routeMessage" || k === "Channel id in code";
}
function layout(overview, opts) {
  const byId = {};
  overview.channels.forEach((n) => byId[n.id] = n);
  const visible = (e) => e.kind === "Deploy dependency" ? opts.deploy : e.kind === "Shared map variable" ? opts.shared : e.kind === "Channel id in code" ? opts.mention : true;
  const target = (e) => e.toId ? e.toId : "?" + e.targetRef;
  const vis = overview.edges.filter((e) => visible(e) && byId[e.fromId]);
  let show = /* @__PURE__ */ new Set();
  if (opts.focus && byId[opts.focus]) {
    const adj = {};
    const add = (a, b) => (adj[a] = adj[a] || []).push(b);
    vis.forEach((e) => {
      add(e.fromId, target(e));
      add(target(e), e.fromId);
    });
    const dist = { [opts.focus]: 0 };
    const q = [opts.focus];
    while (q.length) {
      const c = q.shift();
      if (dist[c] >= opts.depth) continue;
      for (const n of adj[c] || []) if (dist[n] === void 0) {
        dist[n] = dist[c] + 1;
        q.push(n);
      }
    }
    show = new Set(Object.keys(dist));
  } else {
    vis.forEach((e) => {
      show.add(e.fromId);
      show.add(target(e));
    });
    if (opts.unconnected) overview.channels.forEach((n) => show.add(n.id));
  }
  const nodes = {};
  overview.channels.forEach((n) => {
    if (show.has(n.id)) nodes[n.id] = { id: n.id, node: n, name: n.name, x: 0, y: 0, layer: 0, order: 0 };
  });
  const edges = [];
  for (const e of vis) {
    const t = target(e);
    if (!show.has(e.fromId) || !show.has(t)) continue;
    if (!nodes[t]) nodes[t] = { id: t, node: null, name: "Missing channel", missingRef: e.targetRef, x: 0, y: 0, layer: 0, order: 0 };
    if (nodes[e.fromId]) edges.push({ e, from: nodes[e.fromId], to: nodes[t] });
  }
  const parent = {};
  Object.keys(nodes).forEach((k) => parent[k] = k);
  const find = (x) => parent[x] === x ? x : parent[x] = find(parent[x]);
  edges.forEach((e) => {
    const a = find(e.from.id), b = find(e.to.id);
    if (a !== b) parent[a] = b;
  });
  const comps = {};
  Object.values(nodes).forEach((n) => (comps[find(n.id)] = comps[find(n.id)] || []).push(n));
  const byName = (a, b) => a.name.localeCompare(b.name, void 0, { sensitivity: "base" });
  const lists = Object.values(comps).sort((a, b) => {
    const fa = opts.focus && a.some((n) => n.id === opts.focus), fb = opts.focus && b.some((n) => n.id === opts.focus);
    if (fa !== fb) return fa ? -1 : 1;
    return b.length - a.length || byName(a[0], b[0]);
  });
  let y = 0;
  const singles = [];
  for (const comp of lists) {
    if (comp.length === 1) {
      singles.push(comp[0]);
      continue;
    }
    y += layoutComponent(comp, edges, y) + VGAP * 3;
  }
  const cols = Math.max(1, Math.min(6, Math.ceil(Math.sqrt(singles.length * 2))));
  singles.sort(byName).forEach((n, i) => {
    n.x = i % cols * (W + 40);
    n.y = y + Math.floor(i / cols) * (H + VGAP);
  });
  route(edges);
  return { nodes: Object.values(nodes), edges };
}
function layoutComponent(comp, edges, y0) {
  const inComp = new Set(comp);
  const out = /* @__PURE__ */ new Map();
  for (const e of edges) if (inComp.has(e.from) && isFlow(e.e.kind) && e.from !== e.to) {
    if (!out.has(e.from)) out.set(e.from, []);
    out.get(e.from).push(e.to);
  }
  const sorted = [...comp].sort((a, b) => a.name.localeCompare(b.name, void 0, { sensitivity: "base" }));
  const back = /* @__PURE__ */ new Set();
  const state = /* @__PURE__ */ new Map();
  for (const n of sorted) {
    if (state.has(n)) continue;
    const stack = [[n, 0]];
    state.set(n, 1);
    while (stack.length) {
      const top = stack[stack.length - 1];
      const l = out.get(top[0]) || [];
      if (top[1] >= l.length) {
        state.set(top[0], 2);
        stack.pop();
        continue;
      }
      const t = l[top[1]++];
      const ts = state.get(t);
      if (ts === void 0) {
        state.set(t, 1);
        stack.push([t, 0]);
      } else if (ts === 1) back.add(top[0].id + ">" + t.id);
    }
  }
  const indeg = /* @__PURE__ */ new Map();
  comp.forEach((n) => {
    indeg.set(n, 0);
    n.layer = 0;
  });
  out.forEach((l, f) => l.forEach((t) => {
    if (!back.has(f.id + ">" + t.id)) indeg.set(t, indeg.get(t) + 1);
  }));
  const q = sorted.filter((n) => indeg.get(n) === 0);
  while (q.length) {
    const n = q.shift();
    for (const t of out.get(n) || []) {
      if (back.has(n.id + ">" + t.id)) continue;
      t.layer = Math.max(t.layer, n.layer + 1);
      indeg.set(t, indeg.get(t) - 1);
      if (indeg.get(t) === 0) q.push(t);
    }
  }
  const hasPred = /* @__PURE__ */ new Set();
  out.forEach((l, f) => l.forEach((t) => {
    if (!back.has(f.id + ">" + t.id)) hasPred.add(t);
  }));
  for (const n of comp) {
    const s = out.get(n);
    if (hasPred.has(n) || !s || !s.length) continue;
    const min = Math.min(...s.filter((t) => !back.has(n.id + ">" + t.id)).map((t) => t.layer));
    if (isFinite(min) && min - 1 > n.layer) n.layer = min - 1;
  }
  for (const e of edges) if (inComp.has(e.from) && !isFlow(e.e.kind) && e.to.layer === 0 && e.from.layer > 0 && !out.get(e.to)) e.to.layer = e.from.layer;
  const layers = Math.max(...comp.map((n) => n.layer)) + 1;
  const byLayer = Array.from({ length: layers }, () => []);
  sorted.forEach((n) => byLayer[n.layer].push(n));
  byLayer.forEach((l) => l.forEach((n, i) => n.order = i));
  const nb = /* @__PURE__ */ new Map();
  for (const e of edges) if (inComp.has(e.from) && e.from !== e.to) {
    if (!nb.has(e.from)) nb.set(e.from, []);
    if (!nb.has(e.to)) nb.set(e.to, []);
    nb.get(e.from).push(e.to);
    nb.get(e.to).push(e.from);
  }
  for (let it = 0; it < 6; it++) {
    const down = it % 2 === 0;
    for (let li = down ? 1 : layers - 2; down ? li < layers : li >= 0; li += down ? 1 : -1) {
      const ref = down ? li - 1 : li + 1;
      const bc = /* @__PURE__ */ new Map();
      for (const n of byLayer[li]) {
        const ms = (nb.get(n) || []).filter((m) => m.layer === ref);
        bc.set(n, ms.length ? ms.reduce((s, m) => s + m.order, 0) / ms.length : n.order);
      }
      byLayer[li].sort((a, b) => bc.get(a) - bc.get(b));
      byLayer[li].forEach((n, i) => n.order = i);
    }
  }
  const maxRows = Math.max(...byLayer.map((l) => l.length));
  const height = maxRows * (H + VGAP) - VGAP;
  byLayer.forEach((l, li) => {
    const off = (height - (l.length * (H + VGAP) - VGAP)) / 2;
    l.forEach((n, i) => {
      n.x = li * (W + HGAP);
      n.y = y0 + off + i * (H + VGAP);
    });
  });
  return height;
}
function route(edges) {
  const pairs = {};
  edges.forEach((e) => {
    const k = [e.from.id, e.to.id].sort().join("|");
    (pairs[k] = pairs[k] || []).push(e);
  });
  Object.values(pairs).forEach((l) => l.forEach((e, i) => {
    e.par = i;
    e.parN = l.length;
  }));
  for (const e of edges) {
    const off = (e.par - (e.parN - 1) / 2) * 12;
    let d, ex, ey, cx, cy;
    if (e.from === e.to) {
      const x = e.from.x + W - 30, y = e.from.y;
      d = `M${x},${y} C${x + 10},${y - 45} ${x + 60},${y - 10} ${e.from.x + W},${y + H / 3}`;
      cx = x + 60;
      cy = y - 10;
      ex = e.from.x + W;
      ey = y + H / 3;
    } else if (e.to.x > e.from.x + W / 2) {
      const x1 = e.from.x + W, y1 = e.from.y + H / 2 + off, x2 = e.to.x, y2 = e.to.y + H / 2 + off, dx = Math.max(40, (x2 - x1) / 2);
      d = `M${x1},${y1} C${x1 + dx},${y1} ${x2 - dx},${y2} ${x2},${y2}`;
      cx = x2 - dx;
      cy = y2;
      ex = x2;
      ey = y2;
    } else if (e.to.x < e.from.x - W / 2) {
      const x1 = e.from.x, y1 = e.from.y + H / 2 + off, x2 = e.to.x + W, y2 = e.to.y + H / 2 + off, dx = Math.max(45, (x1 - x2) / 2);
      d = `M${x1},${y1} C${x1 - dx},${y1} ${x2 + dx},${y2} ${x2},${y2}`;
      cx = x2 + dx;
      cy = y2;
      ex = x2;
      ey = y2;
    } else {
      const x1 = e.from.x + W, y1 = e.from.y + H / 2 + off, x2 = e.to.x + W, y2 = e.to.y + H / 2 + off, b = 50 + Math.min(120, Math.abs(y2 - y1) * 0.25);
      d = `M${x1},${y1} C${x1 + b},${y1} ${x2 + b},${y2} ${x2},${y2}`;
      cx = x2 + b;
      cy = y2;
      ex = x2;
      ey = y2;
    }
    const a = Math.atan2(ey - cy, ex - cx);
    e.d = d;
    e.arrow = `M${ex},${ey} L${ex - 10 * Math.cos(a - 0.4)},${ey - 10 * Math.sin(a - 0.4)} L${ex - 10 * Math.cos(a + 0.4)},${ey - 10 * Math.sin(a + 0.4)} Z`;
  }
}
function edgeColor(e) {
  return e.broken ? "#c62828" : EDGE_COLOR[e.kind] || "#788696";
}
function sourceColor(t) {
  const s = (t || "").toLowerCase();
  if (s.includes("channel reader")) return "#2f6fb3";
  if (s.includes("http") || s.includes("web service")) return "#009688";
  if (s.includes("tcp") || s.includes("mllp")) return "#438e3f";
  if (s.includes("file")) return "#a16d1b";
  if (s.includes("database")) return "#6d4c9f";
  if (s.includes("javascript")) return "#c29d00";
  return "#7a8693";
}
function FlowGraph({ overview, focus, setFocus, depth, setDepth, onExplore, selected }) {
  const [opts, setOpts] = React.useState({ deploy: true, shared: true, mention: true, unconnected: false });
  const [hover, setHover] = React.useState(null);
  const [view, setView] = React.useState({ s: 1, tx: 20, ty: 20 });
  const [moved, setMoved] = React.useState(0);
  const hostRef = React.useRef(null);
  const drag = React.useRef(null);
  const g = React.useMemo(() => overview ? layout(overview, { ...opts, focus, depth }) : { nodes: [], edges: [] }, [overview, opts, focus, depth]);
  const bounds = () => {
    if (!g.nodes.length) return { x: 0, y: 0, w: 1, h: 1 };
    const xs = g.nodes.map((n) => n.x), ys = g.nodes.map((n) => n.y);
    const x = Math.min(...xs) - 20, y = Math.min(...ys) - 60;
    return { x, y, w: Math.max(...xs) + W + 80 - x, h: Math.max(...ys) + H + 80 - y };
  };
  const fit = () => {
    const el = hostRef.current;
    if (!el) return;
    const b = bounds(), cw = el.clientWidth || 800, chh = (el.clientHeight || 500) - 34;
    const s = Math.max(0.15, Math.min(1.2, Math.min((cw - 40) / b.w, (chh - 20) / b.h)));
    setView({ s, tx: (cw - b.w * s) / 2 - b.x * s, ty: Math.max(10, (chh - b.h * s) / 2) - b.y * s });
  };
  React.useEffect(() => {
    fit();
  }, [g]);
  const onWheel = (ev) => {
    ev.preventDefault();
    const r = hostRef.current.getBoundingClientRect();
    const px = ev.clientX - r.left, py = ev.clientY - r.top, f0 = ev.deltaY < 0 ? 1.15 : 1 / 1.15;
    setView((v) => {
      const s = Math.max(0.1, Math.min(3, v.s * f0)), f = s / v.s;
      return { s, tx: px - (px - v.tx) * f, ty: py - (py - v.ty) * f };
    });
  };
  React.useEffect(() => {
    const el = hostRef.current;
    if (!el) return;
    el.addEventListener("wheel", onWheel, { passive: false });
    return () => el.removeEventListener("wheel", onWheel);
  }, []);
  const active = hover;
  const near = /* @__PURE__ */ new Set();
  if (active) {
    near.add(active);
    g.edges.forEach((e) => {
      if (e.from.id === active) near.add(e.to.id);
      if (e.to.id === active) near.add(e.from.id);
    });
  }
  const onDown = (ev, n) => {
    ev.stopPropagation();
    drag.current = { x: ev.clientX, y: ev.clientY, n, nx: n?.x, ny: n?.y, tx: view.tx, ty: view.ty };
  };
  const onMove = (ev) => {
    const d = drag.current;
    if (!d) return;
    const dx = ev.clientX - d.x, dy = ev.clientY - d.y;
    if (d.n) {
      d.n.x = d.nx + dx / view.s;
      d.n.y = d.ny + dy / view.s;
      route(g.edges);
      setMoved(moved + 1);
    } else setView({ ...view, tx: d.tx + dx, ty: d.ty + dy });
  };
  const opt = (k, label, tip) => /* @__PURE__ */ React.createElement("label", { title: tip, style: { display: "inline-flex", alignItems: "center", gap: 4, fontSize: 12 } }, /* @__PURE__ */ React.createElement("input", { type: "checkbox", checked: opts[k], onChange: (e) => setOpts({ ...opts, [k]: e.target.checked }) }), label);
  const sorted = overview ? [...overview.channels].sort((a, b) => a.name.localeCompare(b.name, void 0, { sensitivity: "base" })) : [];
  const legend = [["Channel Writer", "#2f6fb3", 0], ["router.routeMessage", "#7b3fb5", 0], ["Channel id in code", "#788696", 1], ["Deploy dependency", "#2e8b57", 1], ["Shared globalMap", "#d97a00", 1], ["Missing / disabled", "#c62828", 1]];
  return /* @__PURE__ */ React.createElement("div", { style: { display: "flex", flexDirection: "column", flex: 1, minHeight: 0 } }, /* @__PURE__ */ React.createElement("div", { style: { display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap", padding: "6px 10px", borderBottom: "1px solid " + C.line, background: C.bg1 } }, /* @__PURE__ */ React.createElement("span", { style: { fontSize: 12 } }, "Show:"), /* @__PURE__ */ React.createElement("select", { value: focus || "", onChange: (e) => setFocus(e.target.value || null), style: { width: 240, flex: "none" } }, /* @__PURE__ */ React.createElement("option", { value: "" }, "All connected channels"), sorted.map((n) => /* @__PURE__ */ React.createElement("option", { key: n.id, value: n.id }, n.name))), /* @__PURE__ */ React.createElement("span", { style: { fontSize: 12 } }, "Steps:"), /* @__PURE__ */ React.createElement("input", { type: "number", min: 1, max: 9, value: depth, disabled: !focus, onChange: (e) => setDepth(Math.max(1, Math.min(9, Number(e.target.value) || 1))), style: { width: 60, flex: "none" } }), opt("deploy", "Deploy dependencies", "Channel \u203A Set Dependencies (deploy / start order)."), opt("shared", "Shared globalMap", "One channel writes a globalMap variable that another channel reads."), opt("mention", "Channel ids in code", "A channel id appears in a script or setting."), opt("unconnected", "Unconnected channels", "Also show channels that have no links."), /* @__PURE__ */ React.createElement("span", { style: { flex: 1 } }), /* @__PURE__ */ React.createElement("button", { className: "btn btn-sm", onClick: () => setView({ ...view, s: view.s / 1.25 }) }, "\u2212"), /* @__PURE__ */ React.createElement("button", { className: "btn btn-sm", onClick: () => setView({ ...view, s: view.s * 1.25 }) }, "+"), /* @__PURE__ */ React.createElement("button", { className: "btn btn-sm", onClick: fit }, "Fit")), /* @__PURE__ */ React.createElement(
    "div",
    {
      ref: hostRef,
      onMouseDown: (e) => onDown(e),
      onMouseMove: onMove,
      onMouseUp: () => drag.current = null,
      onMouseLeave: () => {
        drag.current = null;
        setHover(null);
      },
      style: { flex: 1, minHeight: 360, position: "relative", overflow: "hidden", cursor: "grab", background: C.bg0 || "var(--bg0)" }
    },
    !g.nodes.length ? /* @__PURE__ */ React.createElement("div", { style: { position: "absolute", inset: 0, display: "flex", alignItems: "center", justifyContent: "center", color: C.dim, padding: 40, textAlign: "center" } }, !overview ? "Loading\u2026" : focus ? "This channel has no connections of the selected kinds." : 'No channel sends messages to another channel, and no other links were found. Tick "Unconnected channels" to show every channel.') : /* @__PURE__ */ React.createElement("svg", { width: "100%", height: "100%", style: { position: "absolute", inset: 0 } }, /* @__PURE__ */ React.createElement("g", { transform: `translate(${view.tx},${view.ty}) scale(${view.s})` }, g.edges.map((e, i) => {
      const on = !active || e.from.id === active || e.to.id === active;
      const sel = selected && (e.from.id === selected || e.to.id === selected);
      const dashed = !e.e.enabled || e.e.broken || !isFlow(e.e.kind) || e.e.kind === "Channel id in code";
      const col = edgeColor(e.e);
      const tip = e.e.kind === "Deploy dependency" ? `${e.from.name} depends on ${e.to.name}` : e.e.kind === "Shared map variable" ? `${e.from.name} writes globalMap variables that ${e.to.name} reads: ${e.e.detail || ""}` : `${e.from.name}${e.e.connectorName ? " \u203A " + e.e.connectorName : ""} \u2192 ${e.to.node ? e.to.name : e.to.missingRef} (${e.e.kind})${e.e.enabled ? "" : " \u2013 disabled"}`;
      return /* @__PURE__ */ React.createElement("g", { key: i, opacity: on ? 1 : 0.15 }, /* @__PURE__ */ React.createElement("path", { d: e.d, fill: "none", stroke: col, strokeWidth: on && (active || sel) ? 2.6 : 1.6, strokeDasharray: dashed ? "6 4" : void 0 }, /* @__PURE__ */ React.createElement("title", null, tip)), /* @__PURE__ */ React.createElement("path", { d: e.arrow, fill: col }));
    }), g.nodes.map((n) => {
      const on = !active || near.has(n.id);
      const missing = !n.node;
      const dis = n.node && !n.node.enabled;
      const isSel = n.id === selected, isFoc = n.id === focus;
      const tip = missing ? `Missing channel
${n.missingRef}` : `${n.name}${dis ? " (disabled)" : ""}
Source: ${n.node.sourceType}
` + n.node.destinations.map((d) => "\u2192 " + d.split("|")[1] + " (" + d.split("|")[2] + ")").join("\n") + (n.node.errors + n.node.warnings ? `
${plural(n.node.errors, "error")}, ${plural(n.node.warnings, "warning")}` : "") + "\nDouble-click: show its dependencies";
      return /* @__PURE__ */ React.createElement(
        "g",
        {
          key: n.id,
          transform: `translate(${n.x},${n.y})`,
          opacity: on ? 1 : 0.18,
          style: { cursor: missing ? "default" : "pointer" },
          onMouseDown: (ev) => onDown(ev, n),
          onMouseEnter: () => setHover(n.id),
          onMouseLeave: () => setHover(null),
          onDoubleClick: () => !missing && onExplore(n.id)
        },
        /* @__PURE__ */ React.createElement("title", null, tip),
        /* @__PURE__ */ React.createElement("rect", { x: 2, y: 3, width: W, height: H, rx: 6, fill: "rgba(0,0,0,.08)" }),
        /* @__PURE__ */ React.createElement(
          "rect",
          {
            width: W,
            height: H,
            rx: 6,
            strokeWidth: isSel ? 2.6 : isFoc ? 2 : 1.2,
            strokeDasharray: missing || dis ? "5 4" : void 0,
            style: {
              fill: missing ? "color-mix(in srgb, #c62828 10%, var(--bg1))" : isFoc ? "color-mix(in srgb, #2f6fb3 14%, var(--bg1))" : dis ? "var(--bg2)" : "var(--bg1)",
              stroke: isSel || isFoc ? "#2f6fb3" : missing ? "#c62828" : "var(--line-strong, var(--line))"
            }
          }
        ),
        /* @__PURE__ */ React.createElement("rect", { width: 5, height: H, rx: 2, fill: missing ? "#c62828" : sourceColor(n.node.sourceType) }),
        /* @__PURE__ */ React.createElement("text", { x: 12, y: 22, fontSize: 12, fontWeight: 600, style: { fill: missing ? "#c62828" : dis ? "var(--text-faint)" : "var(--text)" } }, clip(missing ? "Missing channel" : n.name, n.node && n.node.errors + n.node.warnings ? 22 : 27)),
        /* @__PURE__ */ React.createElement("text", { x: 12, y: 41, fontSize: 10.5, style: { fill: missing ? "#c62828" : "var(--text-dim)" } }, clip(missing ? n.missingRef : `${n.node.sourceType} \xB7 ${n.node.destinations.length} dest.${dis ? " \xB7 disabled" : ""}`, 32)),
        n.node && n.node.warnings ? /* @__PURE__ */ React.createElement(Pill, { x: W - 8, n: n.node.warnings, c: "#e08a00" }) : null,
        n.node && n.node.errors ? /* @__PURE__ */ React.createElement(Pill, { x: W - 8 - (n.node.warnings ? 24 : 0), n: n.node.errors, c: "#c62828" }) : null
      );
    }))),
    /* @__PURE__ */ React.createElement("div", { style: { position: "absolute", left: 10, bottom: 8, display: "flex", gap: 12, flexWrap: "wrap", fontSize: 11, color: C.dim, background: "color-mix(in srgb, var(--bg1) 88%, transparent)", border: "1px solid " + C.line, borderRadius: 6, padding: "3px 8px" } }, legend.map(([l, c, d]) => /* @__PURE__ */ React.createElement("span", { key: l, style: { display: "inline-flex", alignItems: "center", gap: 5 } }, /* @__PURE__ */ React.createElement("svg", { width: 22, height: 6 }, /* @__PURE__ */ React.createElement("line", { x1: 0, y1: 3, x2: 22, y2: 3, stroke: c, strokeWidth: 2, strokeDasharray: d ? "5 4" : void 0 })), l))),
    /* @__PURE__ */ React.createElement("div", { style: { position: "absolute", right: 10, bottom: 10, fontSize: 11, color: C.faint } }, Math.round(view.s * 100), "%")
  ));
}
function Pill({ x, n, c }) {
  const s = n > 99 ? "99+" : String(n);
  const w = Math.max(16, s.length * 7 + 8);
  return /* @__PURE__ */ React.createElement("g", null, /* @__PURE__ */ React.createElement("rect", { x: x - w, y: 8, width: w, height: 16, rx: 8, fill: c }), /* @__PURE__ */ React.createElement("text", { x: x - w / 2, y: 20, fontSize: 9.5, fontWeight: 700, fill: "#fff", textAnchor: "middle" }, s));
}
function clip(s, n) {
  s = s || "";
  return s.length > n ? s.slice(0, n - 1) + "\u2026" : s;
}
var GROUP_ORDER = [
  "Problems",
  "Receives messages from",
  "Sends messages to",
  "Deploy dependencies",
  "Shared map variables",
  "Configuration Map keys",
  "Code template functions",
  "Map variables written",
  "Map variables read"
];
var CATEGORY_ORDER = [
  "Missing channel",
  "Missing Configuration Map key",
  "Code template library not enabled",
  "Function not available here",
  "Function not added to scripts",
  "Disabled target channel",
  "Unknown function",
  "Map variable never written",
  "Unresolved variable",
  "Function defined more than once",
  "Unused Configuration Map key",
  "Unused code template function",
  "Library not enabled for any channel",
  "Map variable never read",
  "Channel nobody sends to"
];
var rank = (arr, v) => {
  const i = arr.indexOf(v);
  return i < 0 ? arr.length : i;
};
function exploreGrouping(current) {
  return {
    level1: (r) => r.group || "Results",
    level2: (r) => {
      const g = r.group || "";
      if (g === "Problems") return r.category;
      if (g === "Deploy dependencies" || g === "Shared map variables") return null;
      if (g === "Configuration Map keys" || g.startsWith("Map variables")) return r.key;
      if (g === "Code template functions") return r.key + "()" + (r.message ? "  \u2014  " + String(r.message).replace("code template ", "") : "");
      if (g === "Sends messages to") return r.message;
      return r.ownerName;
    },
    leaf: (r) => {
      const g = r.group || "";
      if (g === "Deploy dependencies") return r.relation + "  " + (r.message || "");
      if (g === "Shared map variables") return current && r.ownerName === current.title ? r.relation : r.ownerName + " \u2014 " + r.relation;
      if (!r.location || r.location === "Channel") return r.message || r.ownerName;
      return r.location;
    },
    icon1: (gr) => gr.key === "Problems" ? /* @__PURE__ */ React.createElement(SevDot, { severity: "Error" }) : null
  };
}
var reportGrouping = {
  level1: (r) => r.category || "",
  level2: () => null,
  leaf: (r) => {
    if (r.ownerType === "Configuration Map") return r.ownerName;
    const loc = !r.location || r.location === "Channel" ? "" : r.location;
    if (!loc || loc === r.ownerName) return r.ownerName;
    if (loc.startsWith(r.ownerName + " \u203A") || r.ownerName.startsWith(loc) && r.ownerType !== "Channel") return loc;
    return r.ownerName + "  \u203A  " + loc;
  },
  icon1: (gr) => /* @__PURE__ */ React.createElement(SevDot, { severity: gr.rows[0] && gr.rows[0].severity })
};
function searchOf(r, current) {
  const g = (r.group || "") + " " + (r.category || "");
  const k = r.key;
  if (!k) return null;
  let s = null;
  if (g.includes("Configuration Map")) s = ["CONFIG", k];
  else if (/function|Function|Code template library/.test(g)) s = ["FUNCTION", k];
  else if (/Map variable|Unresolved variable|map variable/.test(g)) s = ["MAPVAR", k];
  else if (g.includes("Missing channel")) s = ["TEXT", k];
  if (s && current && s[0] === current.type && s[1] === current.key) return null;
  return s;
}
function Explorer({ initialChannel, compact, setTasks }) {
  const [overview, setOverview] = React.useState(null);
  const [report, setReport] = React.useState(null);
  const [busy, setBusy] = React.useState(0);
  const [error, setError] = React.useState("");
  const [tab, setTab] = React.useState("explore");
  const [result, setResult] = React.useState(null);
  const [history, setHistory] = React.useState({ list: [], i: -1 });
  const [text, setText] = React.useState("");
  const [typeFilter, setTypeFilter] = React.useState("");
  const [suggestOpen, setSuggestOpen] = React.useState(false);
  const [sugIdx, setSugIdx] = React.useState(0);
  const [focus, setFocus] = React.useState(null);
  const [depth, setDepth] = React.useState(2);
  const [pFilter, setPFilter] = React.useState("");
  const [uFilter, setUFilter] = React.useState("");
  const [showErr, setShowErr] = React.useState(true);
  const [showWarn, setShowWarn] = React.useState(true);
  const mounted = React.useRef(true);
  React.useEffect(() => () => {
    mounted.current = false;
  }, []);
  const work = async (fn) => {
    setBusy((b) => b + 1);
    setError("");
    try {
      return await fn();
    } catch (e) {
      if (mounted.current) setError(errText(e));
      return null;
    } finally {
      if (mounted.current) setBusy((b) => b - 1);
    }
  };
  const load = async (refresh = false) => {
    const o = await work(() => getOverview(refresh));
    if (!o || !mounted.current) return;
    setOverview(o);
    if (!compact) {
      const r = await work(getReport);
      if (r && mounted.current) setReport(r);
    }
  };
  const query = async (type, key, record = true) => {
    setTab("explore");
    const r = await work(() => runQuery(type, key));
    if (!r || !mounted.current) return;
    setResult(r);
    setText(type === "FUNCTION" ? r.title + "()" : type === "TEXT" ? text : r.title);
    setSuggestOpen(false);
    if (record) setHistory((h) => {
      const l = h.list.slice(0, h.i + 1).concat([{ type, key }]).slice(-50);
      return { list: l, i: l.length - 1 };
    });
  };
  React.useEffect(() => {
    (async () => {
      await load(false);
      if (initialChannel && mounted.current) {
        setFocus(initialChannel);
        setDepth(1);
        query("CHANNEL", initialChannel);
      }
    })();
  }, [initialChannel]);
  React.useEffect(() => {
    if (setTasks) setTasks("Dependency Explorer Tasks", [platform.ui.taskButton("Refresh", "refresh", () => load(false)), platform.ui.taskButton("Rescan", "refresh", () => load(true))].filter(Boolean));
  }, [setTasks]);
  const items = React.useMemo(() => {
    if (!overview) return [];
    const l = [];
    overview.channels.forEach((n) => l.push({ type: "CHANNEL", key: n.id, label: n.name, detail: n.sourceType + (n.enabled ? "" : " \xB7 disabled") }));
    overview.configKeys.forEach((k) => l.push({ type: "CONFIG", key: k, label: k, detail: "Configuration Map" }));
    overview.missingConfigKeys.forEach((k) => l.push({ type: "CONFIG", key: k, label: k, detail: "read, but not defined in the Configuration Map" }));
    overview.functions.forEach((f) => {
      const p = f.split("|");
      l.push({ type: "FUNCTION", key: p[0], label: p[0] + "()", detail: (p[2] ? p[2] + " \u203A " : "") + (p[1] || "") });
    });
    overview.mapVariables.forEach((v) => {
      const p = v.split("|");
      l.push({ type: "MAPVAR", key: p[0], label: p[0], detail: p[1] || "" });
    });
    return l.sort((a, b) => a.label.localeCompare(b.label, void 0, { sensitivity: "base" }));
  }, [overview]);
  const suggestions = React.useMemo(() => {
    const t = text.trim().toLowerCase();
    if (!t) return [];
    const pre = [], con = [];
    for (const it of items) {
      if (typeFilter && it.type !== typeFilter) continue;
      const k = it.label.toLowerCase();
      if (k.startsWith(t)) pre.push(it);
      else if (k.includes(t) || it.detail.toLowerCase().includes(t)) con.push(it);
      if (pre.length > 60) break;
    }
    const out = pre.concat(con).slice(0, 60);
    if (!typeFilter || typeFilter === "TEXT") out.push({ type: "TEXT", key: text.trim(), label: text.trim(), detail: "find this text in every script and setting" });
    return out;
  }, [text, items, typeFilter]);
  const submit = (it) => {
    if (!it) {
      const t = text.trim();
      if (!t) return;
      it = items.find((x) => (!typeFilter || x.type === typeFilter) && x.key.toLowerCase() === t.toLowerCase()) || { type: typeFilter || "TEXT", key: t };
    }
    query(it.type, it.key);
  };
  const go = (d) => {
    const i = history.i + d;
    if (i < 0 || i >= history.list.length) return;
    setHistory({ ...history, i });
    query(history.list[i].type, history.list[i].key, false);
  };
  const actions = {
    open: openInAdmin,
    canOpen,
    query: (t, k) => query(t, k),
    isCurrentChannel: (id) => result && result.type === "CHANNEL" && result.key === id && tab === "explore",
    searchOf: (r) => searchOf(r, result)
  };
  const sortedResultRows = React.useMemo(() => {
    if (!result) return [];
    return [...result.rows].sort((a, b) => rank(GROUP_ORDER, a.group) - rank(GROUP_ORDER, b.group) || (result.type === "CHANNEL" ? 0 : a.ownerName.localeCompare(b.ownerName, void 0, { sensitivity: "base" })));
  }, [result]);
  const sevRank = (s) => s === "Error" ? 0 : s === "Warning" ? 1 : 2;
  const reportSort = (a, b) => sevRank(a.severity) - sevRank(b.severity) || rank(CATEGORY_ORDER, a.category) - rank(CATEGORY_ORDER, b.category) || str(a.key).localeCompare(str(b.key)) || a.ownerName.localeCompare(b.ownerName);
  const matches = (r, f) => !f || [r.ownerName, r.location, r.key, r.message, r.snippet, r.category].some((s) => s && String(s).toLowerCase().includes(f.toLowerCase()));
  const problems = React.useMemo(() => report ? report.problems.filter((r) => (r.severity === "Error" ? showErr : showWarn) && matches(r, pFilter)).sort(reportSort) : [], [report, showErr, showWarn, pFilter]);
  const unused = React.useMemo(() => report ? report.unused.filter((r) => matches(r, uFilter)).sort(reportSort) : [], [report, uFilter]);
  const errCount = report ? report.problems.filter((r) => r.severity === "Error").length : 0;
  const warnCount = report ? report.problems.length - errCount : 0;
  const links = overview ? overview.edges.filter((e) => e.kind !== "Deploy dependency" && e.kind !== "Shared map variable").length : 0;
  const status = overview ? `${plural(overview.channels.length, "channel")} \xB7 ${plural(links, "message link")} \xB7 checked ${new Date(overview.scannedAt).toLocaleTimeString()}` : "";
  const tabs = [["explore", "Explore"], ["graph", "Flow Graph"]].concat(compact ? [] : [
    ["problems", `Problems (${errCount} error${errCount === 1 ? "" : "s"} \xB7 ${warnCount} warning${warnCount === 1 ? "" : "s"})`],
    ["unused", `Unused (${report ? report.unused.length : 0})`]
  ]);
  return /* @__PURE__ */ React.createElement("div", { "data-depexplorer": "", style: { display: "flex", flexDirection: "column", height: compact ? "100%" : "calc(100vh - 150px)", minHeight: 480, color: C.text } }, /* @__PURE__ */ React.createElement("div", { style: { display: "flex", alignItems: "center", gap: 8, padding: "8px 10px", borderBottom: "1px solid " + C.line, background: C.bg1, position: "relative" } }, /* @__PURE__ */ React.createElement("select", { value: typeFilter, onChange: (e) => setTypeFilter(e.target.value), title: "Limit the suggestions to one kind", style: { width: 190, flex: "none" } }, /* @__PURE__ */ React.createElement("option", { value: "" }, "Everything"), /* @__PURE__ */ React.createElement("option", { value: "CHANNEL" }, "Channels"), /* @__PURE__ */ React.createElement("option", { value: "CONFIG" }, "Configuration Map keys"), /* @__PURE__ */ React.createElement("option", { value: "FUNCTION" }, "Code template functions"), /* @__PURE__ */ React.createElement("option", { value: "MAPVAR" }, "Map variables"), /* @__PURE__ */ React.createElement("option", { value: "TEXT" }, "Text")), /* @__PURE__ */ React.createElement("div", { style: { flex: 1, minWidth: 260, position: "relative" } }, /* @__PURE__ */ React.createElement(
    "input",
    {
      type: "search",
      value: text,
      placeholder: "Search a channel, Configuration Map key, function, map variable or any text\u2026",
      onChange: (e) => {
        setText(e.target.value);
        setSuggestOpen(true);
        setSugIdx(0);
      },
      onFocus: () => setSuggestOpen(true),
      onBlur: () => setTimeout(() => mounted.current && setSuggestOpen(false), 150),
      onKeyDown: (e) => {
        if (e.key === "ArrowDown") {
          e.preventDefault();
          setSuggestOpen(true);
          setSugIdx(Math.min(suggestions.length - 1, sugIdx + 1));
        } else if (e.key === "ArrowUp") {
          e.preventDefault();
          setSugIdx(Math.max(0, sugIdx - 1));
        } else if (e.key === "Enter") {
          e.preventDefault();
          submit(suggestOpen && suggestions[sugIdx] ? suggestions[sugIdx] : void 0);
        } else if (e.key === "Escape") setSuggestOpen(false);
      },
      style: { width: "100%", fontSize: 13, padding: "5px 8px" }
    }
  ), suggestOpen && suggestions.length ? /* @__PURE__ */ React.createElement("div", { style: { position: "absolute", left: 0, right: 0, top: "100%", zIndex: 50, maxHeight: 330, overflow: "auto", background: C.bg1, border: "1px solid " + C.line, borderRadius: 6, boxShadow: "0 6px 18px rgba(0,0,0,.18)" } }, suggestions.map((it, i) => /* @__PURE__ */ React.createElement(
    "div",
    {
      key: it.type + it.key + i,
      onMouseDown: (e) => {
        e.preventDefault();
        submit(it);
      },
      onMouseEnter: () => setSugIdx(i),
      style: { display: "flex", alignItems: "center", gap: 8, padding: "4px 8px", cursor: "pointer", background: i === sugIdx ? "var(--accent-glow, rgba(47,111,179,.15))" : void 0 }
    },
    /* @__PURE__ */ React.createElement("span", { style: { width: 86, flex: "none" } }, /* @__PURE__ */ React.createElement(Badge, { type: it.type })),
    /* @__PURE__ */ React.createElement("span", { style: { flex: 1, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" } }, it.type === "TEXT" ? "\u201C" + it.label + "\u201D" : it.label),
    /* @__PURE__ */ React.createElement("span", { style: { color: C.faint, fontSize: 11, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis", maxWidth: "40%" } }, it.detail)
  ))) : null), /* @__PURE__ */ React.createElement("button", { className: "btn btn-primary", onClick: () => submit() }, "Search"), /* @__PURE__ */ React.createElement("span", { style: { color: C.dim, fontSize: 11.5, whiteSpace: "nowrap" }, title: overview ? `Last check took ${overview.scanMillis} ms \xB7 about ${Math.max(1, Math.round(overview.cacheKb / 1024))} MB kept on the server` : "" }, busy ? "Working\u2026" : status), /* @__PURE__ */ React.createElement("button", { className: "btn", onClick: () => load(true), title: "Read every channel, code template and script again (changes are normally picked up automatically)." }, "Rescan")), error ? /* @__PURE__ */ React.createElement("div", { style: { padding: "6px 12px", color: C.err, borderBottom: "1px solid " + C.line } }, error) : null, /* @__PURE__ */ React.createElement("div", { style: { display: "flex", gap: 2, padding: "6px 10px 0", borderBottom: "1px solid " + C.line } }, tabs.map(([id, label]) => /* @__PURE__ */ React.createElement(
    "button",
    {
      key: id,
      onClick: () => setTab(id),
      className: "btn btn-ghost btn-sm",
      style: { borderRadius: "6px 6px 0 0", borderBottom: tab === id ? "2px solid " + C.accent : "2px solid transparent", color: tab === id ? C.text : C.dim, fontWeight: tab === id ? 600 : 400 }
    },
    label
  ))), /* @__PURE__ */ React.createElement("div", { style: { flex: 1, minHeight: 0, display: "flex", flexDirection: "column" } }, tab === "explore" ? result ? /* @__PURE__ */ React.createElement("div", { style: { display: "flex", flexDirection: "column", flex: 1, minHeight: 0 } }, /* @__PURE__ */ React.createElement("div", { style: { display: "flex", alignItems: "center", gap: 10, padding: "10px 12px" } }, /* @__PURE__ */ React.createElement("button", { className: "btn btn-sm", disabled: history.i <= 0, onClick: () => go(-1), title: "Back" }, "\u2039"), /* @__PURE__ */ React.createElement("button", { className: "btn btn-sm", disabled: history.i >= history.list.length - 1, onClick: () => go(1), title: "Forward" }, "\u203A"), /* @__PURE__ */ React.createElement("div", { style: { flex: 1, minWidth: 0 } }, /* @__PURE__ */ React.createElement("div", { style: { display: "flex", alignItems: "center", gap: 8 } }, /* @__PURE__ */ React.createElement(Badge, { type: result.type }), /* @__PURE__ */ React.createElement("span", { style: { fontSize: 16, fontWeight: 600 } }, result.type === "TEXT" ? "\u201C" + result.title + "\u201D" : result.type === "FUNCTION" ? result.title + "()" : result.title)), /* @__PURE__ */ React.createElement("div", { style: { color: C.dim, fontSize: 12, marginTop: 2 } }, result.subtitle, " \xB7 ", plural(result.rows.filter((r) => r.group !== "Problems").length, "place"), result.rows.some((r) => r.group === "Problems") ? " \xB7 " + plural(result.rows.filter((r) => r.group === "Problems").length, "problem") : "", result.truncated ? " \xB7 only the first " + result.rows.length + " are shown" : "")), result.type === "CHANNEL" ? /* @__PURE__ */ React.createElement(React.Fragment, null, /* @__PURE__ */ React.createElement("button", { className: "btn btn-sm", onClick: () => platform.router.navigate(`/channels/${encodeURIComponent(result.key)}/edit`) }, "Open channel"), /* @__PURE__ */ React.createElement("button", { className: "btn btn-sm", onClick: () => {
    setFocus(result.key);
    setTab("graph");
  } }, "Show in flow graph")) : null), /* @__PURE__ */ React.createElement(
    ResultTree,
    {
      rows: sortedResultRows,
      grouping: exploreGrouping(result),
      actions,
      columns: { howTitle: "How", how: (r) => r.group === "Problems" ? str(r.key) : r.group === "Deploy dependencies" || r.group === "Shared map variables" ? "" : str(r.relation) },
      empty: result.type === "TEXT" ? "The text was not found in any channel, code template or global script." : "Nothing uses it \u2013 no channel, code template or global script refers to it."
    }
  )) : /* @__PURE__ */ React.createElement(Welcome, { overview, compact, setTab, explore: (id) => query("CHANNEL", id) }) : null, tab === "graph" ? /* @__PURE__ */ React.createElement(FlowGraph, { overview, focus, setFocus, depth, setDepth, selected: result && result.type === "CHANNEL" ? result.key : null, onExplore: (id) => query("CHANNEL", id) }) : null, tab === "problems" ? /* @__PURE__ */ React.createElement("div", { style: { display: "flex", flexDirection: "column", flex: 1, minHeight: 0 } }, /* @__PURE__ */ React.createElement("div", { style: { display: "flex", alignItems: "center", gap: 10, padding: "6px 10px", flexWrap: "wrap" } }, /* @__PURE__ */ React.createElement("input", { type: "search", placeholder: "Filter", value: pFilter, onChange: (e) => setPFilter(e.target.value), style: { width: 220, flex: "none" } }), /* @__PURE__ */ React.createElement("label", { style: { fontSize: 12 } }, /* @__PURE__ */ React.createElement("input", { type: "checkbox", checked: showErr, onChange: (e) => setShowErr(e.target.checked) }), " Errors"), /* @__PURE__ */ React.createElement("label", { style: { fontSize: 12 } }, /* @__PURE__ */ React.createElement("input", { type: "checkbox", checked: showWarn, onChange: (e) => setShowWarn(e.target.checked) }), " Warnings"), /* @__PURE__ */ React.createElement("span", { style: { color: C.dim, fontSize: 11.5 } }, "Errors fail at runtime (missing key, channel, function); warnings may be fine (e.g. a variable set outside Mirth).")), /* @__PURE__ */ React.createElement(ResultTree, { rows: problems, grouping: reportGrouping, actions, columns: { howTitle: "Name", how: (r) => str(r.key) }, empty: report ? "No broken references found." : "Loading\u2026" })) : null, tab === "unused" ? /* @__PURE__ */ React.createElement("div", { style: { display: "flex", flexDirection: "column", flex: 1, minHeight: 0 } }, /* @__PURE__ */ React.createElement("div", { style: { display: "flex", alignItems: "center", gap: 10, padding: "6px 10px", flexWrap: "wrap" } }, /* @__PURE__ */ React.createElement("input", { type: "search", placeholder: "Filter", value: uFilter, onChange: (e) => setUFilter(e.target.value), style: { width: 220, flex: "none" } }), /* @__PURE__ */ React.createElement("span", { style: { color: C.dim, fontSize: 11.5 } }, "Candidates for clean-up. Check before deleting: some names may be used outside Mirth or built at runtime.")), /* @__PURE__ */ React.createElement(
    ResultTree,
    {
      rows: unused,
      grouping: reportGrouping,
      actions,
      columns: { howTitle: "Name", how: (r) => r.ownerType === "Configuration Map" || r.category === "Channel nobody sends to" ? "" : str(r.key) },
      empty: report ? "Nothing unused found." : "Loading\u2026"
    }
  )) : null));
}
function Welcome({ overview, compact, setTab, explore }) {
  if (!overview) return /* @__PURE__ */ React.createElement("div", { style: { padding: 30, color: C.dim } }, "Reading channels, code templates and scripts\u2026");
  const links = overview.edges.filter((e) => e.kind !== "Deploy dependency" && e.kind !== "Shared map variable").length;
  const deg = {};
  overview.edges.forEach((e) => {
    if (e.kind === "Deploy dependency") return;
    (deg[e.fromId] = deg[e.fromId] || [0, 0])[1]++;
    if (e.toId) (deg[e.toId] = deg[e.toId] || [0, 0])[0]++;
  });
  const top = Object.keys(deg).sort((a, b) => deg[b][0] + deg[b][1] - deg[a][0] - deg[a][1]).slice(0, 8);
  const byId = {};
  overview.channels.forEach((n) => byId[n.id] = n);
  const tile = (v, label, color, t) => /* @__PURE__ */ React.createElement("div", { onClick: () => setTab(t), style: { flex: 1, minWidth: 140, cursor: "pointer", border: "1px solid " + C.line, borderLeft: "4px solid " + color, borderRadius: 6, padding: "10px 14px", background: C.bg1 } }, /* @__PURE__ */ React.createElement("div", { style: { fontSize: 24, fontWeight: 700, color } }, v), /* @__PURE__ */ React.createElement("div", { style: { fontSize: 11.5, color: C.dim } }, label));
  return /* @__PURE__ */ React.createElement("div", { style: { padding: "20px 24px", overflow: "auto" } }, /* @__PURE__ */ React.createElement("div", { style: { fontSize: 18, fontWeight: 700 } }, "What depends on what?"), /* @__PURE__ */ React.createElement("div", { style: { color: C.dim, margin: "6px 0 18px", maxWidth: 900 } }, "Type the name of a channel, a Configuration Map key, a code template function or a map variable above and pick it from the list \u2013 or press Enter to find any text in every script and connector setting. Double-click a result to open that place in the editor."), /* @__PURE__ */ React.createElement("div", { style: { display: "flex", gap: 12, flexWrap: "wrap", marginBottom: 22 } }, tile(overview.channels.length, "channels", "#2f6fb3", "graph"), tile(links, "message links between channels", "#7b3fb5", "graph"), !compact ? tile(overview.errors, "errors (broken references)", overview.errors ? "#c62828" : "#2e7d32", "problems") : null, !compact ? tile(overview.warnings, "warnings", overview.warnings ? "#e08a00" : "#2e7d32", "problems") : null, !compact ? tile(overview.unused, "unused items", "#1e76c8", "unused") : null), top.length ? /* @__PURE__ */ React.createElement("div", { style: { fontWeight: 600, marginBottom: 6 } }, "Most connected channels") : null, top.filter((id) => byId[id]).map((id) => /* @__PURE__ */ React.createElement("div", { key: id, style: { padding: "2px 0" } }, /* @__PURE__ */ React.createElement("a", { href: "#", onClick: (e) => {
    e.preventDefault();
    explore(id);
  }, style: { color: C.accent } }, byId[id].name), /* @__PURE__ */ React.createElement("span", { style: { color: C.dim } }, "  receives from ", deg[id][0], ", sends to ", deg[id][1]))));
}
function SettingsPanel({ setTasks }) {
  return /* @__PURE__ */ React.createElement(Explorer, { setTasks });
}
function ChannelView({ query }) {
  const id = query && query.channel ? String(query.channel) : null;
  return /* @__PURE__ */ React.createElement("div", { className: "view", style: { display: "flex", flexDirection: "column", height: "100%" } }, /* @__PURE__ */ React.createElement(Explorer, { key: id || "all", initialChannel: id }));
}
function register(p) {
  platform = p;
  React = p.React;
  p.registerSettingsPanel({ id: "dependency-explorer", label: "Dependency Explorer", order: 60, component: SettingsPanel });
  p.registerView("/dependency-explorer", p.reactView(ChannelView), { title: "Dependency Explorer" });
  p.registerChannelAction({
    id: "depexplorer-view-dependencies",
    label: "View Dependencies",
    icon: "channels",
    order: 90,
    onInvoke: (channel) => p.router.navigate("/dependency-explorer?channel=" + encodeURIComponent(channel.id))
  });
  if (p.registerCommand) {
    p.registerCommand({ id: "depexplorer:open", label: "Dependency Explorer", icon: "channels", section: "Plugins", path: "/dependency-explorer" });
  }
}
export {
  register
};
