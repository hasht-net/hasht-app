import '@fontsource-variable/manrope';
import { ServerConfig, type ServerEntry } from './plugins/serverConfig';

// hasht-desktop's picker.js mirrors this file. The usernames, default and "+"
// below haven't reached it yet; port them there when touching either.
const HOSTED_URL = 'chat.hasht.net';
const HOSTED_NAME = 'Hasht';

const el = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;

const headline = el('headline');
const tagline = el('tagline');
const addHeading = el('add-heading');
const serversSection = el('servers-section');
const serversEl = el<HTMLUListElement>('servers');
const addSection = el('add-section');
const addToggle = el<HTMLButtonElement>('add-toggle');
const errorEl = el('error');
const urlInput = el<HTMLInputElement>('url');
const nameInput = el<HTMLInputElement>('name');
const hostedBtn = el<HTMLButtonElement>('hosted-connect');
const selfhostedForm = el<HTMLFormElement>('selfhosted-form');
const selfhostedBtn = el<HTMLButtonElement>('selfhosted-connect');

// iOS uses resize:none: the native inset is the only indication that the
// keyboard covers the picker. Android/browser keyboards can shrink the visual
// viewport instead. Recheck on focus too, when moving between fields while up.
function revealFocusedInput() {
  requestAnimationFrame(() => {
    const input = document.activeElement;
    if (!(input instanceof HTMLInputElement)) return;
    const viewport = window.visualViewport;
    if (viewport && viewport.scale > 1.001) return;
    const inset = Number.parseFloat(getComputedStyle(document.documentElement)
      .getPropertyValue('--hasht-keyboard-inset')) || 0;
    const top = viewport?.offsetTop ?? 0;
    const bottom = Math.min(top + (viewport?.height ?? window.innerHeight), window.innerHeight - inset);
    const bounds = input.getBoundingClientRect();
    if (bounds.bottom > bottom - 16) window.scrollBy(0, bounds.bottom - bottom + 16);
    else if (bounds.top < top + 16) window.scrollBy(0, bounds.top - top - 16);
  });
}
document.addEventListener('focusin', revealFocusedInput);
window.addEventListener('hasht:keyboard', revealFocusedInput);
window.addEventListener('resize', revealFocusedInput);
window.visualViewport?.addEventListener('resize', revealFocusedInput);

// Only one choice card is expanded at a time.
function selectCard(card: HTMLElement | null) {
  for (const other of document.querySelectorAll('.card')) {
    other.classList.toggle('selected', other === card);
  }
}

for (const card of document.querySelectorAll<HTMLElement>('.card')) {
  card.querySelector('.card-head')?.addEventListener('click', () => {
    selectCard(card);
    if (card.dataset.choice === 'selfhosted') urlInput.focus();
  });
}

// With servers saved, adding one is behind the "+" on "Your servers".
let adding = false;
function setAdding(open: boolean) {
  adding = open;
  addToggle.setAttribute('aria-expanded', String(open));
  addToggle.setAttribute('aria-label', open ? 'Close' : 'Add a server');
  if (!serversSection.hidden) addSection.hidden = !open;
  if (!open) selectCard(null);
}
addToggle.addEventListener('click', () => setAdding(!adding));

async function withBusy(btn: HTMLButtonElement, fn: () => Promise<void>) {
  errorEl.textContent = '';
  btn.disabled = true;
  try {
    await fn();
  } catch (error) {
    errorEl.textContent = error instanceof Error ? error.message : "Couldn't reach that server.";
  } finally {
    btn.disabled = false;
  }
}

