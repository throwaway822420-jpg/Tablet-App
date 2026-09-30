# Builds a self-contained Slate.exe folder (no .NET install needed on the target PC).
# Usage: .\publish.ps1 [-Runtime win-x64|win-arm64]
param([string]$Runtime = 'win-x64')
$ErrorActionPreference = 'Stop'

$root = Join-Path $PSScriptRoot '..'
if (-not (Test-Path (Join-Path $root 'Slate\platform-tools\adb.exe'))) {
    & (Join-Path $PSScriptRoot 'fetch-platform-tools.ps1')
}

$out = Join-Path $root "publish\$Runtime"
dotnet publish (Join-Path $root 'Slate\Slate.csproj') -c Release -r $Runtime --self-contained true -o $out
Write-Host "Published to $out"
