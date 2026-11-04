// ClipboardSync: two-way text clipboard sync between this Windows host and
// the Moonlight desktop mode app on Android.
//
// Listens on TCP port 48250. Each message is one line holding the Base64 of
// the UTF-8 clipboard text. Only connections from private, link-local and
// Tailscale (100.64.0.0/10) addresses are accepted. Traffic is not encrypted.
//
// Build: build.ps1 (uses the C# compiler that ships with Windows).

using System;
using System.Drawing;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;

static class Log
{
    static readonly string path = Path.Combine(Path.GetTempPath(), "ClipboardSync.log");
    public static void Write(string msg)
    {
        try { File.AppendAllText(path, DateTime.Now.ToString("HH:mm:ss.fff ") + msg + Environment.NewLine); } catch { }
    }
}

class Controller : Form
{
    const int PORT = 48250;
    const int WM_CLIPBOARDUPDATE = 0x031D;
    const int MAX_TEXT_LENGTH = 1024 * 1024;
    const int MAX_READ_ATTEMPTS = 10;
    const int READ_RETRY_MS = 100;

    [DllImport("user32.dll")] static extern bool AddClipboardFormatListener(IntPtr hwnd);
    [DllImport("user32.dll")] static extern bool RemoveClipboardFormatListener(IntPtr hwnd);

    readonly NotifyIcon tray = new NotifyIcon();
    readonly object clientLock = new object();
    TcpClient client;
    StreamWriter writer;
    string lastSyncedText;

    public Controller()
    {
        ShowInTaskbar = false;
        WindowState = FormWindowState.Minimized;
        Opacity = 0;
        CreateHandle();

        var menu = new ContextMenuStrip();
        menu.Items.Add("Exit", null, (s, e) => Application.Exit());
        tray.ContextMenuStrip = menu;
        tray.Icon = SystemIcons.Information;
        tray.Text = "Clipboard sync: waiting for phone";
        tray.Visible = true;

        new Thread(AcceptLoop) { IsBackground = true }.Start();
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        AddClipboardFormatListener(Handle);
    }

    protected override void SetVisibleCore(bool value) { base.SetVisibleCore(false); }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        RemoveClipboardFormatListener(Handle);
        tray.Visible = false;
        base.OnFormClosing(e);
    }

    protected override void WndProc(ref Message m)
    {
        if (m.Msg == WM_CLIPBOARDUPDATE) SendLocalClipboard();
        base.WndProc(ref m);
    }

    void SendLocalClipboard() { SendLocalClipboard(0); }

    void SendLocalClipboard(int attempt)
    {
        string text;
        try
        {
            if (!Clipboard.ContainsText()) return;
            text = Clipboard.GetText();
        }
        catch (ExternalException)
        {
            // The copying app often still holds the clipboard when the update
            // notification arrives, so retry shortly instead of dropping it
            if (attempt < MAX_READ_ATTEMPTS)
            {
                var retry = new System.Windows.Forms.Timer { Interval = READ_RETRY_MS };
                retry.Tick += (s, e) => { retry.Dispose(); SendLocalClipboard(attempt + 1); };
                retry.Start();
            }
            else Log.Write("clipboard busy, not sent");
            return;
        }

        if (string.IsNullOrEmpty(text) || text.Length > MAX_TEXT_LENGTH || text == lastSyncedText) return;
        lastSyncedText = text;
        Log.Write(Send(text) ? "sent " + text.Length + " chars to phone" : "copied, but no phone connected");
    }

    bool Send(string text)
    {
        lock (clientLock)
        {
            if (writer == null) return false;
            try
            {
                writer.Write(Convert.ToBase64String(Encoding.UTF8.GetBytes(text)) + "\n");
                writer.Flush();
                return true;
            }
            catch (Exception ex) { Log.Write("send failed: " + ex.Message); return false; }
        }
    }

    static bool IsAllowed(IPAddress a)
    {
        if (a.IsIPv4MappedToIPv6) a = a.MapToIPv4();
        if (IPAddress.IsLoopback(a)) return true;
        if (a.AddressFamily == AddressFamily.InterNetworkV6)
            return a.IsIPv6LinkLocal || a.IsIPv6SiteLocal || (a.GetAddressBytes()[0] & 0xFE) == 0xFC;
        byte[] b = a.GetAddressBytes();
        return b[0] == 10
            || (b[0] == 172 && b[1] >= 16 && b[1] <= 31)
            || (b[0] == 192 && b[1] == 168)
            || (b[0] == 169 && b[1] == 254)
            || (b[0] == 100 && b[1] >= 64 && b[1] <= 127);
    }

    void AcceptLoop()
    {
        var listener = new TcpListener(IPAddress.IPv6Any, PORT);
        listener.Server.DualMode = true;
        try { listener.Start(); }
        catch (Exception ex)
        {
            Log.Write("listen failed: " + ex.Message);
            BeginInvoke((Action)(() => tray.ShowBalloonTip(3000, "Clipboard sync",
                "Port " + PORT + " is in use. Is another copy running?", ToolTipIcon.Warning)));
            return;
        }

        while (true)
        {
            TcpClient c = listener.AcceptTcpClient();
            var remote = ((IPEndPoint)c.Client.RemoteEndPoint).Address;
            if (!IsAllowed(remote))
            {
                Log.Write("rejected " + remote);
                c.Close();
                continue;
            }

            Log.Write("connected " + remote);
            // The newest connection replaces any previous one
            lock (clientLock)
            {
                if (client != null) client.Close();
                client = c;
                writer = new StreamWriter(c.GetStream(), Encoding.ASCII);
            }
            BeginInvoke((Action)(() =>
            {
                tray.Text = "Clipboard sync: connected";
                lastSyncedText = null;
                SendLocalClipboard();
            }));
            new Thread(() => ReadLoop(c)) { IsBackground = true }.Start();
        }
    }

    void ReadLoop(TcpClient c)
    {
        try
        {
            var reader = new StreamReader(c.GetStream(), Encoding.ASCII);
            string line;
            while ((line = reader.ReadLine()) != null)
            {
                string text;
                try { text = Encoding.UTF8.GetString(Convert.FromBase64String(line)); }
                catch (FormatException) { continue; }
                if (text.Length == 0 || text.Length > MAX_TEXT_LENGTH) continue;
                Log.Write("received " + text.Length + " chars from phone");
                BeginInvoke((Action)(() => ApplyRemoteClipboard(text)));
            }
        }
        catch (Exception) { }

        lock (clientLock)
        {
            if (client != c) return;
            client = null;
            writer = null;
        }
        c.Close();
        Log.Write("disconnected");
        BeginInvoke((Action)(() => tray.Text = "Clipboard sync: waiting for phone"));
    }

    void ApplyRemoteClipboard(string text)
    {
        if (text == lastSyncedText) return;
        lastSyncedText = text;
        // The clipboard can be briefly locked by another app
        for (int i = 0; i < 5; i++)
        {
            try { Clipboard.SetText(text); return; }
            catch (ExternalException) { Thread.Sleep(50); }
        }
        Log.Write("could not set clipboard");
    }
}

static class Program
{
    [STAThread]
    static void Main()
    {
        bool created;
        using (new Mutex(true, "MoonlightClipboardSync", out created))
        {
            if (!created) return;
            Application.EnableVisualStyles();
            Application.Run(new Controller());
        }
    }
}
