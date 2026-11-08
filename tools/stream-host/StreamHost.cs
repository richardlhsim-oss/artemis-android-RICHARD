// StreamHost: host helper for Apollo / Vibepollo / Sunshine sessions. Combines
// the privacy screen (blanks the physical monitors while the stream still shows
// the desktop) and switching the monitor to the client's resolution.
//
//   StreamHost.exe                      run the privacy screen in the tray (for startup)
//   StreamHost.exe --start [WxH[@FPS]]  session start: client resolution + privacy on
//   StreamHost.exe --stop               session end: privacy off + original resolution
//
// Individual commands:
//   --privacy-on | --privacy-off | --privacy-toggle
//   --set [WxH[@FPS]] [--dry-run] | --restore | --list
//
// Hotkeys while running: Ctrl+Alt+Shift+P toggle, Ctrl+Alt+Shift+F9 on, F10 off.
// Without a mode, the client's resolution comes from SUNSHINE_CLIENT_WIDTH,
// SUNSHINE_CLIENT_HEIGHT and SUNSHINE_CLIENT_FPS. Logs: %TEMP%\PrivacyScreen.log
// and %TEMP%\StreamResolution.log.
//
// Build: build.ps1 (uses the C# compiler that ships with Windows).

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

static class Program
{
    [STAThread]
    static int Main(string[] args)
    {
        string cmd = args.Length > 0 ? args[0].ToLowerInvariant() : "";
        switch (cmd)
        {
            case "--set":
            case "--restore":
            case "--list":
                return Resolution.Run(args);

            case "--start":
            {
                var setArgs = new List<string> { "--set" };
                if (args.Length > 1) setArgs.Add(args[1]);
                // The resolution goes first: Windows refuses changes once the PC is locked
                int result = Resolution.Run(setArgs.ToArray());
                PrivacyRequest("--on");
                return result;
            }

            case "--stop":
                PrivacyRequest("--off");
                return Resolution.Run(new[] { "--restore" });

            case "--privacy-on": PrivacyRequest("--on"); return 0;
            case "--privacy-off": PrivacyRequest("--off"); return 0;
            case "--privacy-toggle": PrivacyRequest("--toggle"); return 0;

            case "--tray":
                Privacy.Run(args.Length > 1 ? new[] { args[1] } : new string[0]);
                return 0;

            case "":
                Privacy.Run(new string[0]);
                return 0;

            default:
                Console.WriteLine("Usage: StreamHost.exe [--start [WxH[@FPS]] | --stop | --privacy-on | --privacy-off | " +
                        "--privacy-toggle | --set [WxH[@FPS]] [--dry-run] | --restore | --list]");
                return 2;
        }
    }

    // Forwards to the running privacy screen, or starts one in the background
    // so the calling command (e.g. a session start command) doesn't block
    static void PrivacyRequest(string mode)
    {
        bool noInstance;
        using (var m = new Mutex(false, "PrivacyScreen.SingleInstance", out noInstance)) { }
        if (!noInstance)
        {
            using (var ev = new EventWaitHandle(false, EventResetMode.AutoReset,
                    mode == "--on" ? "PrivacyScreen.On" : mode == "--off" ? "PrivacyScreen.Off" : "PrivacyScreen.Toggle"))
            {
                ev.Set();
            }
            return;
        }
        if (mode == "--off")
        {
            return;
        }
        Process.Start(new ProcessStartInfo(Application.ExecutablePath, "--tray " + mode) { UseShellExecute = false });
    }
}

static class Log
{
    static readonly string path = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "PrivacyScreen.log");
    public static void Write(string msg)
    {
        try { System.IO.File.AppendAllText(path, DateTime.Now.ToString("HH:mm:ss.fff ") + msg + Environment.NewLine); } catch { }
    }
}

