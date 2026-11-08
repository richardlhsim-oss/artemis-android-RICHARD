package com.limelight.binding.input.touch;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.utils.PanZoomHandler;

/**
 * Absolute touch gestures for desktop use on a zoomable view:
 * tap clicks where the finger is, one-finger drag pans the view, double-tap
 * double-clicks, double-tap then drag presses and drags (moving windows and
 * items, selecting text), press and hold right-clicks, and two-finger drag
 * scrolls.
 */
public class DirectTouchGestures {
    private static final int DOUBLE_TAP_TIME_MS = 300;
    private static final int LONG_PRESS_TIME_MS = 650;
    private static final int CLICK_RELEASE_DELAY_MS = 100;
    private static final float TAP_SLOP_DP = 12;
    private static final float DOUBLE_TAP_SLOP_DP = 40;
    private static final float SCROLL_SPEED_FACTOR = 3;
    // A press within the host's double-click time (500 ms by default on
    // Windows) of the previous click would be a double click, which on a title
    // bar maximizes the window instead of moving it, so drags wait this long
    private static final int DRAG_AFTER_CLICK_DELAY_MS = 550;

    private static final int STATE_IDLE = 0;
    private static final int STATE_PENDING = 1;
    private static final int STATE_PANNING = 2;
    private static final int STATE_SELECTING = 3;
    private static final int STATE_SCROLLING = 4;
    private static final int STATE_CANCELLED = 5;
    // Second press of a double tap, before it is known to be a tap or a drag
    private static final int STATE_DOUBLE_PENDING = 6;
    // Dragging after a double tap, waiting for the double-click time to pass
    private static final int STATE_DRAG_WAIT = 7;

    private final NvConnection conn;
    private final View streamView;
    private final PanZoomHandler panZoomHandler;
    private final byte buttonPrimary;
    private final byte buttonSecondary;
    private final float tapSlop;
    private final float doubleTapSlop;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int state = STATE_IDLE;
    private float downX, downY;
    private float prevX, prevY;
    private float prevScrollY;
    private long lastTapUpTime;
    private float lastTapX, lastTapY;
    private boolean longPressed;
    private Runnable pendingRelease;
    private byte pendingReleaseButton;
    private long lastClickDownTime;

