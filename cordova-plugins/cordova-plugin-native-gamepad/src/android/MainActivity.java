/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
*/

package com.flats.mtsyntho;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebView;

import org.apache.cordova.*;
import org.json.JSONObject;

/**
 * Cordova's default MainActivity, extended to read real Android gamepad HID
 * input (SteelSeries Nimbus Cloud and friends) via InputDevice, and push it
 * into the WebView's JS context. GDJS (the GDevelop runtime) has no built-in
 * gamepad support and the Android System WebView's coverage of the browser
 * Gamepad API is unreliable, so this bridges native input -> a plain JS
 * global object (`window.__nativeGamepad`) that game events poll every frame.
 *
 * Installed by replacing the generated MainActivity.java (see plugin.xml in
 * cordova-plugin-native-gamepad) rather than as a normal CordovaPlugin,
 * because analog stick/trigger motion and gamepad buttons are only ever
 * delivered to dispatchGenericMotionEvent/dispatchKeyEvent at the Activity
 * level, not through the standard CordovaPlugin lifecycle callbacks.
 */
public class MainActivity extends CordovaActivity
{
    // Polling rate for pushing the accumulated gamepad state into JS.
    // Raw motion events can fire far more often than this; we coalesce them
    // into one evaluateJavascript() call per tick instead of one per event.
    private static final int POLL_INTERVAL_MS = 16; // ~60Hz

    private final GamepadState state = new GamepadState();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean polling = false;

    private static boolean isGamepadSource(int source) {
        return (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            || (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
    }

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        // enable Cordova apps to be started in the background
        Bundle extras = getIntent().getExtras();
        if (extras != null && extras.getBoolean("cdvStartInBackground", false)) {
            moveTaskToBack(true);
        }

        // Set by <content src="index.html" /> in config.xml
        loadUrl(launchUrl);

        // Android WebView blocks audio/video autoplay without a preceding
        // user gesture by default (same policy as Chrome). GDJS plays
        // background music as soon as a scene loads, before the player has
        // touched anything, so without this the game is silent until (if
        // ever) some other gesture happens to unlock the audio context.
        allowAudioAutoplay();

        startPolling();
    }

    private void allowAudioAutoplay() {
        View v = (appView != null) ? appView.getView() : null;
        if (v instanceof WebView) {
            ((WebView) v).getSettings().setMediaPlaybackRequiresUserGesture(false);
        }
    }

    private void startPolling() {
        if (polling) return;
        polling = true;
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pushStateToJs();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    public void onDestroy() {
        polling = false;
        handler.removeCallbacks(pollRunnable);
        super.onDestroy();
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (isGamepadSource(event.getSource()) && event.getAction() == MotionEvent.ACTION_MOVE) {
            state.leftStickX = event.getAxisValue(MotionEvent.AXIS_X);
            state.leftStickY = event.getAxisValue(MotionEvent.AXIS_Y);
            state.rightStickX = event.getAxisValue(MotionEvent.AXIS_Z);
            state.rightStickY = event.getAxisValue(MotionEvent.AXIS_RZ);

            float lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
            float rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
            // Some controllers report triggers on BRAKE/GAS instead of L/RTRIGGER.
            if (lt == 0f) lt = event.getAxisValue(MotionEvent.AXIS_BRAKE);
            if (rt == 0f) rt = event.getAxisValue(MotionEvent.AXIS_GAS);
            state.leftTrigger = lt;
            state.rightTrigger = rt;

            // D-pad as analog hat axes (-1/0/1), used by some controllers
            // instead of/alongside KEYCODE_DPAD_* key events.
            float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            state.dpadLeft = hatX < -0.5f;
            state.dpadRight = hatX > 0.5f;
            state.dpadUp = hatY < -0.5f;
            state.dpadDown = hatY > 0.5f;

            state.connected = true;
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isGamepadSource(event.getSource())) {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            boolean handled = true;
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_BUTTON_A: state.a = down; break;
                case KeyEvent.KEYCODE_BUTTON_B: state.b = down; break;
                case KeyEvent.KEYCODE_BUTTON_X: state.x = down; break;
                case KeyEvent.KEYCODE_BUTTON_Y: state.y = down; break;
                case KeyEvent.KEYCODE_BUTTON_L1: state.l1 = down; break;
                case KeyEvent.KEYCODE_BUTTON_R1: state.r1 = down; break;
                case KeyEvent.KEYCODE_BUTTON_L2: state.l2 = down; break;
                case KeyEvent.KEYCODE_BUTTON_R2: state.r2 = down; break;
                case KeyEvent.KEYCODE_BUTTON_THUMBL: state.l3 = down; break;
                case KeyEvent.KEYCODE_BUTTON_THUMBR: state.r3 = down; break;
                case KeyEvent.KEYCODE_BUTTON_START: state.start = down; break;
                case KeyEvent.KEYCODE_BUTTON_SELECT: state.select = down; break;
                case KeyEvent.KEYCODE_DPAD_UP: state.dpadUp = down; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: state.dpadDown = down; break;
                case KeyEvent.KEYCODE_DPAD_LEFT: state.dpadLeft = down; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: state.dpadRight = down; break;
                default: handled = false; break;
            }
            if (handled) {
                state.connected = true;
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void pushStateToJs() {
        View v = (appView != null) ? appView.getView() : null;
        if (!(v instanceof WebView)) return;
        WebView webView = (WebView) v;
        String json = state.toJson();
        String js = "(function(){"
            + "window.__nativeGamepad = " + json + ";"
            + "if (window.__onNativeGamepadUpdate) { window.__onNativeGamepadUpdate(window.__nativeGamepad); }"
            + "})();";
        webView.evaluateJavascript(js, null);
    }

    /** Plain state holder + hand-rolled JSON serialization (no extra deps). */
    private static class GamepadState {
        boolean connected = false;
        float leftStickX = 0f, leftStickY = 0f;
        float rightStickX = 0f, rightStickY = 0f;
        float leftTrigger = 0f, rightTrigger = 0f;
        boolean a, b, x, y, l1, r1, l2, r2, l3, r3, start, select;
        boolean dpadUp, dpadDown, dpadLeft, dpadRight;

        String toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("connected", connected);
                o.put("leftStickX", leftStickX);
                o.put("leftStickY", leftStickY);
                o.put("rightStickX", rightStickX);
                o.put("rightStickY", rightStickY);
                o.put("leftTrigger", leftTrigger);
                o.put("rightTrigger", rightTrigger);
                o.put("a", a); o.put("b", b); o.put("x", x); o.put("y", y);
                o.put("l1", l1); o.put("r1", r1); o.put("l2", l2); o.put("r2", r2);
                o.put("l3", l3); o.put("r3", r3);
                o.put("start", start); o.put("select", select);
                o.put("dpadUp", dpadUp); o.put("dpadDown", dpadDown);
                o.put("dpadLeft", dpadLeft); o.put("dpadRight", dpadRight);
                return o.toString();
            } catch (Exception e) {
                return "{\"connected\":false}";
            }
        }
    }
}
