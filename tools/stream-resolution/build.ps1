# Builds StreamResolution.exe with the C# compiler that ships with Windows
# (no SDK install needed).
$csc = "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
& $csc /nologo /target:exe /optimize+ /out:"$PSScriptRoot\StreamResolution.exe" "$PSScriptRoot\StreamResolution.cs"
