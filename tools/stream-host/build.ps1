# Builds StreamHost.exe with the C# compiler that ships with Windows
# (no SDK install needed).
$csc = "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
& $csc /nologo /target:winexe /optimize+ /out:"$PSScriptRoot\StreamHost.exe" `
    /r:System.Windows.Forms.dll /r:System.Drawing.dll "$PSScriptRoot\StreamHost.cs"
