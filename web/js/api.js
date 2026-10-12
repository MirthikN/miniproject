// Change BASE to wherever your Java server runs
const BASE = '/api';
const $ = s => document.querySelector(s);
const esc = v => String(v ?? '').replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));

async function api(path, method = 'GET', body) {
  const r = await fetch(BASE + path, { method, headers: { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined });
  const t = await r.text(); let d = null;
  try { d = t ? JSON.parse(t) : null; } catch { d = t; }
  if (!r.ok) throw new Error((d && d.error) || t || r.statusText);
  return d;
}
let tt;
function toast(msg, bad) {
  const e = $('#toast'); e.textContent = msg; e.className = 'show' + (bad ? ' bad' : '');
  clearTimeout(tt); tt = setTimeout(() => e.className = '', 3500);
}
async function run(fn, ok) {            // call API, show success/error toast
  try { const r = await fn(); if (ok) toast(ok); return r; }
  catch (e) { toast(e.message.includes('fetch') ? 'Cannot reach the server. Is the Java backend running?' : e.message, true); }
}
function badge(v) { return `<span class="badge ${esc(v)}">${esc(v)}</span>`; }
function table(cols, rows, act) {
  if (!rows || !rows.length) return '<p class="muted">Nothing here yet.</p>';
  const h = cols.map(c => `<th>${c[0]}</th>`).join('') + (act ? '<th></th>' : '');
  const b = rows.map(r => '<tr>' + cols.map(c => {
    const v = r[c[1]];
    return `<td>${['status', 'priority', 'type'].includes(c[1]) ? badge(v) : typeof v === 'boolean' ? (v ? 'Yes' : 'No') : esc(v)}</td>`;
  }).join('') + (act ? `<td>${act(r)}</td>` : '') + '</tr>').join('');
  return `<div class="wrap"><table><thead><tr>${h}</tr></thead><tbody>${b}</tbody></table></div>`;
}
// fields: [key, label, type('text'|'number'|'checkbox'|'select'), options[]]
function formHtml(id, fields, btn) {
  const f = fields.map(([k, l, t = 'text', o]) =>
    t === 'checkbox' ? `<label class="chk"><input type="checkbox" name="${k}">${l}</label>`
    : t === 'select' ? `<label>${l}<select name="${k}">${o.map(x => `<option>${x}</option>`).join('')}</select></label>`
    : `<label>${l}<input name="${k}" type="${t}" required ${t === 'number' ? 'min="0"' : ''}></label>`).join('');
  return `<form class="f" id="${id}">${f}<button class="b">${btn}</button></form>`;
}
function formData(form) {
  const o = {};
  new FormData(form).forEach((v, k) => o[k] = form.elements[k].type === 'number' ? Number(v) : v);
  form.querySelectorAll('input[type=checkbox]').forEach(c => o[c.name] = c.checked);
  return o;
}
