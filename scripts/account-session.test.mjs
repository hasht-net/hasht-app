import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('../public/native/account-session.js', import.meta.url), 'utf8');

function page(data, id, { legacy = false, origin = 'https://chat.test', removed = [] } = {}) {
  const listeners = [];
  const requests = [];
  class Storage {
    constructor(values = new Map()) { this.values = values; }
    get length() { return this.values.size; }
    key(index) { return [...this.values.keys()][index] ?? null; }
    getItem(key) { return this.values.get(String(key)) ?? null; }
    setItem(key, value) { this.values.set(String(key), String(value)); }
    removeItem(key) { this.values.delete(String(key)); }
    clear() { this.values.clear(); }
  }
  class StorageEvent {
    constructor(type, options) { Object.assign(this, options, { type }); }
    stopImmediatePropagation() { this.stopped = true; }
  }
  const window = {
    HashtAccount: {
      getOrigin: () => 'https://chat.test', getAccountID: () => id,
      getRemovedStorageKeys: () => JSON.stringify(removed),
      getStorageKey: () => legacy ? 'chat_auth' : `hasht.account.${id}.auth`,
    },
    addEventListener(type, listener) { listeners.push(listener); },
    dispatchEvent(event) {
      for (const listener of listeners) {
        listener(event);
        if (event.stopped) break;
      }
    },
  };
  const context = vm.createContext({ window, Storage, StorageEvent, fetch: async (...args) => { requests.push(args); return {}; }, location: { origin }, localStorage: new Storage(data), sessionStorage: new Storage() });
  vm.runInContext(source, context);
  return { ...context, requests, run: (script) => vm.runInContext(script, context) };
}

test('duplicate entries start signed out and retain separate accounts across reloads', () => {
  const data = new Map([['chat_auth', 'original-user']]);
  const original = page(data, 'first', { legacy: true });
  const second = page(data, 'second');
  assert.equal(original.localStorage.getItem('chat_auth'), 'original-user');
  assert.equal(second.localStorage.getItem('chat_auth'), null);
  second.localStorage.setItem('chat_auth', 'another-user');
  assert.equal(original.localStorage.getItem('chat_auth'), 'original-user');
  assert.equal(page(data, 'second').localStorage.getItem('chat_auth'), 'another-user');
  assert.equal(page(data, 'third').localStorage.getItem('chat_auth'), null);
  second.localStorage.removeItem('chat_auth');
  assert.equal(original.localStorage.getItem('chat_auth'), 'original-user');
});

test('new first entries do not adopt old origin credentials', () => {
  const current = page(new Map([['chat_auth', 'removed-user']]), 'new');
  assert.equal(current.localStorage.getItem('chat_auth'), null);
});

test('clear only removes the selected account auth and keeps normal storage behavior', () => {
  for (const legacy of [false, true]) {
    const data = new Map([['chat_auth', 'old'], ['hasht.account.a.auth', 'a'], ['hasht.account.b.auth', 'b'], ['theme', 'dark']]);
    const current = page(data, 'a', { legacy });
    current.sessionStorage.setItem('chat_auth', 'temporary');
    current.localStorage.setItem('theme', 'light');
    assert.equal(current.localStorage.getItem('theme'), 'light');
    assert.equal(current.sessionStorage.getItem('chat_auth'), 'temporary');
    current.localStorage.clear();
    assert.equal(data.get('hasht.account.b.auth'), 'b');
    assert.equal(data.get(legacy ? 'hasht.account.a.auth' : 'chat_auth'), legacy ? 'a' : 'old');
    assert.equal(current.localStorage.getItem('chat_auth'), null);
    assert.equal(data.has('theme'), false);
    current.sessionStorage.clear();
    assert.equal(current.sessionStorage.length, 0);
  }
});

test('storage events report only the current account under the frontend auth key', () => {
  for (const legacy of [false, true]) {
    const current = page(new Map(), 'a', { legacy });
    const received = [];
    current.window.addEventListener('storage', (event) => received.push([event.key, event.newValue]));
    for (const key of ['chat_auth', 'hasht.account.a.auth', 'hasht.account.b.auth', 'theme']) {
      current.window.dispatchEvent(new current.StorageEvent('storage', { key, newValue: key, storageArea: current.localStorage }));
    }
    assert.deepEqual(received, [['chat_auth', legacy ? 'chat_auth' : 'hasht.account.a.auth'], ['theme', 'theme']]);
  }
});

test('other origins are left alone', () => {
  const current = page(new Map([['chat_auth', 'sso']]), 'a', { origin: 'https://sso.test' });
  assert.equal(current.window.__hashtAccountID, undefined);
  assert.equal(current.localStorage.getItem('chat_auth'), 'sso');
});


test('native removal tombstones clear only forgotten credentials before app hydration', () => {
  const old = JSON.stringify({ token: 'old-session' });
  const other = JSON.stringify({ token: 'other-session' });
  const data = new Map([['chat_auth', old], ['hasht.account.a.auth', old], ['hasht.account.b.auth', other]]);
  const current = page(data, 'b', { removed: ['chat_auth', 'hasht.account.a.auth'] });
  assert.equal(current.localStorage.getItem('chat_auth'), other);
  assert.equal(data.has('chat_auth'), false);
  assert.equal(data.has('hasht.account.a.auth'), false);
  assert.equal(current.requests.length, 2);
  for (const [url, options] of current.requests) {
    assert.equal(url, '/api/auth/logout');
    assert.equal(options.headers.Authorization, 'Bearer old-session');
    assert.equal(options.redirect, 'error');
  }
  assert.equal(page(data, 'new', { removed: ['chat_auth', 'hasht.account.a.auth'] }).localStorage.getItem('chat_auth'), null);
});
