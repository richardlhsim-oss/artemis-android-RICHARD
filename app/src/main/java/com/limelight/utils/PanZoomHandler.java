package com.limelight.utils;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.limelight.Game;
import com.limelight.preferences.PreferenceConfiguration;

public class PanZoomHandler {
    static private final float MAX_SCALE = 10.0f;

    // Results of filterImplicitGesture()
    public static final int IMPLICIT_PASS = 0;
    public static final int IMPLICIT_CONSUMED = 1;
    public static final int IMPLICIT_CLAIM_STARTED = 2;

    private static final int GESTURE_IDLE = 0;
    private static final int GESTURE_UNDECIDED = 1;
    private static final int GESTURE_CLAIMED = 2;
    private static final int GESTURE_REJECTED = 3;
    private static final int GESTURE_PANNING = 4;
    // A touch shortly after a pinch: a tap still clicks, a drag pans
    private static final int GESTURE_PAN_CANDIDATE = 5;

    private static final float IMPLICIT_DECISION_SLOP_DP = 24;
    private static final float FOLLOW_CURSOR_MARGIN_DP = 48;
    private static final float SMART_ZOOM_SCALE = 2.5f;
    private static final int TWO_FINGER_TAP_TIME_MS = 250;
    private static final int SMART_ZOOM_DOUBLE_TAP_TIME_MS = 400;
    // After a pinch, one finger pans if it is down within this window
    // (or is still down from the pinch)
    private static final int PINCH_FOLLOW_UP_PAN_MS = 1000;

    private final float implicitDecisionSlop;
    private final float followCursorMargin;
    private int implicitState = GESTURE_IDLE;
    private long implicitStartTime;
    private float implicitStartSpan, implicitStartFocusX, implicitStartFocusY;
    private float implicitPrevSpan, implicitPrevFocusX, implicitPrevFocusY;
    private long lastTwoFingerTapTime;
    private float lastTwoFingerTapX, lastTwoFingerTapY;
    private long lastPinchEndTime;
    private boolean panPrimed;
    private float panPrevRawX, panPrevRawY;
    private float candidateDownRawX, candidateDownRawY;

    private final Game game;
    private final View streamView;
    private final PreferenceConfiguration prefConfig;
    private final boolean isTopMode;
    private final ScaleGestureDetector scaleGestureDetector;
    private final GestureDetector gestureDetector;
    private View parent;
    private float scaleFactor = 1.0f;
    private float childX, childY = 0;
    private float parentWidth, parentHeight = 0;
    private float childWidth, childHeight = 0;

    public PanZoomHandler(Context context, Game game, View streamView, PreferenceConfiguration prefConfig) {
        this.game = game;
        this.streamView = streamView;
        this.prefConfig = prefConfig;
        this.isTopMode = prefConfig.alignDisplayTopCenter;
        float density = context.getResources().getDisplayMetrics().density;
        implicitDecisionSlop = IMPLICIT_DECISION_SLOP_DP * density;
        followCursorMargin = FOLLOW_CURSOR_MARGIN_DP * density;
        scaleGestureDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new GestureListener());

