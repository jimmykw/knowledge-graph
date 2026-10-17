'use strict';
// Minimal stand-in for the browser + Alpine so the static UI scripts can be evaluated by plain Node.
// The scripts run in this process's global scope (window === globalThis), which keeps their return values
// in the same realm as the tests (deepStrictEqual compares prototypes). `window.hljs` is left undefined on purpose.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const STATIC_JS = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'js');

const initListeners = [];
const components = {};

globalThis.window = globalThis;
globalThis.document = {
  addEventListener(event, callback) {
    if (event === 'alpine:init') {
      initListeners.push(callback);
    }
  }
};
globalThis.Alpine = {
  data(name, factory) {
    components[name] = factory;
  }
};
globalThis.localStorage = (() => {
  let store = {};
  return {
    getItem: (key) => (key in store ? store[key] : null),
    setItem: (key, value) => { store[key] = String(value); },
    removeItem: (key) => { delete store[key]; },
    clear: () => { store = {}; }
  };
})();

/** Evaluates a script from the static js folder and fires alpine:init, registering its components. */
function loadScript(fileName) {
  const file = path.join(STATIC_JS, fileName);
  vm.runInThisContext(fs.readFileSync(file, 'utf8'), { filename: file });
  initListeners.splice(0).forEach((callback) => callback());
}

/** A fresh component instance; `$nextTick`/`$refs` are provided so methods that touch them can run. */
function component(name, ...args) {
  const instance = components[name](...args);
  instance.$nextTick = (callback) => callback();
  instance.$refs = {};
  return instance;
}

module.exports = { loadScript, component };
