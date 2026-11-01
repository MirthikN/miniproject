/**
 * Smart Water Distribution Management - Shared API & UI Helpers
 * Framework-free, Vanilla ES2017+ JavaScript
 */

// Java backend API base endpoint
const BASE = '/api';

// DOM utility selectors
const $ = s => document.querySelector(s);
const $$ = s => document.querySelectorAll(s);

// Safe HTML character escaping for untrusted server data
const esc = v => String(v ?? '').replace(/[&<>'"]/g, c => ({
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  "'": '&#39;',
  '"': '&quot;'
}[c]));

/**
 * Perform an HTTP request against the Java API backend.
 * @param {string} path - API endpoint path (e.g. '/sources')
 * @param {string} method - HTTP method ('GET', 'POST', 'DELETE')
 * @param {object|null} body - Optional JSON payload
 * @returns {Promise<any>} Parsed response data
 */
async function api(path, method = 'GET', body) {
  const r = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined
  });
  const t = await r.text();
  let d = null;
  try {
    d = t ? JSON.parse(t) : null;
  } catch {
    d = t;
  }
  if (!r.ok) {
    throw new Error((d && d.error) || t || r.statusText);
  }
  return d;
}

// Toast notification management with accessible ARIA live regions
let toastTimeout;
function toast(msg, bad = false) {
  let e = $('#toast');
  if (!e) {
    e = document.createElement('div');
    e.id = 'toast';
    document.body.appendChild(e);
  }
  e.setAttribute('role', 'status');
  e.setAttribute('aria-live', bad ? 'assertive' : 'polite');
  e.textContent = msg;
  e.className = 'show' + (bad ? ' bad' : '');
  
  clearTimeout(toastTimeout);
  toastTimeout = setTimeout(() => {
    e.className = '';
  }, 3500);
}

/**
 * Execute an async operation with automatic feedback and button busy state handling.
 * @param {Function} fn - Async operation to execute
 * @param {string|null} ok - Optional toast message on success
 * @param {HTMLElement|null} btn - Optional submit button to disable & show spinner
 */
async function run(fn, ok, btn) {
  if (btn) {
    btn.disabled = true;
    btn.classList.add('is-busy');
    btn.setAttribute('aria-busy', 'true');
  }
  try {
    const r = await fn();
    if (ok) toast(ok);
    return r;
  } catch (e) {
    const isNetworkError = e.message.includes('fetch') || e.message.includes('NetworkError') || e.message.includes('Failed to fetch');
    const msg = isNetworkError ? 'Cannot reach the server. Is the Java backend running?' : e.message;
    toast(msg, true);
    return null;
  } finally {
    if (btn) {
      btn.disabled = false;
      btn.classList.remove('is-busy');
      btn.removeAttribute('aria-busy');
    }
  }
}

/**
 * Render an accessible status badge.
 * @param {string} v - Status value
 */
function badge(v) {
  return `<span class="badge ${esc(v)}">${esc(v)}</span>`;
}

/**
 * Generate an accessible, horizontally contained table.
 * @param {Array<[string, string]>} cols - Column definitions [headerText, propertyKey]
 * @param {Array<object>} rows - Array of record objects
 * @param {Function|null} act - Optional action button renderer per row
 * @param {{wrap?: boolean}} opts - wrap: let cell text wrap instead of scrolling sideways
 */
function table(cols, rows, act, opts = {}) {
  if (!rows || !rows.length) {
    return `<div class="empty-state" role="status">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <circle cx="12" cy="12" r="10"/><path d="M8 12h8"/>
      </svg>
      <p>No records found.</p>
    </div>`;
  }
  const h = cols.map(c => `<th scope="col">${esc(c[0])}</th>`).join('') + (act ? '<th scope="col"><span class="sr-only">Actions</span></th>' : '');
  const b = rows.map(r => '<tr>' + cols.map(c => {
    const k = c[1];
    const v = r[k];
    let cellContent = '';
    if (['status', 'priority', 'type'].includes(k)) {
      cellContent = badge(v);
    } else if (typeof v === 'boolean') {
      cellContent = v ? 'Yes' : 'No';
    } else if (typeof v === 'number') {
      cellContent = v.toLocaleString();
    } else {
      cellContent = esc(v);
    }
    return `<td>${cellContent}</td>`;
  }).join('') + (act ? `<td>${act(r)}</td>` : '') + '</tr>').join('');

  return `<div class="wrap${opts.wrap ? ' wrap-text' : ''}" tabindex="0" role="region" aria-label="Data table">
    <table>
      <thead><tr>${h}</tr></thead>
      <tbody>${b}</tbody>
    </table>
  </div>`;
}

