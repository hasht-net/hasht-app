import { ServerConfig } from './plugins/serverConfig';

const form = document.getElementById('form') as HTMLFormElement;
const input = document.getElementById('url') as HTMLInputElement;
const submit = document.getElementById('submit') as HTMLButtonElement;
const status = document.getElementById('status') as HTMLParagraphElement;

function setStatus(text: string, state: 'idle' | 'error' = 'idle') {
  status.textContent = text;
  status.dataset.state = state;
}

/** Bare host → https://host; anything already carrying a scheme is left alone. */
function normalize(raw: string): string | null {
  const trimmed = raw.trim().replace(/\/+$/, '');
  if (!trimmed) return null;
  const withScheme = /^https?:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`;
  try {
    const url = new URL(withScheme);
    return url.origin;
  } catch {
    return null;
  }
}

/**
 * Confirms the origin is a Hasht instance before handing the webview over to
 * it — a typo that lands on an unrelated site would otherwise leave the user
 * staring at someone else's page with no way back.
 */
async function probe(origin: string): Promise<{ name: string }> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 8000);
  try {
    const health = await fetch(`${origin}/api/health`, { signal: controller.signal });
    if (!health.ok) throw new Error(`server returned ${health.status}`);
    const body = await health.json();
    if (body?.status !== 'ok') throw new Error('not a Hasht server');

    // Public endpoint — gives us the instance's own name to confirm against.
    const settings = await fetch(`${origin}/api/instance-settings`, { signal: controller.signal });
    const name = settings.ok ? ((await settings.json())?.app_name ?? 'Hasht') : 'Hasht';
    return { name };
  } finally {
    clearTimeout(timeout);
  }
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const origin = normalize(input.value);
  if (!origin) {
    setStatus('That does not look like a valid address.', 'error');
    return;
  }

  submit.disabled = true;
  setStatus(`Contacting ${new URL(origin).host}…`);
  try {
    const { name } = await probe(origin);
    setStatus(`Connecting to ${name}…`);
    await ServerConfig.connect({ url: origin });
  } catch (error) {
    const reason = error instanceof Error ? error.message : 'unknown error';
    setStatus(`Could not reach that server (${reason}).`, 'error');
    submit.disabled = false;
  }
});

// A configured instance skips the picker entirely; native reloads straight
// into it on launch, so reaching here with a URL set means we were reset to
// pick a different one.
ServerConfig.get().then(({ url }) => {
  if (url) input.value = url;
});