static class Native
{
    public const int WDA_EXCLUDEFROMCAPTURE = 0x11;
    public const int WS_EX_TRANSPARENT = 0x20, WS_EX_TOOLWINDOW = 0x80, WS_EX_LAYERED = 0x80000, WS_EX_NOACTIVATE = 0x08000000;
    public const int LWA_ALPHA = 2;
    public static readonly IntPtr HWND_TOPMOST = new IntPtr(-1);
    public const uint SWP_NOMOVE = 2, SWP_NOSIZE = 1, SWP_NOACTIVATE = 0x10, SWP_SHOWWINDOW = 0x40;
    public const int WM_HOTKEY = 0x312;
    public const uint MOD_ALT = 1, MOD_CONTROL = 2, MOD_SHIFT = 4, MOD_NOREPEAT = 0x4000;

    [DllImport("user32.dll")] public static extern bool SetWindowDisplayAffinity(IntPtr hWnd, uint affinity);
    [DllImport("user32.dll")] public static extern bool SetLayeredWindowAttributes(IntPtr hWnd, uint key, byte alpha, uint flags);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll")] public static extern bool RegisterHotKey(IntPtr hWnd, int id, uint mods, uint vk);
    [DllImport("user32.dll")] public static extern bool UnregisterHotKey(IntPtr hWnd, int id);
    [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr ctx);
}

class Overlay : Form
{
    readonly bool dot;
    public bool CaptureExcluded;

    public Overlay(Rectangle bounds, bool dot)
    {
        this.dot = dot;
        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = false;
        StartPosition = FormStartPosition.Manual;
        BackColor = dot ? Color.FromArgb(0, 200, 120) : Color.Black;
        TopMost = true;
        Bounds = bounds;
    }

    protected override bool ShowWithoutActivation { get { return true; } }

    protected override CreateParams CreateParams
    {
        get
        {
            var cp = base.CreateParams;
            cp.ExStyle |= Native.WS_EX_LAYERED | Native.WS_EX_TRANSPARENT | Native.WS_EX_TOOLWINDOW | Native.WS_EX_NOACTIVATE;
            return cp;
        }
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        Native.SetLayeredWindowAttributes(Handle, 0, 255, Native.LWA_ALPHA);
        // The status dot is deliberately visible in the stream, as feedback
        if (!dot) CaptureExcluded = Native.SetWindowDisplayAffinity(Handle, Native.WDA_EXCLUDEFROMCAPTURE);
    }

    public void Raise()
    {
        Native.SetWindowPos(Handle, Native.HWND_TOPMOST, 0, 0, 0, 0,
            Native.SWP_NOMOVE | Native.SWP_NOSIZE | Native.SWP_NOACTIVATE | Native.SWP_SHOWWINDOW);
    }
}

class Controller : Form
{
    const int HOTKEY_ID = 1;
    const int HOTKEY_ON_ID = 2;
    const int HOTKEY_OFF_ID = 3;
    readonly System.Collections.Generic.List<Overlay> overlays = new System.Collections.Generic.List<Overlay>();
    readonly NotifyIcon tray = new NotifyIcon();
    readonly System.Windows.Forms.Timer keepOnTop = new System.Windows.Forms.Timer();
    bool active;

