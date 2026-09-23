// Thin JS-side companion to the native Android gamepad bridge in
// MainActivity.java. The native side calls
// `window.__nativeGamepad = {...}` directly (no Cordova exec() bridge,
// for minimum per-frame overhead), so this module mostly just guarantees a
// safe default exists before the first native update lands, and gives game
// code a documented place to read from instead of poking `window` directly.

var DEFAULT_STATE = {
  connected: false,
  leftStickX: 0, leftStickY: 0,
  rightStickX: 0, rightStickY: 0,
  leftTrigger: 0, rightTrigger: 0,
  a: false, b: false, x: false, y: false,
  l1: false, r1: false, l2: false, r2: false,
  l3: false, r3: false,
  start: false, select: false,
  dpadUp: false, dpadDown: false, dpadLeft: false, dpadRight: false,
};

if (typeof window.__nativeGamepad === "undefined") {
  window.__nativeGamepad = DEFAULT_STATE;
}

var NativeGamepad = {
  /** Returns the latest polled gamepad state (updated ~60x/sec natively). */
  get: function () {
    return window.__nativeGamepad || DEFAULT_STATE;
  },
  isConnected: function () {
    return !!(window.__nativeGamepad && window.__nativeGamepad.connected);
  },
};

module.exports = NativeGamepad;
