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
    if (!reply.ok) throw new Error(reply.error);
    return reply.result;
  };
  const selector = value => typeof value === 'string' ? {textEquals: value} : value;
  const asCondition = value => value && typeof value === 'object' && ('selector' in value || 'packageName' in value || 'state' in value || 'minCount' in value) ? value : {selector: selector(value)};
  const conditions = value => Array.isArray(value) ? value.map(asCondition) : value && Array.isArray(value.conditions) ? value.conditions.map(asCondition) : [asCondition(value)];
  const conditionArgs = (value, options) => ({...(value && Array.isArray(value.conditions) ? value : {}), conditions: conditions(value), ...options});
  const query = (value, options) => {
    const page = invoke('query', {selector: selector(value), ...(typeof options === 'number' ? {limit: options} : options)});
    // Keep pagination/evidence metadata while supporting familiar list operations.
    for (const name of ['map','filter','find','some','every','forEach','reduce','at'])
      Object.defineProperty(page, name, {value: (...args) => page.nodes[name](...args)});
    Object.defineProperty(page, 'length', {get: () => page.nodes.length});
    Object.defineProperty(page, Symbol.iterator, {value: () => page.nodes[Symbol.iterator]()});
    return page;
  };
  const listeners = new Set();
  const wukong = Object.freeze({
    observe: () => invoke('observe'),
    query: (value = {}, options = {}) => query(value, options),
    waitUntil: (value, options = {}) => invoke('waitUntil', conditionArgs(value, options)),
    verify: (value, options = {}) => invoke('verify', conditionArgs(value, options)),
    tap: value => invoke('tap', {selector: {clickable: true, ...selector(value)}}),
    input: (value, text) => invoke('input', {selector: {editable: true, ...selector(value)}, text}),
    scroll: (value, forward = true) => invoke('scroll', {selector: {scrollable: true, ...selector(value)}, forward}),
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
