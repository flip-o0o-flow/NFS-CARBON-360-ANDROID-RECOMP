// Physical controller remapping before SDL. SPDX-License-Identifier: GPL-3.0-only
package com.nfscarbon.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

final class ButtonMap {
    static final String[] NAMES = {"A", "B", "X", "Y", "LB", "RB", "LT", "RT", "Back", "Start",
            "LS", "RS", "D-pad ↑", "D-pad ↓", "D-pad ←", "D-pad →"};
    static final int[] KEYS = {KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT};
    static final int[] AXES = {MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
            MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ};
    private final SharedPreferences prefs;
    private final Consumer<KeyEvent> send;
    private final Map<String, String[]> maps = new HashMap<>();
    private final Map<String, KeyEvent> held = new HashMap<>();
    InputDevice lastDevice;

    ButtonMap(Context context, Consumer<KeyEvent> send) {
        prefs = context.getSharedPreferences("nfscarbon_controls", Context.MODE_PRIVATE);
        this.send = send;
    }

    private String id(InputDevice device) {
        String descriptor = device.getDescriptor();
        return "button_map:" + (descriptor == null || descriptor.isEmpty() ? device.getName() : descriptor);
    }

    String[] mapping(InputDevice device) {
        String id = id(device);
        String[] values = maps.get(id);
        if (values == null) {
            values = new String[KEYS.length];
            for (String entry : prefs.getString(id, "").split(";")) {
                String[] pair = entry.split("=");
                if (pair.length != 2) continue;
                for (int i = 0; i < NAMES.length; i++) {
                    if (NAMES[i].equals(pair[0])) values[i] = pair[1];
                }
            }
            maps.put(id, values);
        }
        return values;
    }

    void assign(InputDevice device, int target, String source) {
        release();
        String[] values = mapping(device);
        // One physical source has one destination.
        for (int i = 0; i < values.length; i++) {
            if (source.equals(values[i])) values[i] = null;
        }
        values[target] = source;
        StringBuilder saved = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) saved.append(NAMES[i]).append('=').append(values[i]).append(';');
        }
        prefs.edit().putString(id(device), saved.toString()).apply();
    }

    void reset(InputDevice device) {
        release();
        maps.remove(id(device));
        prefs.edit().remove(id(device)).apply();
    }

    KeyEvent key(KeyEvent event) {
        String token = event.getDeviceId() + ":key:" + event.getKeyCode();
        KeyEvent down = held.get(token);
        if (down != null) {
            if (event.getAction() == KeyEvent.ACTION_UP) held.remove(token);
            return copyKey(event, down.getKeyCode());
        }
        String[] values = mapping(event.getDevice());
        for (int i = 0; i < values.length; i++) {
            if (Integer.toString(event.getKeyCode()).equals(values[i])) {
                KeyEvent mapped = copyKey(event, KEYS[i]);
                if (event.getAction() == KeyEvent.ACTION_DOWN) held.put(token, mapped);
                return mapped;
            }
        }
        return event;
    }

    private KeyEvent copyKey(KeyEvent event, int key) {
        return new KeyEvent(event.getDownTime(), event.getEventTime(), event.getAction(), key,
                event.getRepeatCount(), event.getMetaState(), event.getDeviceId(), event.getScanCode(),
                event.getFlags(), event.getSource());
    }

    MotionEvent motion(MotionEvent event) {
        String[] values = mapping(event.getDevice());
        boolean mapped = false;
        for (String value : values) mapped |= value != null && value.startsWith("axis:");
        if (!mapped || event.getActionMasked() != MotionEvent.ACTION_MOVE) return event;
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[event.getPointerCount()];
        for (int p = 0; p < coords.length; p++) {
            coords[p] = new MotionEvent.PointerCoords();
            event.getPointerCoords(p, coords[p]);
        }
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null || !values[i].startsWith("axis:")) continue;
            int axis;
            try { axis = Integer.parseInt(values[i].substring(5)); }
            catch (NumberFormatException ignored) { continue; }
            String token = event.getDeviceId() + ":axis:" + axis;
            KeyEvent down = held.get(token);
            float value = event.getAxisValue(axis);
            if (down == null && value > .5f) {
                down = new KeyEvent(event.getEventTime(), event.getEventTime(), KeyEvent.ACTION_DOWN,
                        KEYS[i], 0, 0, event.getDeviceId(), 0, 0, InputDevice.SOURCE_GAMEPAD);
                held.put(token, down);
                send.accept(down);
            } else if (down != null && value < .3f) {
                send.accept(keyUp(down, event.getEventTime()));
                held.remove(token);
            }
            for (MotionEvent.PointerCoords coord : coords) coord.setAxisValue(axis, 0);
        }
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[coords.length];
        for (int p = 0; p < properties.length; p++) {
            properties[p] = new MotionEvent.PointerProperties();
            event.getPointerProperties(p, properties[p]);
        }
        // SDL reads only the current sample; omit history so no original axis values leak through.
        return MotionEvent.obtain(event.getDownTime(), event.getEventTime(), event.getAction(),
                coords.length, properties, coords, event.getMetaState(), event.getButtonState(),
                event.getXPrecision(), event.getYPrecision(), event.getDeviceId(), event.getEdgeFlags(),
                event.getSource(), event.getFlags());
    }

    private KeyEvent keyUp(KeyEvent down, long time) {
        return new KeyEvent(down.getDownTime(), time, KeyEvent.ACTION_UP, down.getKeyCode(),
                0, 0, down.getDeviceId(), 0, 0, InputDevice.SOURCE_GAMEPAD);
    }

    void release() {
        for (KeyEvent down : held.values()) send.accept(keyUp(down, SystemClock.uptimeMillis()));
        held.clear();
    }
}
