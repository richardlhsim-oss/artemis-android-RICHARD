package com.limelight.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageButton;
import android.widget.Toast;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.LinkedHashMap;

/**
 * Floating desktop controls: a handle on the right edge that opens a quick
 * menu, a scroll strip, and optional left/right mouse buttons. The overlays
 * can be dragged to new positions, which are remembered.
 */
public class DesktopOverlays {
    private static final int ACCENT_COLOR = 0xFF4FC3F7;
    private static final int SCROLL_STRIP_SPEED_FACTOR = 6;
    private static final float SCROLL_STRIP_SLOWDOWN = 0.57f;
    private static final int DRAG_LOCK_TAP_MS = 300;
    private static final String LAYOUT_PREFS = "desktop_overlay_layout";

    private final Game game;
    private final NvConnection conn;
    private final KeyboardTranslator keyboardTranslator;
    private final PreferenceConfiguration prefConfig;

    private final View handle;
    private final View quickMenu;
    private final View mouseButtonBar;
    private final View scrollStrip;

    private final LinkedHashMap<View, float[]> offsets = new LinkedHashMap<>();
    private boolean editMode;
    private boolean dragMoved;
    private float dragStartRawX, dragStartRawY, dragStartX, dragStartY;
    private float lastScrollY;
    private boolean privacyRequested;
    private boolean hidden;

    public DesktopOverlays(Game game, NvConnection conn, KeyboardTranslator keyboardTranslator,
                           PreferenceConfiguration prefConfig) {
        this.game = game;
        this.conn = conn;
        this.keyboardTranslator = keyboardTranslator;
        this.prefConfig = prefConfig;

        handle = game.findViewById(R.id.quickMenuHandle);
        quickMenu = game.findViewById(R.id.quickMenu);
        mouseButtonBar = game.findViewById(R.id.mouseButtonBar);
        scrollStrip = game.findViewById(R.id.scrollStrip);

        if (!prefConfig.showHandleKeyboard) {
            game.findViewById(R.id.quickMenuHandleKeyboard).setVisibility(View.GONE);
        }

        game.findViewById(R.id.quickMenuHandleChevron).setOnClickListener(v -> setMenuOpen(true));
        game.findViewById(R.id.quickMenuHandleKeyboard).setOnClickListener(v -> game.toggleKeyboard());
        game.findViewById(R.id.quickMenuCollapseButton).setOnClickListener(v -> setMenuOpen(false));
        game.findViewById(R.id.quickMenuKeyboardButton).setOnClickListener(v -> game.toggleKeyboard());
        game.findViewById(R.id.quickMenuCursorModeButton).setOnClickListener(v -> game.selectMouseMode());
        game.findViewById(R.id.quickMenuStatsButton).setOnClickListener(v -> game.toggleHUD());
        game.findViewById(R.id.quickMenuGameMenuButton).setOnClickListener(v -> game.showGameMenu(null));
        game.findViewById(R.id.quickMenuMoveOverlaysButton).setOnClickListener(v -> setEditMode(true));
        game.findViewById(R.id.quickMenuDisconnectButton).setOnClickListener(v -> game.disconnect());
        game.findViewById(R.id.quickMenuPrivacyButton).setOnClickListener(v -> {
            // The host-side PrivacyScreen helper toggles on this hotkey. Its real
            // state can't be read back, so the tint tracks our toggles.
            sendKeys(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT,
                    KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_P);
            privacyRequested = !privacyRequested;
            if (privacyRequested) {
                ((ImageButton) v).setColorFilter(ACCENT_COLOR, PorterDuff.Mode.SRC_IN);
            }
            else {
                ((ImageButton) v).clearColorFilter();
            }
            Toast.makeText(game, R.string.privacy_screen_sent, Toast.LENGTH_SHORT).show();
        });

        game.findViewById(R.id.mouseLeftButton).setOnTouchListener(mouseButtonListener(MouseButtonPacket.BUTTON_LEFT));
        game.findViewById(R.id.mouseRightButton).setOnTouchListener(mouseButtonListener(MouseButtonPacket.BUTTON_RIGHT));
        scrollStrip.setOnTouchListener((v, event) -> {
            if (editMode) {
                return handleDrag(scrollStrip, event);
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastScrollY = event.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int delta = (int) ((event.getY() - lastScrollY) * SCROLL_STRIP_SPEED_FACTOR * SCROLL_STRIP_SLOWDOWN);
                    if (prefConfig.invertScrollStrip) {
                        delta = -delta;
                    }
                    conn.sendMouseHighResScroll((short) delta);
                    lastScrollY = event.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return false;
            }
        });

        setupMovable();
        show();
    }

    // Hidden in picture-in-picture
    public void setHidden(boolean hidden) {
        this.hidden = hidden;
        if (hidden) {
            handle.setVisibility(View.GONE);
            quickMenu.setVisibility(View.GONE);
            mouseButtonBar.setVisibility(View.GONE);
            scrollStrip.setVisibility(View.GONE);
        }
        else {
            show();
        }
    }

    private void show() {
        handle.setVisibility(View.VISIBLE);
        quickMenu.setVisibility(View.GONE);
        mouseButtonBar.setVisibility(prefConfig.showMouseButtons ? View.VISIBLE : View.GONE);
        scrollStrip.setVisibility(prefConfig.showScrollStrip ? View.VISIBLE : View.GONE);
    }

    private void setMenuOpen(boolean open) {
        if (hidden) {
            return;
        }
        handle.setVisibility(open ? View.GONE : View.VISIBLE);
        quickMenu.setVisibility(open ? View.VISIBLE : View.GONE);
    }