/**
 * Generate semantic form HTML with explicit label-to-input association.
 * @param {string} id - Form ID
 * @param {Array} fields - Array of [key, label, type, options]
 * @param {string} btn - Submit button label
 */
function formHtml(id, fields, btn) {
  const f = fields.map(([k, l, t = 'text', o]) => {
    const inputId = `${id}_${k}`;
    if (t === 'checkbox') {
      return `<label class="chk" for="${inputId}">
        <input type="checkbox" id="${inputId}" name="${k}">
        <span>${esc(l)}</span>
      </label>`;
    }
    if (t === 'select') {
      // options are either plain strings or [value, label] pairs
      const opts = (o || []).map(x => {
        const [v, lab] = Array.isArray(x) ? x : [x, x];
        return `<option value="${esc(v)}">${esc(lab)}</option>`;
      }).join('');
      return `<label for="${inputId}">
        <span>${esc(l)}</span>
        <select id="${inputId}" name="${k}">${opts}</select>
      </label>`;
    }
    return `<label for="${inputId}">
      <span>${esc(l)}</span>
      <input id="${inputId}" name="${k}" type="${t}" required ${t === 'number' ? 'min="0" step="any"' : ''} autocomplete="off">
    </label>`;
  }).join('');

  return `<form class="f" id="${id}">${f}<button class="b" type="submit">${esc(btn)}</button></form>`;
}

/**
 * Extract typed data from a form element.
 * @param {HTMLFormElement} form
 * @returns {object} Form payload object
 */
function formData(form) {
  const o = {};
  new FormData(form).forEach((v, k) => {
    const el = form.elements[k];
    const isNum = el && el.type === 'number';
    o[k] = isNum ? (v === '' ? 0 : Number(v)) : v;
  });
  form.querySelectorAll('input[type="checkbox"]').forEach(c => {
    o[c.name] = c.checked;
  });
  return o;
}

/**
 * Accessible Modal Replacement for native prompt().
 * Returns a Promise that resolves with the entered string, or null if canceled.
 */
function modalPrompt({ title, label, placeholder = '', defaultValue = '', okText = 'Submit', cancelText = 'Cancel' }) {
  return new Promise(resolve => {
    const dialog = document.createElement('dialog');
    dialog.className = 'app-dialog';
    dialog.setAttribute('aria-labelledby', 'dialog-prompt-title');

    dialog.innerHTML = `
      <div class="dialog-card">
        <div class="dialog-header">
          <h3 id="dialog-prompt-title">${esc(title)}</h3>
        </div>
        <form method="dialog" id="dialog-prompt-form">
          <div class="dialog-body">
            <label for="dialog-prompt-input">
              <span>${esc(label)}</span>
              <input type="text" id="dialog-prompt-input" required placeholder="${esc(placeholder)}" value="${esc(defaultValue)}" autocomplete="off">
            </label>
          </div>
          <div class="dialog-footer">
            <button type="button" class="b alt" id="dialog-prompt-cancel">${esc(cancelText)}</button>
            <button type="submit" class="b" id="dialog-prompt-submit">${esc(okText)}</button>
          </div>
        </form>
      </div>
    `;

    document.body.appendChild(dialog);
    const input = dialog.querySelector('#dialog-prompt-input');
    const form = dialog.querySelector('#dialog-prompt-form');
    const cancelBtn = dialog.querySelector('#dialog-prompt-cancel');

    const cleanUp = result => {
      dialog.close();
      dialog.remove();
      resolve(result);
    };

    form.onsubmit = e => {
      e.preventDefault();
      const val = input.value.trim();
      if (!val) return;
      cleanUp(val);
    };

    cancelBtn.onclick = () => cleanUp(null);
    dialog.oncancel = () => cleanUp(null); // Handles Escape key

    dialog.showModal();
    input.focus();
  });
}