        // Everything gets easier with 0,0 as the pivot point
        streamView.setPivotX(0);
        streamView.setPivotY(0);
    }

    public void handleTouchEvent(MotionEvent motionEvent) {
        scaleGestureDetector.onTouchEvent(motionEvent);
        gestureDetector.onTouchEvent(motionEvent);
    }

    public boolean isZoomed() {
        return scaleFactor > 1.01f;
    }

    // Watches finger gestures outside of zoom mode and claims those that look
    // like a pinch, so zooming works without switching modes. Two-finger
    // scrolls and taps pass through. Returns IMPLICIT_CLAIM_STARTED when a
    // pinch is first recognized (the caller should cancel touch handling).
    public int filterImplicitGesture(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (isZoomed() && event.getEventTime() - lastPinchEndTime < PINCH_FOLLOW_UP_PAN_MS) {
                    implicitState = GESTURE_PAN_CANDIDATE;
                    candidateDownRawX = event.getRawX();
                    candidateDownRawY = event.getRawY();
                    return IMPLICIT_PASS;
                }
                implicitState = GESTURE_IDLE;
                return IMPLICIT_PASS;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (implicitState == GESTURE_PAN_CANDIDATE) {
                    implicitState = GESTURE_IDLE;
                }
                if (implicitState == GESTURE_PANNING) {
                    // A second finger turns the pan back into a pinch
                    implicitState = GESTURE_CLAIMED;
                }
                if (implicitState == GESTURE_CLAIMED) {
                    if (event.getPointerCount() == 2) {
                        primePinch(event);
                    }
                    return IMPLICIT_CONSUMED;
                }
                if (implicitState == GESTURE_IDLE && event.getPointerCount() == 2) {
                    implicitState = GESTURE_UNDECIDED;
                    implicitStartTime = event.getEventTime();
                    implicitStartSpan = span(event);
                    implicitStartFocusX = focusX(event);
                    implicitStartFocusY = focusY(event);
                }
                else if (implicitState == GESTURE_UNDECIDED) {
                    // A third finger is not a pinch (e.g. the keyboard gesture)
                    implicitState = GESTURE_REJECTED;
                }
                return IMPLICIT_PASS;

            case MotionEvent.ACTION_MOVE:
                if (implicitState == GESTURE_PAN_CANDIDATE) {
                    if (Math.hypot(event.getRawX() - candidateDownRawX, event.getRawY() - candidateDownRawY) >
                            implicitDecisionSlop / 2) {
                        // It's a drag: pan the view instead of sending input
                        startFollowUpPan();
                        continueFollowUpPan(event);
                        return IMPLICIT_CLAIM_STARTED;
                    }
                    return IMPLICIT_PASS;
                }
                if (implicitState == GESTURE_UNDECIDED) {
                    if (event.getPointerCount() < 2) {
                        return IMPLICIT_PASS;
                    }
                    float spanDelta = Math.abs(span(event) - implicitStartSpan);
                    float focusDelta = (float) Math.hypot(focusX(event) - implicitStartFocusX,
                            focusY(event) - implicitStartFocusY);
                    if (spanDelta > implicitDecisionSlop && spanDelta > focusDelta) {
                        implicitState = GESTURE_CLAIMED;
                        primePinch(event);
                        return IMPLICIT_CLAIM_STARTED;
                    }
                    else if (focusDelta > implicitDecisionSlop * 1.5f && focusDelta > spanDelta * 2) {
                        // Fingers moving together: this is a scroll
                        implicitState = GESTURE_REJECTED;
                        return IMPLICIT_PASS;
                    }
                    // Hold the gesture back until we know what it is
                    return IMPLICIT_CONSUMED;
                }
                if (implicitState == GESTURE_CLAIMED) {
                    if (event.getPointerCount() >= 2) {
                        continuePinch(event);
                    }
                    return IMPLICIT_CONSUMED;
                }
                if (implicitState == GESTURE_PANNING) {
                    if (event.getPointerCount() == 1) {
                        continueFollowUpPan(event);
                    }
                    return IMPLICIT_CONSUMED;
                }
                return IMPLICIT_PASS;

            case MotionEvent.ACTION_POINTER_UP:
                if (implicitState == GESTURE_CLAIMED || implicitState == GESTURE_PANNING) {
                    if (event.getPointerCount() == 2) {
                        // One pinch finger lifted: the remaining one pans
                        startFollowUpPan();
                    }
                    return IMPLICIT_CONSUMED;
                }
                if (implicitState == GESTURE_UNDECIDED) {
                    // Released before moving enough to decide: it was a tap
                    implicitState = GESTURE_REJECTED;
                    if (event.getEventTime() - implicitStartTime < TWO_FINGER_TAP_TIME_MS) {
                        // Two quick two-finger taps in the same place toggle smart zoom
                        if (event.getEventTime() - lastTwoFingerTapTime < SMART_ZOOM_DOUBLE_TAP_TIME_MS &&
                                Math.hypot(implicitStartFocusX - lastTwoFingerTapX,
                                        implicitStartFocusY - lastTwoFingerTapY) < implicitDecisionSlop * 3) {
                            lastTwoFingerTapTime = 0;
                            smartZoomToggle(implicitStartFocusX, implicitStartFocusY);
                            return IMPLICIT_CLAIM_STARTED;
                        }
                        lastTwoFingerTapTime = event.getEventTime();
                        lastTwoFingerTapX = implicitStartFocusX;
                        lastTwoFingerTapY = implicitStartFocusY;
                    }
                }
                return IMPLICIT_PASS;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean claimed = implicitState == GESTURE_CLAIMED || implicitState == GESTURE_PANNING;
                implicitState = GESTURE_IDLE;
                if (claimed) {
                    lastPinchEndTime = event.getEventTime();
                    return IMPLICIT_CONSUMED;
                }
                return IMPLICIT_PASS;

            default:
                return IMPLICIT_PASS;
        }
    }

    // A third or fourth finger is a tap gesture, not a pinch
    public void abortImplicitGesture() {
        implicitState = GESTURE_REJECTED;
    }

    public void panBy(float dx, float dy) {
        if (parent == null) {
            parent = (View) streamView.getParent();
        }
        childX = streamView.getX() + dx;
        childY = streamView.getY() + dy;
        constrainToBounds();
    }

    public void resetImplicitGesture() {
        implicitState = GESTURE_IDLE;
        lastPinchEndTime = 0;
    }

    // Pans the zoomed view so the given point (parent coordinates) stays
    // inside the visible area with a small margin
    public void followPoint(float parentX, float parentY) {
        if (!isZoomed() || parent == null) {
            return;
        }
        float dx = 0, dy = 0;
        if (parentX < followCursorMargin) {
            dx = followCursorMargin - parentX;
        }
        else if (parentX > parent.getWidth() - followCursorMargin) {
            dx = parent.getWidth() - followCursorMargin - parentX;
        }
        if (parentY < followCursorMargin) {
            dy = followCursorMargin - parentY;
        }
        else if (parentY > parent.getHeight() - followCursorMargin) {
            dy = parent.getHeight() - followCursorMargin - parentY;
        }
        if (dx != 0 || dy != 0) {
            childX = streamView.getX() + dx;
            childY = streamView.getY() + dy;
            constrainToBounds();
        }
    }

    private void smartZoomToggle(float focusX, float focusY) {
        if (parent == null) {
            parent = (View) streamView.getParent();
        }
        float newScale = isZoomed() ? 1.0f : SMART_ZOOM_SCALE;
        applyScale(newScale, focusX, focusY);
        constrainToBounds();
    }

    private void applyScale(float newScaleFactor, float focusX, float focusY) {
        childX = focusX + (streamView.getX() - focusX) / scaleFactor * newScaleFactor;
        childY = focusY + (streamView.getY() - focusY) / scaleFactor * newScaleFactor;
        scaleFactor = newScaleFactor;
        streamView.setScaleX(scaleFactor);
        streamView.setScaleY(scaleFactor);
    }

    private void primePinch(MotionEvent event) {
        if (parent == null) {
            parent = (View) streamView.getParent();
        }
        implicitPrevSpan = span(event);
        implicitPrevFocusX = focusX(event);
        implicitPrevFocusY = focusY(event);
    }

    private void continuePinch(MotionEvent event) {
        float span = span(event);
        float fx = focusX(event);
        float fy = focusY(event);
        if (implicitPrevSpan > 0) {
            float newScale = Math.max(1, Math.min(scaleFactor * (span / implicitPrevSpan), MAX_SCALE));
            applyScale(newScale, fx, fy);
            // Pan with the fingers as well
            childX += fx - implicitPrevFocusX;
            childY += fy - implicitPrevFocusY;
            constrainToBounds();
        }
        implicitPrevSpan = span;
        implicitPrevFocusX = fx;
        implicitPrevFocusY = fy;
    }

    private void startFollowUpPan() {
        implicitState = GESTURE_PANNING;
        // Primed on the first single-finger move, since only then is the
        // remaining finger guaranteed to be at index 0 for getRawX/Y
        panPrimed = false;
    }

    private void continueFollowUpPan(MotionEvent event) {
        // Raw screen coordinates are unaffected by the view being moved
        float rawX = event.getRawX();
        float rawY = event.getRawY();
        if (panPrimed) {
            childX = streamView.getX() + rawX - panPrevRawX;
            childY = streamView.getY() + rawY - panPrevRawY;
            constrainToBounds();
        }
        panPrevRawX = rawX;
        panPrevRawY = rawY;
        panPrimed = true;
    }

    // Events passed to filterImplicitGesture() are in parent coordinates
    private static float span(MotionEvent e) {
        return (float) Math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0));
    }

    private static float focusX(MotionEvent e) {
        return (e.getX(0) + e.getX(1)) / 2;
    }

    private static float focusY(MotionEvent e) {
        return (e.getY(0) + e.getY(1)) / 2;
    }

    private void updateDimensions() {
        childHeight = streamView.getHeight() * scaleFactor;
        childWidth = streamView.getWidth() * scaleFactor;
        parentWidth = parent.getWidth();
        parentHeight = parent.getHeight();
    }

    private void constrainToBounds() {
        updateDimensions();

        if (parentWidth >= childWidth) {
            childX = (parentWidth - childWidth) / 2;
        } else {
            float boundaryX = parentWidth - childWidth;
            childX = Math.max(boundaryX, Math.min(childX, 0));
        }

        if (parentHeight >= childHeight) {
            if (isTopMode) {
                childY = 0;
            } else {
                childY = (parentHeight - childHeight) / 2;
            }
        } else {
            float boundaryY = parentHeight - childHeight;
            childY = Math.max(boundaryY, Math.min(childY, 0));
        }

        streamView.setX(childX);
        streamView.setY(childY);
    }

    public void handleSurfaceChange() {
        if (childWidth == 0 || parent == null) {
            // Retrieve parent, should handle both built-in display and external display
            parent = (View)streamView.getParent();
            return;
        }

        float prevChildWidth = childWidth;
        float prevChildHeight = childHeight;
        float prevParentWidth = parentWidth;
        float prevParentHeight = parentHeight;

        updateDimensions();

        float viewScaleX = childWidth / prevChildWidth;
        float viewScaleY = childHeight / prevChildHeight;

        float dPivotX1 = childX - prevParentWidth / 2;
        float dPivotY1 = childY - prevParentHeight / 2;

        float dPivotX2 = dPivotX1 * viewScaleX;
        float dPivotY2 = dPivotY1 * viewScaleY;

        childX = dPivotX2 + parentWidth / 2;
        childY = dPivotY2 + parentHeight / 2;

        streamView.setX(childX);
        streamView.setY(childY);

        constrainToBounds();
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float newScaleFactor = scaleFactor * detector.getScaleFactor();
            newScaleFactor = Math.max(1, Math.min(newScaleFactor, MAX_SCALE)); // Apply minimum scale

            // Calculate pivot point
            float focusX = detector.getFocusX();
            float focusY = detector.getFocusY();

            float dPivotX = (childX - focusX) / scaleFactor * newScaleFactor;
            float dPivotY = (childY - focusY) / scaleFactor * newScaleFactor;

            childX = focusX + dPivotX;
            childY = focusY + dPivotY;

            scaleFactor = newScaleFactor;

            streamView.setScaleX(scaleFactor);
            streamView.setScaleY(scaleFactor);

            streamView.setX(childX);
            streamView.setY(childY);

            constrainToBounds();
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            game.updatePipAutoEnter();
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            childX = streamView.getX() - distanceX;
            childY = streamView.getY() - distanceY;

            streamView.setX(childX);
            streamView.setY(childY);

            constrainToBounds();
            return true;
        }
    }
}
