package com.limelight.utils;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.ui.StreamView;

/**
 * Sticky modifier bar shown above the soft keyboard, a function key pad that
 * can replace the keyboard, and a text input mode that lets swipe typing,
 * autocorrect and voice dictation type into the host.
 */
public class DesktopKeyboardBar implements StreamView.TextInputCallbacks {
    private static final int ACCENT_COLOR = 0xFF4FC3F7;
    private static final int TEXT_COLOR = 0xFFEEEEEE;
    private static final int BAR_HEIGHT_DP = 52;
    private static final int PAD_HEIGHT_DP = 300;

    private final Activity activity;
    private final NvConnection conn;
    private final KeyboardTranslator keyboardTranslator;
    private final StreamView streamView;
    private final Runnable openMenu;

    private final View modifierBar;
    private final View functionPad;
    private final ImageButton padToggleButton;

    private int stickyModifiers;
    private boolean textInputModeActive;
    private boolean functionPadVisible;
    private boolean barShowing;
    private boolean imeVisible;
    private boolean keyboardRequested;
    private boolean imeShowing;
    private int imeHeight;

    public DesktopKeyboardBar(Activity activity, NvConnection conn, KeyboardTranslator keyboardTranslator,
                              StreamView streamView, Runnable openMenu) {
        this.activity = activity;
        this.conn = conn;
        this.keyboardTranslator = keyboardTranslator;
        this.streamView = streamView;
        this.openMenu = openMenu;

        modifierBar = activity.findViewById(R.id.modifierBar);
        functionPad = activity.findViewById(R.id.functionPad);
        padToggleButton = activity.findViewById(R.id.modifierPadToggleButton);

        // The stream area is shrunk with padding instead, so the bar can sit
        // exactly on top of the keyboard
        activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        streamView.setTextInputCallbacks(this);

        setupStickyModifier(R.id.modifierWinButton, KeyboardPacket.MODIFIER_META, KeyEvent.KEYCODE_META_LEFT);
        setupStickyModifier(R.id.modifierShiftButton, KeyboardPacket.MODIFIER_SHIFT, KeyEvent.KEYCODE_SHIFT_LEFT);
        setupStickyModifier(R.id.modifierCtrlButton, KeyboardPacket.MODIFIER_CTRL, KeyEvent.KEYCODE_CTRL_LEFT);
        setupStickyModifier(R.id.modifierAltButton, KeyboardPacket.MODIFIER_ALT, KeyEvent.KEYCODE_ALT_LEFT);

        activity.findViewById(R.id.modifierTabButton).setOnClickListener(v -> sendKeys(KeyEvent.KEYCODE_TAB));
        activity.findViewById(R.id.modifierDelButton).setOnClickListener(v -> sendKeys(KeyEvent.KEYCODE_FORWARD_DEL));
        activity.findViewById(R.id.modifierInsButton).setOnClickListener(v -> sendKeys(KeyEvent.KEYCODE_INSERT));
        activity.findViewById(R.id.modifierTextModeButton).setOnClickListener(v -> toggleTextInputMode());
        activity.findViewById(R.id.modifierSettingsButton).setOnClickListener(v -> this.openMenu.run());
        padToggleButton.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (!functionPadVisible) {
                // Replace the soft keyboard with the function key pad
                setFunctionPadVisible(true);
                imm.hideSoftInputFromWindow(streamView.getWindowToken(), 0);
            }
            else {
                setFunctionPadVisible(false);
                imm.toggleSoftInput(0, 0);
            }
            updateLayout();
        });

        setupFunctionPad();
        watchIme();

        // Voice and swipe text input is the default
        textInputModeActive = true;
        ((Button) activity.findViewById(R.id.modifierTextModeButton)).setTextColor(ACCENT_COLOR);
    }

    public boolean isTextInputModeActive() {
        return textInputModeActive;
    }

    public void toggleTextInputMode() {
        textInputModeActive = !textInputModeActive;
        ((Button) activity.findViewById(R.id.modifierTextModeButton))
                .setTextColor(textInputModeActive ? ACCENT_COLOR : TEXT_COLOR);

        // Make the IME re-query the input connection type
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.restartInput(streamView);

        Toast.makeText(activity, textInputModeActive ? R.string.text_mode_on : R.string.text_mode_off,
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean isTextInputActive() {
        return textInputModeActive;
    }

    @Override
    public void onTextInputDelta(int deleteCount, String insertText) {
        for (int i = 0; i < deleteCount; i++) {
            sendKeys(KeyEvent.KEYCODE_DEL);
        }
        // A newline typed as text is not an Enter key press on the host, so
        // send line breaks as real Enter keys
        String[] lines = insertText.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sendKeys(KeyEvent.KEYCODE_ENTER);
            }
            if (!lines[i].isEmpty()) {
                conn.sendUtf8Text(lines[i]);
            }
        }
    }

    @Override
    public void onTextInputEnter() {
        sendKeys(KeyEvent.KEYCODE_ENTER);
    }

    // Presses the keys in order, then releases them in reverse
    private void sendKeys(int... androidKeyCodes) {
        short[] translated = new short[androidKeyCodes.length];
        for (int i = 0; i < androidKeyCodes.length; i++) {
            translated[i] = keyboardTranslator.translate(androidKeyCodes[i], 0, -1);
        }
        for (short key : translated) {
            conn.sendKeyboardInput(key, KeyboardPacket.KEY_DOWN, (byte) stickyModifiers, (byte) 0);
        }
        for (int i = translated.length - 1; i >= 0; i--) {
            conn.sendKeyboardInput(translated[i], KeyboardPacket.KEY_UP, (byte) stickyModifiers, (byte) 0);
        }
    }

    private void wireKey(int viewId, int... androidKeyCodes) {
        activity.findViewById(viewId).setOnClickListener(v -> sendKeys(androidKeyCodes));
    }

    private void setupFunctionPad() {
        int[] fKeyIds = {
                R.id.funcF1, R.id.funcF2, R.id.funcF3, R.id.funcF4,
                R.id.funcF5, R.id.funcF6, R.id.funcF7, R.id.funcF8,
                R.id.funcF9, R.id.funcF10, R.id.funcF11, R.id.funcF12
        };
        for (int i = 0; i < fKeyIds.length; i++) {
            wireKey(fKeyIds[i], KeyEvent.KEYCODE_F1 + i);
        }

        wireKey(R.id.funcEsc, KeyEvent.KEYCODE_ESCAPE);
        wireKey(R.id.funcPrScr, KeyEvent.KEYCODE_SYSRQ);
        wireKey(R.id.funcHome, KeyEvent.KEYCODE_MOVE_HOME);
        wireKey(R.id.funcPgUp, KeyEvent.KEYCODE_PAGE_UP);
        wireKey(R.id.funcTab, KeyEvent.KEYCODE_TAB);
        wireKey(R.id.funcDel, KeyEvent.KEYCODE_FORWARD_DEL);
        wireKey(R.id.funcEnd, KeyEvent.KEYCODE_MOVE_END);
        wireKey(R.id.funcPgDn, KeyEvent.KEYCODE_PAGE_DOWN);
        wireKey(R.id.funcIns, KeyEvent.KEYCODE_INSERT);
        wireKey(R.id.funcEnter, KeyEvent.KEYCODE_ENTER);
        wireKey(R.id.funcWin, KeyEvent.KEYCODE_META_LEFT);
        wireKey(R.id.funcBackspace, KeyEvent.KEYCODE_DEL);

        wireKey(R.id.funcCtrlZ, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_Z);
        wireKey(R.id.funcWinD, KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_D);
        wireKey(R.id.funcLock, KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_L);
        wireKey(R.id.funcCtrlC, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_C);
        wireKey(R.id.funcCtrlX, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_X);
        wireKey(R.id.funcCtrlV, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_V);
        wireKey(R.id.funcCtrlA, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_A);

        wireKey(R.id.funcUp, KeyEvent.KEYCODE_DPAD_UP);
        wireKey(R.id.funcDown, KeyEvent.KEYCODE_DPAD_DOWN);
        wireKey(R.id.funcLeft, KeyEvent.KEYCODE_DPAD_LEFT);
        wireKey(R.id.funcRight, KeyEvent.KEYCODE_DPAD_RIGHT);

        activity.findViewById(R.id.funcPaste).setOnClickListener(v -> {
            // Types the phone's clipboard into the host
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(activity);
                if (text != null && text.length() > 0) {
                    conn.sendUtf8Text(text.toString());
                }
            }
        });

        activity.findViewById(R.id.funcRotate).setOnClickListener(v ->
                activity.setRequestedOrientation(
                        activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ?
                                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT :
                                ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE));
    }

    private void setupStickyModifier(int viewId, byte flag, int androidKeyCode) {
        final View button = activity.findViewById(viewId);
        button.setOnClickListener(v -> {
            short translated = keyboardTranslator.translate(androidKeyCode, 0, -1);
            if ((stickyModifiers & flag) == 0) {
                stickyModifiers |= flag;
                conn.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, (byte) stickyModifiers, (byte) 0);
            }
            else {
                stickyModifiers &= ~flag;
                conn.sendKeyboardInput(translated, KeyboardPacket.KEY_UP, (byte) stickyModifiers, (byte) 0);
            }
            setModifierStyle(button, (stickyModifiers & flag) != 0);
        });
    }

    private static void setModifierStyle(View button, boolean active) {
        if (button instanceof Button) {
            ((Button) button).setTextColor(active ? ACCENT_COLOR : TEXT_COLOR);
        }
        else if (active) {
            ((ImageButton) button).setColorFilter(ACCENT_COLOR, PorterDuff.Mode.SRC_IN);
        }
        else {
            ((ImageButton) button).clearColorFilter();
        }
    }

    private void releaseStickyModifiers() {
        int[][] modifiers = {
                { KeyboardPacket.MODIFIER_META, KeyEvent.KEYCODE_META_LEFT, R.id.modifierWinButton },
                { KeyboardPacket.MODIFIER_SHIFT, KeyEvent.KEYCODE_SHIFT_LEFT, R.id.modifierShiftButton },
                { KeyboardPacket.MODIFIER_CTRL, KeyEvent.KEYCODE_CTRL_LEFT, R.id.modifierCtrlButton },
                { KeyboardPacket.MODIFIER_ALT, KeyEvent.KEYCODE_ALT_LEFT, R.id.modifierAltButton },
        };
        for (int[] m : modifiers) {
            if ((stickyModifiers & m[0]) != 0) {
                stickyModifiers &= ~m[0];
                conn.sendKeyboardInput(keyboardTranslator.translate(m[1], 0, -1),
                        KeyboardPacket.KEY_UP, (byte) stickyModifiers, (byte) 0);
            }
            setModifierStyle(activity.findViewById(m[2]), false);
        }
    }

    private void watchIme() {
        final FrameLayout contentView = activity.findViewById(android.R.id.content);

        // The bar is translated into the bottom padding reserved for the
        // keyboard, so padding must not clip it
        contentView.setClipToPadding(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            contentView.setOnApplyWindowInsetsListener((v, insets) -> {
                onImeStateChanged(insets.isVisible(WindowInsets.Type.ime()),
                        insets.getInsets(WindowInsets.Type.ime()).bottom);
                return insets;
            });
        }
        else {
            contentView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    Rect visibleFrame = new Rect();
                    contentView.getWindowVisibleDisplayFrame(visibleFrame);
                    int rootHeight = contentView.getRootView().getHeight();
                    int coveredHeight = rootHeight - visibleFrame.bottom;
                    onImeStateChanged(coveredHeight > rootHeight * 0.15, coveredHeight);
                }
            });
        }
    }

    // Called whenever the user shows or hides the soft keyboard
    public void onKeyboardRequested() {
        keyboardRequested = true;
    }

    // Whether the soft keyboard is actually on screen
    public boolean isImeShowing() {
        return imeShowing;
    }

    private void onImeStateChanged(boolean visible, int height) {
        imeShowing = visible;
        if (!visible) {
            keyboardRequested = false;
        }
        visible = visible && keyboardRequested;
        imeVisible = visible;
        imeHeight = Math.max(0, height);

        // The soft keyboard replaces the function pad if it comes back
        if (visible && functionPadVisible) {
            setFunctionPadVisible(false);
        }
        updateLayout();
    }

    private void setFunctionPadVisible(boolean visible) {
        functionPadVisible = visible;
        padToggleButton.setBackgroundResource(visible ?
                R.drawable.func_toggle_active_bg : android.R.color.transparent);
    }

    private void updateLayout() {
        boolean barVisible = imeVisible || functionPadVisible;
        if (barVisible != barShowing) {
            barShowing = barVisible;
            modifierBar.setVisibility(barVisible ? View.VISIBLE : View.GONE);
            if (!barVisible) {
                releaseStickyModifiers();
            }
        }

        // Shrink the stream area so it stays clear of the keyboard (or pad)
        // and the bar; the bar and pad are shifted down into that space
        float density = activity.getResources().getDisplayMetrics().density;
        int barHeight = (int) (BAR_HEIGHT_DP * density);
        int padHeight = (int) (PAD_HEIGHT_DP * density);
        int belowBar = imeVisible ? imeHeight : (functionPadVisible ? padHeight : 0);
        int bottomInset = barVisible ? belowBar + barHeight : 0;

        View contentView = activity.findViewById(android.R.id.content);
        if (contentView.getPaddingBottom() != bottomInset) {
            contentView.setPadding(0, 0, 0, bottomInset);
        }
        modifierBar.setTranslationY(barVisible ? barHeight : 0);
        functionPad.setVisibility(functionPadVisible ? View.VISIBLE : View.GONE);
        functionPad.setTranslationY(functionPadVisible ? bottomInset : 0);
    }
}