    public Controller(string startMode)
    {
        ShowInTaskbar = false;
        WindowState = FormWindowState.Minimized;
        Opacity = 0;

        // The form is never shown, so create its handle explicitly: the hotkey
        // and cross-thread requests both need it
        CreateHandle();

        var menu = new ContextMenuStrip();
        menu.Items.Add("Toggle privacy screen", null, (s, e) => Toggle());
        menu.Items.Add("Exit", null, (s, e) => { SetActive(false); Application.Exit(); });
        tray.ContextMenuStrip = menu;
        tray.Icon = SystemIcons.Shield;
        tray.Text = "Privacy screen";
        tray.Visible = true;
        tray.DoubleClick += (s, e) => Toggle();

        keepOnTop.Interval = 1000;
        keepOnTop.Tick += (s, e) => { foreach (var o in overlays) o.Raise(); };

        SystemEvents.DisplaySettingsChanged += (s, e) => { if (active) { Rebuild(); } };

        if (startMode == "--on") SetActive(true);
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        // Ctrl+Alt+Shift+P first; the fallbacks cover another app owning it
        Keys[] candidates = { Keys.P, Keys.F12, Keys.F11 };
        uint mods = Native.MOD_CONTROL | Native.MOD_ALT | Native.MOD_SHIFT | Native.MOD_NOREPEAT;
        if (!Native.RegisterHotKey(Handle, HOTKEY_ON_ID, mods, (uint)Keys.F9) ||
                !Native.RegisterHotKey(Handle, HOTKEY_OFF_ID, mods, (uint)Keys.F10))
        {
            Log.Write("on/off hotkeys unavailable (error " + Marshal.GetLastWin32Error() + ")");
        }

        foreach (var k in candidates)
        {
            if (Native.RegisterHotKey(Handle, HOTKEY_ID,
                    Native.MOD_CONTROL | Native.MOD_ALT | Native.MOD_SHIFT | Native.MOD_NOREPEAT, (uint)k))
            {
                Log.Write("hotkey registered: Ctrl+Alt+Shift+" + k);
                return;
            }
            Log.Write("hotkey unavailable: Ctrl+Alt+Shift+" + k + " (error " + Marshal.GetLastWin32Error() + ")");
        }
        tray.ShowBalloonTip(3000, "Privacy screen", "No hotkey could be registered; use the tray menu.", ToolTipIcon.Warning);
    }

    protected override void SetVisibleCore(bool value) { base.SetVisibleCore(false); }

    protected override void WndProc(ref Message m)
    {
        if (m.Msg == Native.WM_HOTKEY)
        {
            int id = m.WParam.ToInt32();
            if (id == HOTKEY_ID) Toggle();
            else if (id == HOTKEY_ON_ID) SetActive(true);
            else if (id == HOTKEY_OFF_ID) SetActive(false);
        }
        base.WndProc(ref m);
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        Native.UnregisterHotKey(Handle, HOTKEY_ID);
        Native.UnregisterHotKey(Handle, HOTKEY_ON_ID);
        Native.UnregisterHotKey(Handle, HOTKEY_OFF_ID);
        SetActive(false);
        tray.Visible = false;
        base.OnFormClosing(e);
    }

    public void Toggle() { SetActive(!active); }

    public void SetActive(bool on)
    {
        if (on == active) return;
        active = on;
        Log.Write(on ? "privacy ON" : "privacy off");
        if (on) Rebuild(); else Clear();
        keepOnTop.Enabled = on;
        tray.Text = on ? "Privacy screen: ON" : "Privacy screen: off";
    }

    void Clear()
    {
        foreach (var o in overlays) { o.Close(); o.Dispose(); }
        overlays.Clear();
    }

    void Rebuild()
    {
        Clear();
        var screens = Screen.AllScreens;
        foreach (var s in screens)
        {
            var o = new Overlay(s.Bounds, false);
            overlays.Add(o);
            o.Show();
            o.Raise();
            Log.Write("overlay " + s.DeviceName + " " + s.Bounds + " excluded=" + o.CaptureExcluded);
        }

        // Small marker on the primary display so the remote viewer can tell
        // that privacy mode is on
        var p = Screen.PrimaryScreen.Bounds;
        var dot = new Overlay(new Rectangle(p.Right - 14, p.Top + 4, 10, 10), true);
        overlays.Add(dot);
        dot.Show();
        dot.Raise();

        foreach (var o in overlays)
            if (!o.CaptureExcluded && o.BackColor == Color.Black)
                tray.ShowBalloonTip(3000, "Privacy screen", "Capture exclusion is not supported on this Windows version; the stream would show black.", ToolTipIcon.Error);
    }
}

