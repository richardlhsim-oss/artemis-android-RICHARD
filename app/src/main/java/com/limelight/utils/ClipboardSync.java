package com.limelight.utils;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.limelight.LimeLog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Two-way text clipboard sync with the ClipboardSync helper running on the
 * host. Each message is one line holding the Base64 of the UTF-8 text.
 *
 * Android only lets the focused app read the clipboard, so local changes are
 * picked up while the stream is focused and when focus returns to it.
 */
public class ClipboardSync {
    public static final int PORT = 48250;

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int RETRY_DELAY_MS = 5000;
    private static final int MAX_TEXT_LENGTH = 1024 * 1024;

    private final Context context;
    private final String host;
    private final ClipboardManager clipboard;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean running;
    private volatile Socket socket;
    private Thread thread;

    // Last text exchanged in either direction, so changes aren't echoed back
    private String lastSyncedText;

    private final ClipboardManager.OnPrimaryClipChangedListener clipListener =
            new ClipboardManager.OnPrimaryClipChangedListener() {
                @Override
                public void onPrimaryClipChanged() {
                    sendLocalClipboard();
                }
            };

    public ClipboardSync(Context context, String host) {
        this.context = context.getApplicationContext();
        this.host = host;
        this.clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    public void start() {
        if (running || clipboard == null) {
            return;
        }
        running = true;
        clipboard.addPrimaryClipChangedListener(clipListener);

        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                connectLoop();
            }
        }, "ClipboardSync");
        thread.start();
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        clipboard.removePrimaryClipChangedListener(clipListener);
        closeSocket();
        thread.interrupt();
    }

    // Must be called on the main thread while the activity has focus
    public void sendLocalClipboard() {
        if (!running || !clipboard.hasPrimaryClip()) {
            return;
        }

        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return;
        }

        CharSequence text = clip.getItemAt(0).coerceToText(context);
        if (text == null || text.length() == 0 || text.length() > MAX_TEXT_LENGTH) {
            return;
        }

        final String value = text.toString();
        if (value.equals(lastSyncedText)) {
            return;
        }
        lastSyncedText = value;

        final Socket s = socket;
        if (s == null) {
            return;
        }

        // Network I/O is not allowed on the main thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String line = Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP) + "\n";
                    OutputStream out = s.getOutputStream();
                    synchronized (s) {
                        out.write(line.getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    }
                } catch (Exception e) {
                    LimeLog.warning("Clipboard send failed: " + e.getMessage());
                }
            }
        }).start();
    }

    private void connectLoop() {
        while (running) {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS);
                s.setKeepAlive(true);
                socket = s;
                LimeLog.info("Clipboard sync connected");

                // Offer the phone's current clipboard once connected
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        lastSyncedText = null;
                        sendLocalClipboard();
                    }
                });

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                String line;
                while (running && (line = reader.readLine()) != null) {
                    applyRemoteClipboard(line);
                }
            } catch (Exception e) {
                // Helper not running or connection lost; retry below
            } finally {
                closeSocket();
            }

            try {
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void applyRemoteClipboard(String line) {
        final String text;
        try {
            text = new String(Base64.decode(line, Base64.DEFAULT), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (text.isEmpty() || text.length() > MAX_TEXT_LENGTH) {
            return;
        }

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!running || text.equals(lastSyncedText)) {
                    return;
                }
                lastSyncedText = text;
                clipboard.setPrimaryClip(ClipData.newPlainText("PC clipboard", text));
            }
        });
    }

    private void closeSocket() {
        Socket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }
}
