package com.limelight.ui;

import android.annotation.TargetApi;
import android.content.ClipData;
import android.content.Context;
import android.text.ClipboardManager;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.text.InputType;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.View;
import android.view.ViewGroup;

public class StreamView extends SurfaceView {
    private double desiredAspectRatio;
    private InputCallbacks inputCallbacks;
    private TextInputCallbacks textInputCallbacks;

    public void setTextInputCallbacks(TextInputCallbacks callbacks) {
        this.textInputCallbacks = callbacks;
    }
    private boolean fillDisplay = false;

    public void setDesiredAspectRatio(double aspectRatio) {
        this.desiredAspectRatio = aspectRatio;
    }

    public void setInputCallbacks(InputCallbacks callbacks) {
        this.inputCallbacks = callbacks;
    }

    public void setFillDisplay(boolean fillDisplay) {
        this.fillDisplay = fillDisplay;
    }

    public StreamView(Context context) {
        super(context);
    }

    public StreamView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public StreamView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public StreamView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // If no fixed aspect ratio has been provided, simply use the default onMeasure() behavior
        if (desiredAspectRatio == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        // Based on code from: https://www.buzzingandroid.com/2012/11/easy-measuring-of-custom-views-with-specific-aspect-ratio/
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        int measuredHeight, measuredWidth;
        if (fillDisplay) {
            if (widthSize < heightSize * desiredAspectRatio) {
                measuredHeight = heightSize;
                measuredWidth = (int)(heightSize * desiredAspectRatio);
            } else {
                measuredWidth = widthSize;
                measuredHeight = (int)(widthSize / desiredAspectRatio);
            }
        }
        else {
            if (widthSize > heightSize * desiredAspectRatio) {
                measuredHeight = heightSize;
                measuredWidth = (int)(measuredHeight * desiredAspectRatio);
            } else {
                measuredWidth = widthSize;
                measuredHeight = (int)(measuredWidth / desiredAspectRatio);
            }
        }

        setMeasuredDimension(measuredWidth, measuredHeight);
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        // This callbacks allows us to override dumb IME behavior like when
        // Samsung's default keyboard consumes Shift+Space.
        if (inputCallbacks != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (inputCallbacks.handleKeyDown(event)) {
                    return true;
                }
            }
            else if (event.getAction() == KeyEvent.ACTION_UP) {
                if (inputCallbacks.handleKeyUp(event)) {
                    return true;
                }
            }
        }

        return super.onKeyPreIme(keyCode, event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (inputCallbacks != null) {
            inputCallbacks.handleFocusChange(hasWindowFocus);
        }
    }

    public interface InputCallbacks {
        boolean handleKeyUp(KeyEvent event);
        boolean handleKeyDown(KeyEvent event);
        boolean handleFocusChange(boolean hasWindowFocus);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        if (textInputCallbacks == null || !textInputCallbacks.isTextInputActive()) {
            return super.onCreateInputConnection(outAttrs);
        }

        // Present a real text editor to the IME so composing input (swipe
        // typing, autocorrect, voice dictation) works. The composed text is
        // mirrored to the host as deltas by the connection below.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI |
                EditorInfo.IME_FLAG_NO_FULLSCREEN |
                EditorInfo.IME_ACTION_NONE;
        return new StreamTextInputConnection(this);
    }

    private class StreamTextInputConnection extends BaseInputConnection {
        private String composingText = "";

        StreamTextInputConnection(StreamView targetView) {
            super(targetView, true);
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            applyDelta(text.toString(), false);
            return super.setComposingText(text, newCursorPosition);
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            applyDelta(text.toString(), true);
            return super.commitText(text, newCursorPosition);
        }

        @Override
        public boolean finishComposingText() {
            composingText = "";
            return super.finishComposingText();
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            // IMEs replace composing text via setComposingText, so this only
            // needs handling for deletions of already-committed text (e.g.
            // tapping a suggestion to replace the previous word)
            if (composingText.isEmpty() && beforeLength > 0) {
                textInputCallbacks.onTextInputDelta(beforeLength, "");
            }
            return super.deleteSurroundingText(beforeLength, afterLength);
        }

        @Override
        public boolean performEditorAction(int actionCode) {
            textInputCallbacks.onTextInputEnter();
            return true;
        }

        private void applyDelta(String newText, boolean committed) {
            int prefix = 0;
            while (prefix < composingText.length() && prefix < newText.length() &&
                    composingText.charAt(prefix) == newText.charAt(prefix)) {
                prefix++;
            }

            int deleteCount = composingText.length() - prefix;
            String insertText = newText.substring(prefix);
            if (deleteCount > 0 || !insertText.isEmpty()) {
                textInputCallbacks.onTextInputDelta(deleteCount, insertText);
            }

            composingText = committed ? "" : newText;
        }
    }

    public interface TextInputCallbacks {
        boolean isTextInputActive();
        void onTextInputDelta(int deleteCount, String insertText);
        void onTextInputEnter();
    }
}