static class Privacy
{
    public static void Run(string[] args)
    {
        // Per-monitor v2 DPI awareness so monitor bounds are real pixels
        try { Native.SetProcessDpiAwarenessContext(new IntPtr(-4)); } catch { }

        string mode = args.Length > 0 ? args[0].ToLowerInvariant() : "";

        // --on --for N: switch off again after N seconds (safety net for tests)
        int autoOffSeconds = 0;
        for (int a = 1; a + 1 < args.Length; a++)
            if (args[a] == "--for") int.TryParse(args[a + 1], out autoOffSeconds);

        bool created;
        using (var mutex = new Mutex(true, "PrivacyScreen.SingleInstance", out created))
        using (var evOn = new EventWaitHandle(false, EventResetMode.AutoReset, "PrivacyScreen.On"))
        using (var evOff = new EventWaitHandle(false, EventResetMode.AutoReset, "PrivacyScreen.Off"))
        using (var evToggle = new EventWaitHandle(false, EventResetMode.AutoReset, "PrivacyScreen.Toggle"))
        {
            if (!created)
            {
                // Forward the request to the running instance
                if (mode == "--on") evOn.Set();
                else if (mode == "--off") evOff.Set();
                else evToggle.Set();
                if (mode == "--on" && autoOffSeconds > 0)
                {
                    Thread.Sleep(autoOffSeconds * 1000);
                    evOff.Set();
                }
                return;
            }

            Application.EnableVisualStyles();
            var controller = new Controller(mode);
            if (mode == "--on" && autoOffSeconds > 0)
            {
                var off = new System.Windows.Forms.Timer { Interval = autoOffSeconds * 1000 };
                off.Tick += (s, e) => { off.Stop(); controller.SetActive(false); };
                off.Start();
            }

            var listener = new Thread(() =>
            {
                var handles = new WaitHandle[] { evOn, evOff, evToggle };
                while (true)
                {
                    int i = WaitHandle.WaitAny(handles);
                    try
                    {
                        controller.BeginInvoke((MethodInvoker)(() =>
                        {
                            if (i == 0) controller.SetActive(true);
                            else if (i == 1) controller.SetActive(false);
                            else controller.Toggle();
                        }));
                    }
                    catch (Exception ex) { Log.Write("request failed: " + ex.Message); }
                }
            });
            listener.IsBackground = true;
            listener.Start();

            Application.Run(controller);
        }
    }
}

static class ResNative
{
    public const int ENUM_CURRENT_SETTINGS = -1;
    public const int DM_PELSWIDTH = 0x80000, DM_PELSHEIGHT = 0x100000, DM_DISPLAYFREQUENCY = 0x400000;
    public const int CDS_TEST = 0x2;
    public const int DISP_CHANGE_SUCCESSFUL = 0;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct DEVMODE
    {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmDeviceName;
        public short dmSpecVersion, dmDriverVersion, dmSize, dmDriverExtra;
        public int dmFields;
        public int dmPositionX, dmPositionY, dmDisplayOrientation, dmDisplayFixedOutput;
        public short dmColor, dmDuplex, dmYResolution, dmTTOption, dmCollate;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmFormName;
        public short dmLogPixels;
        public int dmBitsPerPel, dmPelsWidth, dmPelsHeight, dmDisplayFlags, dmDisplayFrequency;
        public int dmICMMethod, dmICMIntent, dmMediaType, dmDitherType, dmReserved1, dmReserved2, dmPanningWidth, dmPanningHeight;
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern bool EnumDisplaySettings(string device, int mode, ref DEVMODE dm);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern int ChangeDisplaySettingsEx(string device, ref DEVMODE dm, IntPtr hwnd, int flags, IntPtr param);

    public static DEVMODE NewMode()
    {
        var dm = new DEVMODE();
        dm.dmSize = (short)Marshal.SizeOf(typeof(DEVMODE));
        return dm;
    }
}

static class Resolution
{
    static readonly string StateFile = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "StreamResolution", "saved_mode.txt");
    static readonly string LogFile = Path.Combine(Path.GetTempPath(), "StreamResolution.log");

