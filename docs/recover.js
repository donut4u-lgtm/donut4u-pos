// Adds a yellow "missing products" bar to the website when products that were sold before no longer exist.
import { initializeApp } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js";
import { getAuth, onAuthStateChanged } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-auth.js";
import { getFirestore, collection, getDocs, writeBatch, doc } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-firestore.js";

const HIDE_KEY = "donut4u_recover_hide";
let cfg = null;
try { cfg = JSON.parse(localStorage.getItem("donut4u_cfg") || "null"); } catch (e) {}
if (cfg) start(cfg);

function start(c) {
  const app = initializeApp({ apiKey: c.apiKey, projectId: c.projectId, appId: c.appId, authDomain: c.projectId + ".firebaseapp.com" });
  const auth = getAuth(app);
  const db = getFirestore(app);
  let done = false;
  onAuthStateChanged(auth, user => {
    if (!user || done || !navigator.onLine) return;
    done = true;
    check(db, user.uid).catch(() => { done = false; });
  });
}

const round2 = v => Math.round(v * 100) / 100;

// Products that were sold before but no longer exist, rebuilt from the sales history.
function buildMissing(sales, products) {
  const have = new Set(products.map(p => Number(p.id)));
  const used = new Set(products.map(p => String(p.barcode || "")).filter(Boolean));
  const found = new Map();
  const ordered = [...sales].sort((a, b) => (Number(a.timestamp) || 0) - (Number(b.timestamp) || 0));
  for (const sale of ordered) {
    const whole = sale.type === "wholesale";
    for (const it of (Array.isArray(sale.items) ? sale.items : [])) {
      const id = Number(it.productId);
      if (!Number.isFinite(id) || id <= 0 || have.has(id)) continue;
      const price = Number(it.price) || 0;
      let f = found.get(id);
      if (!f) { f = { id, name: "", retail: null, wholesale: null }; found.set(id, f); }
      if (it.name) f.name = String(it.name);
      if (whole) f.wholesale = price; else f.retail = price;
    }
  }
  const out = [];
  for (const f of [...found.values()].sort((a, b) => a.name.localeCompare(b.name))) {
    let code = "P" + String(f.id).padStart(4, "0");
    while (used.has(code)) code += "-r";
    used.add(code);
    out.push({
      id: f.id, barcode: code, name: f.name || ("Product " + f.id),
      price: round2(f.retail != null ? f.retail : (f.wholesale || 0)),
      wholesale: f.retail != null && f.wholesale != null ? round2(f.wholesale) : 0
    });
  }
  return out;
}

async function check(db, uid) {
  const [salesSnap, prodSnap, labelSnap] = await Promise.all([
    getDocs(collection(db, "users", uid, "sales")),
    getDocs(collection(db, "users", uid, "products")),
    getDocs(collection(db, "users", uid, "labels"))
  ]);
  const products = prodSnap.docs.map(d => ({ ...d.data(), id: Number(d.data().id ?? d.id) }));
  const missing = buildMissing(salesSnap.docs.map(d => d.data()), products);
  if (!missing.length) return;
  const sig = missing.map(m => m.id).sort((a, b) => a - b).join(",");
  try { if (localStorage.getItem(HIDE_KEY) === sig) return; } catch (e) {}

  const n = missing.length;
  const bar = document.createElement("div");
  bar.id = "recoverBar";
  bar.style.cssText = "position:fixed;left:0;right:0;top:60px;z-index:8;background:#fff3cd;color:#4d3b00;padding:10px 14px;display:flex;gap:10px;align-items:center;flex-wrap:wrap;box-shadow:0 6px 18px rgba(0,0,0,.35);font:600 15px system-ui,sans-serif";
  const msg = document.createElement("span");
  msg.style.cssText = "flex:1;min-width:200px";
  msg.textContent = n + " product" + (n === 1 ? "" : "s") + " you sold before " + (n === 1 ? "is" : "are") + " missing from your shop. Add " + (n === 1 ? "it" : "them") + " back?";
  const mkBtn = (text, solid) => {
    const b = document.createElement("button");
    b.textContent = text;
    b.style.cssText = "border:2px solid #4d3b00;border-radius:10px;padding:8px 14px;font:inherit;font-weight:700;cursor:pointer;" + (solid ? "background:#4d3b00;color:#fff" : "background:transparent;color:#4d3b00");
    return b;
  };
  const add = mkBtn("Add back", true), hide = mkBtn("No, hide", false);
  hide.onclick = () => { try { localStorage.setItem(HIDE_KEY, sig); } catch (e) {} bar.remove(); };
  add.onclick = async () => {
    add.disabled = hide.disabled = true;
    msg.textContent = "Adding back...";
    try {
      const writes = missing.map(m => ({
        ref: doc(db, "users", uid, "products", String(m.id)),
        data: { id: m.id, barcode: m.barcode, name: m.name, price: m.price, wholesale: m.wholesale, stock: 0, label: "Other", createdAt: Date.now() }
      }));
      if (!labelSnap.docs.some(d => d.data().name === "Other")) {
        writes.push({ ref: doc(db, "users", uid, "labels", "l_Other"), data: { name: "Other", colorIndex: 11 } });
      }
      for (let i = 0; i < writes.length; i += 400) {
        const b = writeBatch(db);
        writes.slice(i, i + 400).forEach(w => b.set(w.ref, w.data));
        await b.commit();
      }
      msg.textContent = "Done. " + n + " product" + (n === 1 ? "" : "s") + " added back. Open Products to set their labels and stock.";
      add.remove(); hide.textContent = "OK";
      hide.disabled = false;
    } catch (e) {
      add.disabled = hide.disabled = false;
      msg.textContent = "Could not add them back: " + e.message;
    }
  };
  bar.append(msg, add, hide);
  document.body.append(bar);
}
