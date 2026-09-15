import { ServerConfig, type ServerEntry } from './plugins/serverConfig';

// Kept in step with hasht-desktop's picker.js.
const HOSTED_URL = 'chat.hasht.net';
const HOSTED_NAME = 'Hasht';

const el = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;

const headline = el('headline');
const tagline = el('tagline');
const addHeading = el('add-heading');
const serversSection = el('servers-section');
const serversEl = el<HTMLUListElement>('servers');
const errorEl = el('error');
const urlInput = el<HTMLInputElement>('url');
const nameInput = el<HTMLInputElement>('name');
const hostedBtn = el<HTMLButtonElement>('hosted-connect');
const selfhostedForm = el<HTMLFormElement>('selfhosted-form');
const selfhostedBtn = el<HTMLButtonElement>('selfhosted-connect');

// Only one choice card is expanded at a time.
for (const card of document.querySelectorAll<HTMLElement>('.card')) {
  card.querySelector('.card-head')?.addEventListener('click', () => {
    for (const other of document.querySelectorAll('.card')) {
      other.classList.toggle('selected', other === card);
    }
    if (card.dataset.choice === 'selfhosted') urlInput.focus();
  });
}

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

function renderServer(server: ServerEntry, activeId: string | null): HTMLLIElement {
  const row = document.createElement('li');
  row.className = 'server';
  row.dataset.active = String(server.id === activeId);

  const meta = document.createElement('button');
  meta.type = 'button';
  meta.className = 'meta';
  meta.innerHTML = '<div class="name"></div><div class="url"></div>';
  meta.querySelector('.name')!.textContent = server.name;
  meta.querySelector('.url')!.textContent = new URL(server.url).host;
  meta.addEventListener('click', () => {
    void ServerConfig.connect({ id: server.id });
  });

  const remove = document.createElement('button');
  remove.type = 'button';
  remove.className = 'icon-btn danger';
  remove.textContent = 'Remove';
  remove.addEventListener('click', async () => {
    await ServerConfig.remove({ id: server.id });
    await refresh();
  });

  row.append(meta, remove);
  return row;
}

async function refresh() {
  let servers: ServerEntry[] = [];
  let activeId: string | null = null;
  try {
    ({ servers, activeId } = await ServerConfig.list());
  } catch (error) {
    // Without the native half there is nothing to connect to, and a silent
    // failure leaves a half-drawn wizard with no explanation.
    errorEl.textContent = `Native bridge unavailable: ${
      error instanceof Error ? error.message : String(error)
    }`;
  }

  const hasServers = servers.length > 0;

  serversSection.hidden = !hasServers;
  headline.textContent = hasServers ? 'Hasht' : 'Welcome to Hasht';
  tagline.textContent = hasServers
    ? 'Pick a server or add another.'
    : 'Choose how you want to connect.';
  addHeading.textContent = hasServers ? 'Add a server' : 'Connect';

  // On first run, nudge toward the hosted option; once the user has servers,
  // nothing is pre-expanded.
  if (!hasServers && !document.querySelector('.card.selected')) {
    el('card-hosted').classList.add('selected');
  }

  serversEl.replaceChildren(...servers.map((server) => renderServer(server, activeId)));
}

void refresh();



