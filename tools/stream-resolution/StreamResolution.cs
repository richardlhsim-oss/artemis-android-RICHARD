// StreamResolution: switches the primary monitor to the streaming client's
// resolution when a session starts and restores the original mode when it ends.
// Meant for Apollo / Vibepollo / Sunshine command preparations.
//
//   StreamResolution.exe --set              use the client's requested mode
//                                           (SUNSHINE_CLIENT_WIDTH/HEIGHT/FPS)
//   StreamResolution.exe --set 1920x1080     use a fixed mode (optionally @60)
//   StreamResolution.exe --restore          restore the mode saved by --set
//   StreamResolution.exe --list             print the modes the monitor supports
//   StreamResolution.exe --set ... --dry-run  show the chosen mode without applying it
//
// If the exact mode is not supported, the closest supported one is used.
// Changes are not written to the registry, so a reboot always returns to the
// normal resolution. Log: %TEMP%\StreamResolution.log
//
// Build: build.ps1 (uses the C# compiler that ships with Windows).

using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;

static class Native
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

static class Program
{
    static readonly string StateFile = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "StreamResolution", "saved_mode.txt");
    static readonly string LogFile = Path.Combine(Path.GetTempPath(), "StreamResolution.log");

    static void Log(string msg)
    {
        Console.WriteLine(msg);
        try { File.AppendAllText(LogFile, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss ") + msg + Environment.NewLine); } catch { }
    }

    static int Main(string[] args)
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

    static List<Native.DEVMODE> Modes()
    {
        var modes = new List<Native.DEVMODE>();
        var dm = Native.NewMode();
        for (int i = 0; Native.EnumDisplaySettings(null, i, ref dm); i++)
        {
            modes.Add(dm);
            dm = Native.NewMode();
        }
        return modes;
    }

    static Native.DEVMODE Current()
    {
        var dm = Native.NewMode();
        if (!Native.EnumDisplaySettings(null, Native.ENUM_CURRENT_SETTINGS, ref dm))
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
    static Native.DEVMODE Choose(int w, int h, int fps, int currentFreq)
    {
        var modes = Modes();
        int bestSize = int.MaxValue;
        foreach (var m in modes)
            bestSize = Math.Min(bestSize, Math.Abs(m.dmPelsWidth - w) + Math.Abs(m.dmPelsHeight - h));

        int target = fps > 0 ? fps : currentFreq;
        Native.DEVMODE best = default(Native.DEVMODE);
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
        Log((result == Native.DISP_CHANGE_SUCCESSFUL ? "set: " : "set FAILED (" + result + "): ") + summary);
        return result == Native.DISP_CHANGE_SUCCESSFUL ? 0 : 1;
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
        Log((result == Native.DISP_CHANGE_SUCCESSFUL ? "restored " : "restore FAILED (" + result + ") to ") +
            chosen.dmPelsWidth + "x" + chosen.dmPelsHeight + "@" + chosen.dmDisplayFrequency);
        if (result == Native.DISP_CHANGE_SUCCESSFUL) File.Delete(StateFile);
        return result == Native.DISP_CHANGE_SUCCESSFUL ? 0 : 1;
    }

    static int Apply(Native.DEVMODE mode)
    {
        var dm = Native.NewMode();
        dm.dmPelsWidth = mode.dmPelsWidth;
        dm.dmPelsHeight = mode.dmPelsHeight;
        dm.dmDisplayFrequency = mode.dmDisplayFrequency;
        dm.dmFields = Native.DM_PELSWIDTH | Native.DM_PELSHEIGHT | Native.DM_DISPLAYFREQUENCY;
        // Flags 0: change for this session only, not saved to the registry
        return Native.ChangeDisplaySettingsEx(null, ref dm, IntPtr.Zero, 0, IntPtr.Zero);
    }
}
