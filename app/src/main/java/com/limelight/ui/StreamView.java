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
import android.view.inputmethod.InputMethodManager;
import android.text.Editable;
import android.text.Selection;
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
        outAttrs.initialSelStart = 0;
        outAttrs.initialSelEnd = 0;
        return new StreamTextInputConnection(this);
    }

    // Mirrors IME edits to the host as deltas. The local editor only ever
    // holds the word being composed: it is cleared after each commit and the
    // IME is told the cursor position, otherwise IMEs (voice dictation in
    // particular) lose track of the editor state and stop responding.
    private class StreamTextInputConnection extends BaseInputConnection {
        private final InputMethodManager imm;
        private String composingText = "";

        StreamTextInputConnection(StreamView targetView) {
            super(targetView, true);
            imm = (InputMethodManager) targetView.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            applyDelta(text.toString());
            boolean result = super.setComposingText(text, newCursorPosition);
            composingText = text.toString();
            reportSelection();
            return result;
        }

        @Override
        public boolean setComposingRegion(int start, int end) {
            boolean result = super.setComposingRegion(start, end);
            // Re-composing text already in the editor: diff against it from now on
            Editable editable = getEditable();
            int from = Math.max(0, Math.min(start, end));
            int to = Math.min(editable.length(), Math.max(start, end));
            composingText = from < to ? editable.subSequence(from, to).toString() : "";
            reportSelection();
            return result;
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            applyDelta(text.toString());
            super.commitText(text, newCursorPosition);
            resetEditor();
            return true;
        }

        @Override
        public boolean finishComposingText() {
            // The composed text has already been typed on the host
            super.finishComposingText();
            resetEditor();
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            // With nothing being composed this is a backspace on committed text
            if (composingText.isEmpty() && beforeLength > 0) {
                textInputCallbacks.onTextInputDelta(beforeLength, "");
            }
            boolean result = super.deleteSurroundingText(beforeLength, afterLength);
            reportSelection();
            return result;
        }

        @Override
        public boolean performEditorAction(int actionCode) {
            textInputCallbacks.onTextInputEnter();
            return true;
        }

        private void applyDelta(String newText) {
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
        }

        private void resetEditor() {
            Editable editable = getEditable();
            removeComposingSpans(editable);
            editable.clear();
            composingText = "";
            reportSelection();
        }

        private void reportSelection() {
            Editable editable = getEditable();
            imm.updateSelection(StreamView.this,
                    Selection.getSelectionStart(editable), Selection.getSelectionEnd(editable),
                    getComposingSpanStart(editable), getComposingSpanEnd(editable));
        }
    }

    public interface TextInputCallbacks {
        boolean isTextInputActive();
        void onTextInputDelta(int deleteCount, String insertText);
        void onTextInputEnter();
    }
}