/**
 * Accessible Modal Replacement for native confirm().
 * Returns a Promise that resolves with true (confirmed) or false (canceled).
 */
function modalConfirm({ title, message, okText = 'Confirm', cancelText = 'Cancel', danger = false }) {
  return new Promise(resolve => {
    const dialog = document.createElement('dialog');
    dialog.className = 'app-dialog';
    dialog.setAttribute('aria-labelledby', 'dialog-confirm-title');

    dialog.innerHTML = `
      <div class="dialog-card">
        <div class="dialog-header">
          <h3 id="dialog-confirm-title">${esc(title)}</h3>
        </div>
        <div class="dialog-body">
          <p>${esc(message)}</p>
        </div>
        <div class="dialog-footer">
          <button type="button" class="b alt" id="dialog-confirm-cancel">${esc(cancelText)}</button>
          <button type="button" class="b ${danger ? 'danger' : ''}" id="dialog-confirm-ok">${esc(okText)}</button>
        </div>
      </div>
    `;

    document.body.appendChild(dialog);
    const cancelBtn = dialog.querySelector('#dialog-confirm-cancel');
    const okBtn = dialog.querySelector('#dialog-confirm-ok');

    const cleanUp = result => {
      dialog.close();
      dialog.remove();
      resolve(result);
    };

    okBtn.onclick = () => cleanUp(true);
    cancelBtn.onclick = () => cleanUp(false);
    dialog.oncancel = () => cleanUp(false); // Handles Escape key

    dialog.showModal();
    (danger ? cancelBtn : okBtn).focus();
  });
}

/**
 * Skeleton Loader Helpers for data fetching feedback
 */
function skeletonCards(count = 6) {
  let cards = '';
  for (let i = 0; i < count; i++) {
    cards += `<div class="stat skeleton skeleton-stat" aria-hidden="true"></div>`;
  }
  return `<div class="cards" aria-label="Loading statistics...">${cards}</div>`;
}

function skeletonTable(rows = 3) {
  let r = '';
  for (let i = 0; i < rows; i++) {
    r += `<div class="skeleton skeleton-table-row" aria-hidden="true"></div>`;
  }
  return `<div class="panel" aria-label="Loading data...">${r}</div>`;
}

/**
 * Day-wise report: one block per day with the water requests, allocations and maintenance events of that day.
 * @param {object} rep - Response of GET /report
 * @param {boolean} showArea - Show an Area column (dam view)
 */
function reportHtml(rep, showArea) {
  const days = (rep && rep.days) || [];
  if (!days.length) {
    return `<div class="panel"><p class="muted">No activity recorded for this period.</p></div>`;
  }
  const area = showArea ? [['Area', 'areaId']] : [];
  const sub = (title, rows, cols) => `
    <h3 style="margin:1rem 0 0.4rem;font-size:0.95rem">${esc(title)} (${rows.length})</h3>
    ${rows.length ? table(cols, rows, null, { wrap: true }) : '<p class="muted">None on this day.</p>'}`;
  return days.map(d => `
    <section class="panel" aria-label="Report for ${esc(d.date || 'Day ' + d.day)}">
      <h2>${d.date ? esc(d.date) + ' &middot; ' : ''}Day ${d.day}</h2>
      ${sub('Water requests', d.requests, [['Request', 'requestId'], ...area, ['Required (L)', 'requiredWater'],
        ['Emergency', 'emergency'], ['Priority', 'priority'], ['Allocated (L)', 'allocatedWater'],
        ['Unmet (L)', 'unmetWater'], ['Status', 'status']])}
      ${sub('Water allocations', d.allocations, [['Request', 'requestId'], ...area, ['What happened', 'message']])}
      ${sub('Maintenance requests', d.maintenance, [['Ticket', 'maintenanceId'], ['Pipeline', 'pipelineId'],
        ['Event', 'event'], ['Issue', 'issue'], ['Reported by', 'reportedBy'], ['Note', 'note']])}
    </section>`).join('');
}

/** Whole days between two YYYY-MM-DD strings (b minus a), or null if either is not a date. */
function daysBetween(a, b) {
  const p = x => { const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(x || ''); return m ? Date.UTC(+m[1], +m[2] - 1, +m[3]) : null; };
  const x = p(a), y = p(b);
  return x === null || y === null ? null : Math.round((y - x) / 86400000);
}