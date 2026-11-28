#Requires -Version 5.1
<#
.SYNOPSIS
    Pull open Raven feedback from the VPS and write one agent-ready document.
.DESCRIPTION
    The exported document contains user-submitted text and diagnostic excerpts, so it defaults to
    $HOME/.raven/feedback/FEEDBACK_INBOX.md, outside the repository. Never commit it.
.EXAMPLE
    .\deploy\pull-feedback.ps1
.EXAMPLE
    .\deploy\pull-feedback.ps1 -MarkExported
#>
[CmdletBinding()]
param(
    [string]$VpsHost = "shizun-vps2",
    [string]$RemoteData = "/var/lib/raven-feedback",
    [string]$Output = "",
    [switch]$MarkExported
)

$ErrorActionPreference = "Stop"
if ($RemoteData -match "['\r\n\x00]") {
    throw "Invalid RemoteData path: $RemoteData"
}
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $Output) {
    $homeDir = if ($HOME) { $HOME } else { [Environment]::GetFolderPath("UserProfile") }
    $Output = Join-Path $homeDir ".raven/feedback/FEEDBACK_INBOX.md"
}
if ([System.IO.Path]::IsPathRooted($Output)) {
    $outPath = $Output
} else {
    $outPath = Join-Path $repoRoot $Output
}
$outDir = Split-Path -Parent $outPath
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$tmpRemotePath = "/tmp/raven-feedback-inbox-" + [Guid]::NewGuid().ToString("N") + ".md"
$markFlag = if ($MarkExported) { " --mark-exported" } else { "" }
$remoteScript = @"
set -euo pipefail
umask 077
DATA_DIR='$RemoteData'
EXPORT=/opt/raven-feedback/export_feedback.py
OUT_FILE='$tmpRemotePath'
if [ "`$(id -u)" -eq 0 ] && command -v runuser >/dev/null 2>&1; then
    runuser -u raven-feedback -- python3 "`$EXPORT" --data-dir "`$DATA_DIR" --output "`$OUT_FILE"$markFlag
elif command -v sudo >/dev/null 2>&1; then
    sudo -u raven-feedback python3 "`$EXPORT" --data-dir "`$DATA_DIR" --output "`$OUT_FILE"$markFlag
else
    python3 "`$EXPORT" --data-dir "`$DATA_DIR" --output "`$OUT_FILE"$markFlag
fi
"@

try {
    ssh $VpsHost $remoteScript
    if ($LASTEXITCODE -ne 0) { throw "Remote feedback export failed" }
    scp "${VpsHost}:${tmpRemotePath}" $outPath
    if ($LASTEXITCODE -ne 0) { throw "Failed to download feedback inbox" }
} finally {
    ssh $VpsHost "rm -f '$tmpRemotePath'" 2>$null | Out-Null
}

Write-Host "Feedback inbox written to $outPath"
Write-Host "Ask the local agent to read that file and fix the listed tickets."