    // Presses the keys in order, then releases them in reverse
    private void sendKeys(int... androidKeyCodes) {
        short[] translated = new short[androidKeyCodes.length];
        for (int i = 0; i < androidKeyCodes.length; i++) {
            translated[i] = keyboardTranslator.translate(androidKeyCodes[i], 0, -1);
        }
        for (short key : translated) {
            conn.sendKeyboardInput(key, KeyboardPacket.KEY_DOWN, (byte) 0, (byte) 0);
        }
        for (int i = translated.length - 1; i >= 0; i--) {
            conn.sendKeyboardInput(translated[i], KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
        }
    }

    // Behaves like a physical mouse button: held while pressed. A quick
    // double-tap latches the button down (drag lock) until tapped again.
    private View.OnTouchListener mouseButtonListener(final byte button) {
        return new View.OnTouchListener() {
            private boolean latched;
            private boolean unlatching;
            private long lastTapUpTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (editMode) {
                    return handleDrag(mouseButtonBar, event);
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (latched) {
                            latched = false;
                            unlatching = true;
                            ((ImageButton) v).clearColorFilter();
                            v.setPressed(false);
                            conn.sendMouseButtonUp(button);
                        }
                        else {
                            v.setPressed(true);
                            conn.sendMouseButtonDown(button);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (unlatching) {
                            unlatching = false;
                        }
                        else if (event.getActionMasked() == MotionEvent.ACTION_UP &&
                                event.getEventTime() - lastTapUpTime < DRAG_LOCK_TAP_MS) {
                            latched = true;
                            v.setPressed(true);
                            ((ImageButton) v).setColorFilter(ACCENT_COLOR, PorterDuff.Mode.SRC_IN);
                        }
                        else {
                            v.setPressed(false);
                            conn.sendMouseButtonUp(button);
                            lastTapUpTime = event.getEventTime();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        };
    }

    private void setupMovable() {
        SharedPreferences layout = game.getSharedPreferences(LAYOUT_PREFS, Context.MODE_PRIVATE);
        for (View overlay : new View[] { mouseButtonBar, scrollStrip, handle }) {
            String key = game.getResources().getResourceEntryName(overlay.getId());
            offsets.put(overlay, new float[] { layout.getFloat(key + "_x", 0), layout.getFloat(key + "_y", 0) });
        }

        // The handle's buttons consume touches, so they forward drags while editing
        View.OnTouchListener handleDrag = (v, event) -> editMode && handleDrag(handle, event);
        game.findViewById(R.id.quickMenuHandleChevron).setOnTouchListener(handleDrag);
        game.findViewById(R.id.quickMenuHandleKeyboard).setOnTouchListener(handleDrag);

        // Re-clamped whenever the layout changes (rotation, keyboard, visibility)
        View.OnLayoutChangeListener reapply = (v, l, t, r, b, ol, ot, or, ob) -> applyOffsets();
        for (View overlay : offsets.keySet()) {
            overlay.addOnLayoutChangeListener(reapply);
        }
        quickMenu.addOnLayoutChangeListener(reapply);

        setAlpha(prefConfig.desktopOverlayOpacity / 100f);
    }

    private void setAlpha(float alpha) {
        for (View overlay : offsets.keySet()) {
            overlay.setAlpha(alpha);
        }
    }

    private void applyOffsets() {
        for (View overlay : offsets.keySet()) {
            float[] offset = offsets.get(overlay);
            applyClamped(overlay, offset[0], offset[1]);
        }
        // The expanded quick menu opens at the handle's height
        applyClamped(quickMenu, 0, offsets.get(handle)[1]);
    }

    private static void applyClamped(View v, float x, float y) {
        View parent = (View) v.getParent();
        v.setTranslationX(Math.max(-v.getLeft(),
                Math.min(x, parent.getWidth() - parent.getPaddingRight() - v.getRight())));
        v.setTranslationY(Math.max(parent.getPaddingTop() - v.getTop(),
                Math.min(y, parent.getHeight() - parent.getPaddingBottom() - v.getBottom())));
    }

    private boolean handleDrag(View overlay, MotionEvent event) {
        float[] offset = offsets.get(overlay);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragStartRawX = event.getRawX();
                dragStartRawY = event.getRawY();
                // Start from the clamped position so dragging responds immediately
                dragStartX = offset[0] = overlay.getTranslationX();
                dragStartY = offset[1] = overlay.getTranslationY();
                dragMoved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - dragStartRawX;
                float dy = event.getRawY() - dragStartRawY;
                if (!dragMoved && Math.hypot(dx, dy) < ViewConfiguration.get(game).getScaledTouchSlop()) {
                    return true;
                }
                dragMoved = true;
                offset[0] = dragStartX + dx;
                offset[1] = dragStartY + dy;
                applyOffsets();
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragMoved) {
                    setEditMode(false);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return false;
        }
    }

    private void setEditMode(boolean active) {
        editMode = active;
        if (active) {
            setMenuOpen(false);
            setAlpha(1.0f);
            Toast.makeText(game, R.string.move_overlays_on, Toast.LENGTH_LONG).show();
            return;
        }

        setAlpha(prefConfig.desktopOverlayOpacity / 100f);
        SharedPreferences.Editor editor = game.getSharedPreferences(LAYOUT_PREFS, Context.MODE_PRIVATE).edit();
        for (View overlay : offsets.keySet()) {
            String key = game.getResources().getResourceEntryName(overlay.getId());
            float[] offset = offsets.get(overlay);
            editor.putFloat(key + "_x", offset[0]).putFloat(key + "_y", offset[1]);
        }
        editor.apply();
        Toast.makeText(game, R.string.move_overlays_off, Toast.LENGTH_SHORT).show();
    }
}
