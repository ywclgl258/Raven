#Requires -Version 5.1
<#
.SYNOPSIS
    Pull open Raven feedback from the VPS and write one agent-ready document.
.EXAMPLE
    .\deploy\pull-feedback.ps1
#>
[CmdletBinding()]
param(
    [string]$VpsHost = "shizun-vps2",
    [string]$RemoteData = "/var/lib/raven-feedback",
    [string]$Output = "docs/agent/FEEDBACK_INBOX.md"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoRoot
$outPath = Join-Path $repoRoot $Output
$outDir = Split-Path -Parent $outPath
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$remoteScript = @'
set -euo pipefail
python3 /opt/raven-feedback/export_feedback.py --data-dir /var/lib/raven-feedback --output /tmp/raven-feedback-inbox.md
'@

ssh $VpsHost $remoteScript
if ($LASTEXITCODE -ne 0) { throw "Remote feedback export failed" }
scp "${VpsHost}:/tmp/raven-feedback-inbox.md" $outPath
if ($LASTEXITCODE -ne 0) { throw "Failed to download feedback inbox" }
ssh $VpsHost "rm -f /tmp/raven-feedback-inbox.md" | Out-Null

Write-Host "Feedback inbox written to $Output"
Write-Host "Ask the local agent to read that file and fix the listed tickets."