/** Bare host → https://host. Throws on input no amount of guessing fixes. */
function normalize(raw: string): string {
  const trimmed = raw.trim().replace(/\/+$/, '');
  if (!trimmed) throw new Error("Enter your server's address.");
  const url = new URL(/^https?:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`);
  return url.origin;
}

async function addServer(rawUrl: string, name?: string) {
  const origin = normalize(rawUrl);
  // Native, not fetch — see ServerConfigPlugin.probe.
  const { name: instanceName } = await ServerConfig.probe({ url: origin });
  await ServerConfig.add({ url: origin, name: name ?? instanceName });
  setAdding(false);
}

hostedBtn.addEventListener('click', () =>
  withBusy(hostedBtn, () => addServer(HOSTED_URL, HOSTED_NAME)),
);

selfhostedForm.addEventListener('submit', (event) => {
  event.preventDefault();
  void withBusy(selfhostedBtn, async () => {
    await addServer(urlInput.value, nameInput.value.trim() || undefined);
    urlInput.value = '';
    nameInput.value = '';
  });
});

function h<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  className: string,
  text?: string,
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function button(className: string, text: string, onClick: () => void): HTMLButtonElement {
  const node = h('button', className, text);
  node.type = 'button';
  node.addEventListener('click', onClick);
  return node;
}

function accountLabel(server: ServerEntry): string {
  return server.username ? `@${server.username}` : 'Not signed in';
}

// The row whose actions are showing; one at a time.
let openRow: string | null = null;

function renderServer(server: ServerEntry, activeId: string | null, defaultId: string | null): HTMLLIElement {
  const row = h('li', 'server');
  row.dataset.active = String(server.id === activeId);
  const isDefault = server.id === defaultId;

  const meta = h('button', 'meta');
  meta.type = 'button';
  const title = h('div', 'name-line');
  title.append(h('span', 'name', server.name));
  if (isDefault) title.append(h('span', 'badge', 'Default'));
  const sub = h('div', 'sub');
  sub.append(h('span', server.username ? 'account' : 'account signed-out', accountLabel(server)));
  sub.append(h('span', 'host', new URL(server.url).host));
  meta.append(title, sub);
  meta.setAttribute(
    'aria-label',
    `${server.name}, ${server.displayName ? `${server.displayName}, ` : ''}${accountLabel(server)}, ${new URL(server.url).host}${isDefault ? ', default' : ''}`,
  );
  meta.addEventListener('click', () => void ServerConfig.connect({ id: server.id }));

  const more = button('icon-btn more', '', () => {
    openRow = openRow === server.id ? null : server.id;
    void refresh();
  });
  more.setAttribute('aria-label', `Options for ${server.name}`);
  more.setAttribute('aria-expanded', String(openRow === server.id));
  more.innerHTML =
    '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="5" cy="12" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="19" cy="12" r="1.6"/></svg>';

  const head = h('div', 'server-head');
  head.append(meta, more);
  row.append(head);
  if (openRow === server.id) row.append(renderActions(server, isDefault));
  return row;
}

function renderActions(server: ServerEntry, isDefault: boolean): HTMLElement {
  const actions = h('div', 'actions');
  const close = () => {
    openRow = null;
    void refresh();
  };

  const showMain = () => {
    actions.replaceChildren(
      button('chip', isDefault ? 'Remove default' : 'Make default', async () => {
        await ServerConfig.setDefault({ id: isDefault ? null : server.id });
        close();
      }),
      button('chip', 'Rename', showRename),
      button('chip danger', 'Remove', showRemove),
    );
  };

  const showRename = () => {
    const form = h('form', 'rename');
    const input = h('input', '');
    input.value = server.name;
    input.setAttribute('aria-label', 'Name');
    const save = h('button', 'chip primary-chip', 'Save');
    save.type = 'submit';
    form.append(input, save, button('chip', 'Cancel', showMain));
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      const name = input.value.trim();
      if (!name) return;
      await ServerConfig.rename({ id: server.id, name });
      close();
    });
    actions.replaceChildren(form);
    input.focus();
    input.select();
  };

  const showRemove = () => {
    actions.replaceChildren(
      h('p', 'confirm', `Remove ${server.name} from this device? You'll need to sign in again to add it back.`),
      button('chip danger-fill', 'Remove', async () => {
        await ServerConfig.remove({ id: server.id });
        close();
      }),
      button('chip', 'Cancel', showMain),
    );
  };

  showMain();
  return actions;
}

async function refresh() {
  let servers: ServerEntry[] = [];
  let activeId: string | null = null;
  let defaultId: string | null = null;
  try {
    ({ servers, activeId, defaultId = null } = await ServerConfig.list());
  } catch (error) {
    // Without the native half there is nothing to connect to, and a silent
    // failure leaves a half-drawn wizard with no explanation.
    errorEl.textContent = `Native bridge unavailable: ${
      error instanceof Error ? error.message : String(error)
    }`;
  }

  const hasServers = servers.length > 0;

  serversSection.hidden = !hasServers;
  // First run shows the connect cards straight away; after that they wait
  // behind "+".
  addSection.hidden = hasServers && !adding;
  headline.textContent = hasServers ? 'Hasht' : 'Welcome to Hasht';
  tagline.textContent = hasServers ? 'Pick a server to open.' : 'Choose how you want to connect.';
  addHeading.textContent = hasServers ? 'Add a server' : 'Connect';

  // On first run, nudge toward the hosted option.
  if (!hasServers && !document.querySelector('.card.selected')) {
    el('card-hosted').classList.add('selected');
  }

  if (openRow && !servers.some((s) => s.id === openRow)) openRow = null;
  serversEl.replaceChildren(...servers.map((server) => renderServer(server, activeId, defaultId)));
}

// The bars keep whatever the last instance page set; match them to this page,
// which follows the system theme. Colors are --bg-rail from bootstrap.css.
const dark = matchMedia('(prefers-color-scheme: dark)');
async function paintBars() {
  try {
    await ServerConfig.setTheme({
      theme: dark.matches ? 'dark' : 'light',
      color: dark.matches ? '#0e0f11' : '#e8eaec',
    });
  } catch {
    // Cosmetic: the picker works without it.
  }
}
dark.addEventListener('change', () => void paintBars());
void paintBars();

void refresh();