    private final Runnable startDragRunnable = new Runnable() {
        @Override
        public void run() {
            if (state != STATE_DRAG_WAIT) {
                return;
            }
            // Press where the second tap started, then follow the finger
            flushPendingRelease();
            moveCursorTo(downX, downY);
            conn.sendMouseButtonDown(buttonPrimary);
            moveCursorTo(prevX, prevY);
            state = STATE_SELECTING;
        }
    };

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (state == STATE_PENDING) {
                longPressed = true;
                streamView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
        }
    };

    public DirectTouchGestures(NvConnection conn, View streamView, PanZoomHandler panZoomHandler, boolean swapped) {
        this.conn = conn;
        this.streamView = streamView;
        this.panZoomHandler = panZoomHandler;
        this.buttonPrimary = swapped ? MouseButtonPacket.BUTTON_RIGHT : MouseButtonPacket.BUTTON_LEFT;
        this.buttonSecondary = swapped ? MouseButtonPacket.BUTTON_LEFT : MouseButtonPacket.BUTTON_RIGHT;
        float density = streamView.getResources().getDisplayMetrics().density;
        this.tapSlop = TAP_SLOP_DP * density;
        this.doubleTapSlop = DOUBLE_TAP_SLOP_DP * density;
    }

    // Events are in the stream view's parent coordinates
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = prevX = event.getX();
                downY = prevY = event.getY();
                longPressed = false;
                if (event.getEventTime() - lastTapUpTime < DOUBLE_TAP_TIME_MS &&
                        Math.hypot(downX - lastTapX, downY - lastTapY) < doubleTapSlop) {
                    // Second press of a double tap: a release makes it a double
                    // click, a drag presses and drags
                    state = STATE_DOUBLE_PENDING;
                }
                else {
                    state = STATE_PENDING;
                    handler.postDelayed(longPressRunnable, LONG_PRESS_TIME_MS);
                }
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                endSelection();
                handler.removeCallbacks(longPressRunnable);
                handler.removeCallbacks(startDragRunnable);
                if (event.getPointerCount() == 2) {
                    state = STATE_SCROLLING;
                    prevScrollY = (event.getY(0) + event.getY(1)) / 2;
                    // Windows scrolls whatever is under the cursor, so put the
                    // cursor between the fingers to scroll that pane directly
                    moveCursorTo((event.getX(0) + event.getX(1)) / 2, prevScrollY);
                }
                else {
                    state = STATE_CANCELLED;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                float x = event.getX();
                float y = event.getY();
                switch (state) {
                    case STATE_PENDING:
                        if (Math.hypot(x - downX, y - downY) > tapSlop) {
                            handler.removeCallbacks(longPressRunnable);
                            state = STATE_PANNING;
                            panZoomHandler.panBy(x - prevX, y - prevY);
                        }
                        else {
                            return true;
                        }
                        break;
                    case STATE_PANNING:
                        panZoomHandler.panBy(x - prevX, y - prevY);
                        break;
                    case STATE_DOUBLE_PENDING:
                        if (Math.hypot(x - downX, y - downY) > tapSlop) {
                            state = STATE_DRAG_WAIT;
                            long wait = lastClickDownTime + DRAG_AFTER_CLICK_DELAY_MS - SystemClock.uptimeMillis();
                            handler.postDelayed(startDragRunnable, Math.max(0, wait));
                        }
                        break;
                    case STATE_SELECTING:
                        moveCursorTo(x, y);
                        break;
                    case STATE_SCROLLING:
                        if (event.getPointerCount() >= 2) {
                            float focusY = (event.getY(0) + event.getY(1)) / 2;
                            conn.sendMouseHighResScroll((short) ((focusY - prevScrollY) * SCROLL_SPEED_FACTOR));
                            prevScrollY = focusY;
                        }
                        break;
                }
                prevX = x;
                prevY = y;
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                if (state == STATE_SCROLLING) {
                    state = STATE_CANCELLED;
                }
                return true;

            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPressRunnable);
                handler.removeCallbacks(startDragRunnable);
                if (state == STATE_DOUBLE_PENDING) {
                    // Second click at the first tap's spot, so the host sees a double click
                    moveCursorTo(lastTapX, lastTapY);
                    click(buttonPrimary);
                    lastTapUpTime = 0;
                }
                else if (state == STATE_PENDING) {
                    moveCursorTo(event.getX(), event.getY());
                    if (longPressed) {
                        click(buttonSecondary);
                    }
                    else {
                        click(buttonPrimary);
                        lastTapUpTime = event.getEventTime();
                        lastTapX = event.getX();
                        lastTapY = event.getY();
                    }
                }
                else {
                    endSelection();
                    lastTapUpTime = 0;
                }
                state = STATE_IDLE;
                return true;

            case MotionEvent.ACTION_CANCEL:
                cancel();
                return true;

            default:
                return false;
        }
    }

    public void cancel() {
        handler.removeCallbacks(longPressRunnable);
        handler.removeCallbacks(startDragRunnable);
        endSelection();
        state = STATE_IDLE;
    }

    private void endSelection() {
        if (state == STATE_SELECTING) {
            conn.sendMouseButtonUp(buttonPrimary);
        }
    }

    private void click(final byte button) {
        flushPendingRelease();
        conn.sendMouseButtonDown(button);
        lastClickDownTime = SystemClock.uptimeMillis();
        // Released after a short delay for apps that poll button state
        pendingReleaseButton = button;
        pendingRelease = () -> {
            pendingRelease = null;
            conn.sendMouseButtonUp(button);
        };
        handler.postDelayed(pendingRelease, CLICK_RELEASE_DELAY_MS);
    }

    private void flushPendingRelease() {
        if (pendingRelease != null) {
            handler.removeCallbacks(pendingRelease);
            pendingRelease = null;
            conn.sendMouseButtonUp(pendingReleaseButton);
        }
    }

    private void moveCursorTo(float parentX, float parentY) {
        // Undo the zoom transform (pivot is the view's top-left corner)
        float x = (parentX - streamView.getX()) / streamView.getScaleX();
        float y = (parentY - streamView.getY()) / streamView.getScaleY();
        x = Math.min(Math.max(x, 0), streamView.getWidth());
        y = Math.min(Math.max(y, 0), streamView.getHeight());
        conn.sendMousePosition((short) x, (short) y, (short) streamView.getWidth(), (short) streamView.getHeight());
    }
}
