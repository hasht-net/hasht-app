(function () {
  const context = window.HashtAccount;
  if (!context || location.origin !== context.getOrigin()) return;
  const storageKey = context.getStorageKey();
  const accountID = context.getAccountID();
  // Native tombstones are installed before the remote app can hydrate auth.
  const removed = JSON.parse(context.getRemovedStorageKeys?.() || '[]');
  for (const name of removed) {
    const raw = localStorage.getItem(name);
    localStorage.removeItem(name);
    try {
      const token = JSON.parse(raw || 'null')?.token;
      if (token) void fetch('/api/auth/logout', {
        method: 'POST', headers: { Authorization: `Bearer ${token}` },
        credentials: 'omit', redirect: 'error', keepalive: true,
      }).catch(() => {});
    } catch (_) {}
  }

  Object.defineProperty(window, '__hashtAccountID', { value: accountID });

  const prototype = Storage.prototype;
  const get = prototype.getItem;
  const set = prototype.setItem;
  const remove = prototype.removeItem;
  const key = prototype.key;
  const mapKey = (storage, name) => storage === localStorage && String(name) === 'chat_auth' ? storageKey : name;
  prototype.getItem = function (name) { return get.call(this, mapKey(this, name)); };
  prototype.setItem = function (name, value) { return set.call(this, mapKey(this, name), value); };
  prototype.removeItem = function (name) { return remove.call(this, mapKey(this, name)); };
  const clear = prototype.clear;
  prototype.clear = function () {
    if (this !== localStorage) return clear.call(this);
    // Clearing this account must not sign other saved accounts out.
    const keys = Array.from({ length: this.length }, (_, index) => key.call(this, index));
    for (const name of keys) {
      if (name !== 'chat_auth' && !name.startsWith('hasht.account.')) remove.call(this, name);
    }
    remove.call(this, storageKey);
  };
  const translatedEvents = new WeakSet();
  window.addEventListener('storage', (event) => {
    if (translatedEvents.has(event)) return;
    if (event.storageArea !== localStorage) return;
    if (event.key === storageKey) {
      event.stopImmediatePropagation();
      const translated = new StorageEvent('storage', {
        key: 'chat_auth', oldValue: event.oldValue, newValue: event.newValue,
        url: event.url, storageArea: localStorage,
      });
      translatedEvents.add(translated);
      window.dispatchEvent(translated);
    } else if (event.key === 'chat_auth' || event.key?.startsWith('hasht.account.')) {
      event.stopImmediatePropagation();
    }
  });
})();
