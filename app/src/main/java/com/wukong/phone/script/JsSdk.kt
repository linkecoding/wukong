package com.wukong.phone.script

/** A fresh JS context per cell; only explicit JSON state persists for the current task. */
internal object JsSdk {
    fun wrap(code: String, state: String): String = """
(function(native) {
  'use strict';
  delete globalThis.__phoneBridge;
  const parse = JSON.parse.bind(JSON), stringify = JSON.stringify.bind(JSON);
  const invoke = (method, args = {}) => {
    const reply = parse(native.call(method, stringify(args)));
    if (!reply.ok) { const detail = reply.error; const error = new Error(typeof detail === 'object' ? detail.message : detail); if (typeof detail === 'object') Object.assign(error, detail); throw error; }
    return reply.result;
  };
  const invalid = message => { const e = new Error(stringify({code:'INVALID_ARGUMENT',message})); e.code = 'INVALID_ARGUMENT'; throw e; };
  const keys = new Set(['textContains','textEquals','resourceId','className','editable','clickable','scrollable','enabled','checked','selected','ancestorResourceId']);
  const selector = value => {
    if (typeof value === 'string') return {textEquals: value};
    if (!value || typeof value !== 'object' || Array.isArray(value)) return invalid('Expected selector object or exact text; use wukong.id(fullResourceId) for IDs.');
    for (const key of Object.keys(value)) {
      if (!keys.has(key)) return invalid('Unknown selector field: ' + key);
      const type = ['editable','clickable','scrollable','enabled','checked','selected'].includes(key) ? 'boolean' : 'string';
      if (typeof value[key] !== type) return invalid('Selector ' + key + ' must be ' + type);
    }
    return value;
  };
  // Builders must not shadow serialized fields: chain methods are stored on a prototype.
  const make = fields => {
    const plan = {...selector(fields)}, methods = {};
    for (const key of keys) Object.defineProperty(methods, key === 'resourceId' ? 'id' : key === 'textEquals' ? 'text' : key, {
      value: value => make({...plan, [key]: value}), writable: true
    });
    return Object.freeze(Object.assign(Object.create(methods), plan));
  };
  const optionsOf = (value, allowed) => {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return invalid('Expected options object');
    for (const key of Object.keys(value)) if (!allowed.includes(key)) return invalid('Unknown option: ' + key);
    return value;
  };
  const asCondition = value => {
    if (typeof value === 'function') return invalid('Predicate callbacks are unsupported; pass a selector or use waitAny({success: selector, error: selector}).');
    if (value && typeof value === 'object' && ('selector' in value || 'packageName' in value || 'state' in value || 'minCount' in value)) {
      optionsOf(value, ['selector','packageName','state','minCount']);
      return {...value, ...(value.selector ? {selector: selector(value.selector)} : {})};
    }
    return {selector: selector(value)};
  };
  const conditions = value => Array.isArray(value) ? value.map(asCondition) : value && Array.isArray(value.conditions) ? value.conditions.map(asCondition) : [asCondition(value)];
  const conditionArgs = (value, options) => {
    if (value && Array.isArray(value.conditions)) optionsOf(value, ['conditions','matchAll','timeoutMs']);
    return {...(value && Array.isArray(value.conditions) ? value : {}), conditions: conditions(value), ...optionsOf(options, ['matchAll','timeoutMs'])};
  };
  const query = (value, options) => {
    const page = invoke('query', {selector: selector(value), ...(typeof options === 'number' ? {limit: options} : optionsOf(options, ['limit','offset']))});
    // Keep pagination/evidence metadata while supporting familiar list operations.
    for (const name of ['map','filter','find','some','every','forEach','reduce','at'])
      Object.defineProperty(page, name, {value: (...args) => page.nodes[name](...args)});
    Object.defineProperty(page, 'length', {get: () => page.nodes.length});
    Object.defineProperty(page, Symbol.iterator, {value: () => page.nodes[Symbol.iterator]()});
    return page;
  };
  const listeners = new Set();
  const wukong = Object.freeze({
    id: value => make({resourceId: value}), text: value => make({textEquals: value}),
    textContains: value => make({textContains: value}), select: value => make(value),
    observe: () => invoke('observe'),
    query: (value = {}, options = {}) => query(value, options),
    waitUntil: (value, options = {}) => invoke('waitUntil', conditionArgs(value, options)),
    verify: (value, options = {}) => invoke('verify', conditionArgs(value, options)),
    waitAny: (outcomes, options = {}) => {
      if (!outcomes || typeof outcomes !== 'object' || Array.isArray(outcomes)) return invalid('waitAny expects named outcomes');
      const names = Object.keys(outcomes);
      if (!names.length || names.length > 5 || names.some(k => !k.length || k.length > 80)) return invalid('Provide 1–5 named outcomes (keys 1–80 characters)');
      return invoke('waitAny', {names, conditions: names.map(k => asCondition(outcomes[k])), ...optionsOf(options, ['timeoutMs'])});
    },
    waitGone: (value, options = {}) => invoke('waitUntil', conditionArgs({selector: selector(value), state:'absent'}, options)),
    waitEnabled: (value, options = {}) => invoke('waitUntil', conditionArgs({selector:{...selector(value),enabled:true}}, options)),
    exists: value => { const page = query(value, {limit:1}); return page.totalMatches > 0 ? true : page.sourceTruncated ? null : false; },
    tap: (value, options = {}) => invoke('tap', {selector: {clickable: true, ...selector(value)}, ...optionsOf(options, ['timeoutMs'])}),
    input: (value, text, options = {}) => invoke('input', {selector: {editable: true, ...selector(value)}, text, ...optionsOf(options, ['timeoutMs'])}),
    setText: (value, text, options = {}) => invoke('setText', {selector:{editable:true,...selector(value)},text,...optionsOf(options,['timeoutMs'])}),
    setChecked: (value, checked, options = {}) => invoke('setChecked', {selector:selector(value),checked,...optionsOf(options,['timeoutMs'])}),
    scroll: (value, forward = true, options = {}) => invoke('scroll', {selector: {scrollable: true, ...selector(value)}, forward, ...optionsOf(options, ['timeoutMs'])}),
    launch: packageName => invoke('launch', {packageName}),
    home: () => invoke('home'), back: () => invoke('back'),
    screenshot: () => invoke('screenshot'), ocr: () => invoke('ocr'),
    tapAt: (x,y,captureId) => invoke('tapAt', {x,y,captureId}),
    longPress: (x,y,captureId,duration = 600) => invoke('longPress', {x,y,captureId,duration}),
    swipe: (x,y,endX,endY,captureId,duration = 350) => invoke('swipe', {x,y,endX,endY,captureId,duration}),
    sleep: milliseconds => invoke('sleep', {milliseconds}),
    log: message => invoke('log', {message: String(message)}),
    events: Object.freeze({
      onChange: callback => { if (typeof callback !== 'function') throw new Error('callback required'); listeners.add(callback); return () => listeners.delete(callback); },
      next: (options = {}) => { const event = invoke('nextChange', options); if (event.changed) for (const callback of [...listeners]) callback(event); return event; }
    })
  });
  Object.defineProperty(globalThis, 'wukong', {value: wukong, writable: false, configurable: false});
  const state = $state;
  const value = (function(state) {
    'use strict';
$code
  }).call(undefined, state);
  if (value && typeof value.then === 'function') throw new Error('Use synchronous phone APIs; async cells are not supported');
  const saved = stringify(state);
  if (saved.length > 32768) throw new Error('state exceeds 32K');
  const result = stringify({state: parse(saved), value: value === undefined ? null : value});
  if (result.length > 96000) throw new Error('result exceeds 96K');
  return result;
})(globalThis.__phoneBridge)
""".trimIndent()
}