    public static void Log(string msg)
    {
        Console.WriteLine(msg);
        try { File.AppendAllText(LogFile, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss ") + msg + Environment.NewLine); } catch { }
    }

    public static int Run(string[] args)
    {
        try
        {
            string cmd = args.Length > 0 ? args[0].ToLowerInvariant() : "";
            bool dryRun = Array.IndexOf(args, "--dry-run") >= 0;
            switch (cmd)
            {
                case "--set": return Set(args.Length > 1 && !args[1].StartsWith("--") ? args[1] : null, dryRun);
                case "--restore": return Restore();
                case "--list": return List();
                default:
                    Console.WriteLine("Usage: StreamResolution.exe --set [WIDTHxHEIGHT[@FPS]] [--dry-run] | --restore | --list");
                    return 2;
            }
        }
        catch (Exception e)
        {
            Log("error: " + e.Message);
            return 1;
        }
    }

    static List<ResNative.DEVMODE> Modes()
    {
        var modes = new List<ResNative.DEVMODE>();
        var dm = ResNative.NewMode();
        for (int i = 0; ResNative.EnumDisplaySettings(null, i, ref dm); i++)
        {
            modes.Add(dm);
            dm = ResNative.NewMode();
        }
        return modes;
    }

    static ResNative.DEVMODE Current()
    {
        var dm = ResNative.NewMode();
        if (!ResNative.EnumDisplaySettings(null, ResNative.ENUM_CURRENT_SETTINGS, ref dm))
            throw new Exception("could not read the current display mode");
        return dm;
    }

    static int List()
    {
        var seen = new HashSet<string>();
        foreach (var m in Modes())
        {
            string s = m.dmPelsWidth + "x" + m.dmPelsHeight + "@" + m.dmDisplayFrequency;
            if (seen.Add(s)) Console.WriteLine(s);
        }
        var c = Current();
        Console.WriteLine("current: " + c.dmPelsWidth + "x" + c.dmPelsHeight + "@" + c.dmDisplayFrequency);
        return 0;
    }

    static bool ParseMode(string s, out int w, out int h, out int fps)
    {
        w = h = fps = 0;
        if (string.IsNullOrEmpty(s)) return false;
        string[] at = s.Split('@');
        string[] wh = at[0].ToLowerInvariant().Split('x');
        if (wh.Length != 2 || !int.TryParse(wh[0], out w) || !int.TryParse(wh[1], out h)) return false;
        if (at.Length > 1) int.TryParse(at[1], out fps);
        return w > 0 && h > 0;
    }

    // Exact size if available, otherwise the closest size by width and height;
    // then the requested refresh rate, or the highest one not above it
    static ResNative.DEVMODE Choose(int w, int h, int fps, int currentFreq)
    {
        var modes = Modes();
        int bestSize = int.MaxValue;
        foreach (var m in modes)
            bestSize = Math.Min(bestSize, Math.Abs(m.dmPelsWidth - w) + Math.Abs(m.dmPelsHeight - h));

        int target = fps > 0 ? fps : currentFreq;
        ResNative.DEVMODE best = default(ResNative.DEVMODE);
        int bestScore = int.MaxValue;
        bool found = false;
        foreach (var m in modes)
        {
            if (Math.Abs(m.dmPelsWidth - w) + Math.Abs(m.dmPelsHeight - h) != bestSize) continue;
            // Prefer refresh rates at or below the target, nearest first
            int f = m.dmDisplayFrequency;
            int score = f <= target ? target - f : 1000 + f - target;
            if (score < bestScore)
            {
                bestScore = score;
                best = m;
                found = true;
            }
        }
        if (!found) throw new Exception("the monitor reports no display modes");
        return best;
    }

    static int Set(string modeArg, bool dryRun)
    {
        int w, h, fps;
        if (!ParseMode(modeArg, out w, out h, out fps))
        {
            int.TryParse(Environment.GetEnvironmentVariable("SUNSHINE_CLIENT_WIDTH"), out w);
            int.TryParse(Environment.GetEnvironmentVariable("SUNSHINE_CLIENT_HEIGHT"), out h);
            int.TryParse(Environment.GetEnvironmentVariable("SUNSHINE_CLIENT_FPS"), out fps);
            if (w <= 0 || h <= 0)
            {
                Log("no resolution given and SUNSHINE_CLIENT_WIDTH/HEIGHT are not set; nothing to do");
                return 2;
            }
        }

        var current = Current();
        var chosen = Choose(w, h, fps, current.dmDisplayFrequency);
        string summary = "requested " + w + "x" + h + (fps > 0 ? "@" + fps : "") +
            ", current " + current.dmPelsWidth + "x" + current.dmPelsHeight + "@" + current.dmDisplayFrequency +
            ", chosen " + chosen.dmPelsWidth + "x" + chosen.dmPelsHeight + "@" + chosen.dmDisplayFrequency;
        if (dryRun)
        {
            Log("dry run: " + summary);
            return 0;
        }

        // Keep the first saved mode if a previous session never restored,
        // so the original desktop mode is never overwritten by a stream mode
        if (!File.Exists(StateFile))
        {
            Directory.CreateDirectory(Path.GetDirectoryName(StateFile));
            File.WriteAllText(StateFile, current.dmPelsWidth + "x" + current.dmPelsHeight + "@" + current.dmDisplayFrequency);
        }

        if (chosen.dmPelsWidth == current.dmPelsWidth && chosen.dmPelsHeight == current.dmPelsHeight &&
                chosen.dmDisplayFrequency == current.dmDisplayFrequency)
        {
            Log("already at the chosen mode: " + summary);
            return 0;
        }

        int result = Apply(chosen);
        Log((result == ResNative.DISP_CHANGE_SUCCESSFUL ? "set: " : "set FAILED (" + result + "): ") + summary);
        return result == ResNative.DISP_CHANGE_SUCCESSFUL ? 0 : 1;
    }

    static int Restore()
    {
        if (!File.Exists(StateFile))
        {
            Log("restore: nothing saved");
            return 0;
        }

        int w, h, fps;
        if (!ParseMode(File.ReadAllText(StateFile).Trim(), out w, out h, out fps))
        {
            File.Delete(StateFile);
            Log("restore: saved mode unreadable, discarded");
            return 1;
        }

        var chosen = Choose(w, h, fps, fps);
        int result = Apply(chosen);
        Log((result == ResNative.DISP_CHANGE_SUCCESSFUL ? "restored " : "restore FAILED (" + result + ") to ") +
            chosen.dmPelsWidth + "x" + chosen.dmPelsHeight + "@" + chosen.dmDisplayFrequency);
        if (result == ResNative.DISP_CHANGE_SUCCESSFUL) File.Delete(StateFile);
        return result == ResNative.DISP_CHANGE_SUCCESSFUL ? 0 : 1;
    }

    static int Apply(ResNative.DEVMODE mode)
    {
        var dm = ResNative.NewMode();
        dm.dmPelsWidth = mode.dmPelsWidth;
        dm.dmPelsHeight = mode.dmPelsHeight;
        dm.dmDisplayFrequency = mode.dmDisplayFrequency;
        dm.dmFields = ResNative.DM_PELSWIDTH | ResNative.DM_PELSHEIGHT | ResNative.DM_DISPLAYFREQUENCY;
        // Flags 0: change for this session only, not saved to the registry
        return ResNative.ChangeDisplaySettingsEx(null, ref dm, IntPtr.Zero, 0, IntPtr.Zero);
    }
}
