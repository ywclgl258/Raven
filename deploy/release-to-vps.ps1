#Requires -Version 5.1
<#
.SYNOPSIS
    One-click Raven release upload to the official VPS: verify the signed APK, publish the
    update-channel manifest, mirror the APK on the website, and upload the website page.

.DESCRIPTION
    Run from the repository root (or any directory; the script resolves the repo root itself).
    The APK must already be built and signed with the official release certificate. Use -Build
    to run `:app:validateReleaseSigning :app:assembleRelease` first.

    The script never reads, prints, or uploads signing passwords. It only verifies the public
    certificate fingerprint and publishes the APK bytes.

.PARAMETER Apk
    Release APK path. Defaults to app/build/outputs/apk/release/app-release.apk.

.PARAMETER VpsHost
    SSH host/alias for the official website server. Defaults to shizun-vps2 so the host alias
    stays in the developer's SSH config, not in the repository.

.PARAMETER Notes
    Release notes for the update-channel manifest.

.PARAMETER Build
    Build the signed release APK before uploading.

.PARAMETER DryRun
    Validate everything and print the intended upload commands without changing the VPS.

.EXAMPLE
    .\deploy\release-to-vps.ps1 -Build -Notes "本次更新说明"

.EXAMPLE
    .\deploy\release-to-vps.ps1 -Apk .\app\build\outputs\apk\release\app-release.apk -DryRun
#>
[CmdletBinding()]
param(
    [string]$Apk = "",
    [string]$VpsHost = "shizun-vps2",
    [string]$Notes = "",
    [switch]$Build,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

function Fail([string]$Message) {
    Write-Error $Message
    exit 1
}

function Get-Prop([string]$Path, [string]$Name) {
    $line = Get-Content -LiteralPath $Path |
        Where-Object { $_ -match "^\s*$([regex]::Escape($Name))\s*=" } |
        Select-Object -First 1
    if (-not $line) { Fail "release.properties is missing $Name" }
    return ($line -split "=", 2)[1].Trim()
}

function Find-BuildTool([string]$Name, [string]$SdkDir) {
    $candidates = Get-ChildItem -LiteralPath (Join-Path $SdkDir "build-tools") -Directory |
        Sort-Object { [version]$_.Name } -Descending |
        ForEach-Object { Join-Path $_.FullName $Name }
    $tool = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (-not $tool) { Fail "Cannot find $Name under $SdkDir\build-tools" }
    return $tool
}

function Get-InlineScriptHash([string]$Html) {
    $scripts = [regex]::Matches($Html, '(?s)<script(?<attrs>[^>]*)>(?<body>.*?)</script>')
    $inline = @($scripts | Where-Object { $_.Groups['attrs'].Value -notmatch '\bsrc\s*=' })
    if ($inline.Count -ne 1) {
        Fail "index.html must contain exactly one inline <script> block; found $($inline.Count)"
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes($inline[0].Groups['body'].Value)
    $digest = [Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    return "sha256-" + [Convert]::ToBase64String($digest)
}

function Assert-WebPage([string]$Html, [string]$Label) {
    $href = "https://ywclgl258.us.ci/download/Raven-v$versionName-release.apk"
    if ($Html -notmatch [regex]::Escape($href)) {
        Fail "$Label is missing the current VPS download link: $href"
    }
    if ($Html -notmatch [regex]::Escape("v$versionName")) {
        Fail "$Label does not display v$versionName"
    }
    if ($Html -notmatch '(?s)<code class="hash">\s*(?<hash>[0-9a-fA-F]{64})\s*</code>') {
        Fail "$Label is missing the displayed SHA-256 block"
    }
    if ($Matches['hash'].ToLowerInvariant() -ne $sha256) {
        Fail "$Label displays SHA-256 $($Matches['hash']) but the built APK is $sha256"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoRoot

$releaseFile = Join-Path $repoRoot "release.properties"
$versionName = Get-Prop $releaseFile "versionName"
$versionCode = Get-Prop $releaseFile "versionCode"
$expectedCert = (Get-Prop $releaseFile "releaseCertificate").Replace(":", "").ToUpperInvariant()

if ($Build) {
    Write-Host "Building signed release APK..."
    & .\gradlew.bat :app:validateReleaseSigning :app:assembleRelease --no-daemon
    if ($LASTEXITCODE -ne 0) { Fail "Gradle release build failed" }
}

if (-not $Apk) { $Apk = "app/build/outputs/apk/release/app-release.apk" }
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
if (-not (Test-Path -LiteralPath $apkPath)) { Fail "APK not found: $Apk" }

$localProperties = Join-Path $repoRoot "local.properties"
$sdkDir = $env:ANDROID_HOME
if (-not $sdkDir -and (Test-Path -LiteralPath $localProperties)) {
    $sdkLine = Get-Content -LiteralPath $localProperties |
        Where-Object { $_ -match "^\s*sdk\.dir\s*=" } |
        Select-Object -First 1
    if ($sdkLine) { $sdkDir = ((($sdkLine -split "=", 2)[1].Trim()) -replace '\\:', ':' -replace '\\\\', '\') }
}
if (-not $sdkDir) { Fail "ANDROID_HOME is not set and local.properties has no sdk.dir" }

$aapt2 = Find-BuildTool "aapt2.exe" $sdkDir
$apksigner = Find-BuildTool "apksigner.bat" $sdkDir

Write-Host "Verifying package metadata and signature..."
$badging = & $aapt2 dump badging $apkPath
if ($LASTEXITCODE -ne 0) { Fail "aapt2 failed to parse the APK" }
$packageLine = $badging | Where-Object { $_ -match "^package:" } | Select-Object -First 1
if ($packageLine -notmatch "name='com\.raven\.app'") { Fail "APK package is not com.raven.app" }
if ($packageLine -notmatch "versionCode='$([regex]::Escape($versionCode))'") { Fail "APK versionCode does not match release.properties" }
if ($packageLine -notmatch "versionName='$([regex]::Escape($versionName))'") { Fail "APK versionName does not match release.properties" }

$verify = (& $apksigner verify --verbose --print-certs $apkPath 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) { Fail "apksigner verification failed`n$verify" }
if ($verify -notmatch "Verified using v2 scheme.*: true") { Fail "APK is missing v2 signature" }
if ($verify -notmatch "Verified using v3 scheme.*: true") { Fail "APK is missing v3 signature" }
if ($verify -notmatch "Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)") { Fail "APK certificate digest not found" }
if ($Matches[1].ToUpperInvariant() -ne $expectedCert) { Fail "APK certificate does not match releaseCertificate" }

$verifyV1 = (& $apksigner verify --verbose --print-certs --min-sdk-version 23 --max-sdk-version 23 $apkPath 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) { Fail "apksigner v1 verification failed`n$verifyV1" }
if ($verifyV1 -notmatch "Verified using v1 scheme.*: true") { Fail "APK is missing v1 signature" }

$sha256 = [System.BitConverter]::ToString(
    [System.Security.Cryptography.SHA256]::Create().ComputeHash(
        [System.IO.File]::ReadAllBytes($apkPath)
    )
).Replace("-", "").ToLowerInvariant()
$size = (Get-Item -LiteralPath $apkPath).Length
$remoteApk = "app-release-$versionName.apk"
$remoteUrl = "https://ywclgl258.us.ci:8443/raven/releases"
if (-not $Notes) { $Notes = "Raven v$versionName 正式版更新。" }

Write-Host "APK: $apkPath"
Write-Host "Version: $versionName ($versionCode)"
Write-Host "SHA-256: $sha256"
Write-Host "Size: $size bytes"
Write-Host "VPS: $VpsHost"

# Notes travel as a UTF-8 file: inline shell variables lose quoting through two shells and
# split on spaces/CJK punctuation, which publish.py then rejects as extra arguments.
$notesPath = Join-Path ([IO.Path]::GetTempPath()) "raven-notes-$versionName.txt"
[IO.File]::WriteAllText($notesPath, $Notes, [Text.UTF8Encoding]::new($false))

# The page is uploaded with LF endings so the served bytes (and therefore the CSP script hash)
# are reproducible regardless of the developer checkout's core.autocrlf setting.
$pagePath = Join-Path $repoRoot "deploy/website/index.html"
$pageHtml = [IO.File]::ReadAllText($pagePath, [Text.UTF8Encoding]::new($false))
$pageHtml = $pageHtml -replace "`r`n", "`n" -replace "`r", "`n"
Assert-WebPage $pageHtml "deploy/website/index.html"
$inlineHash = Get-InlineScriptHash $pageHtml
$nginxPath = Join-Path $repoRoot "deploy/website/nginx-raven-website.conf"
$nginxConf = [IO.File]::ReadAllText($nginxPath, [Text.UTF8Encoding]::new($false))
if ($nginxConf -notmatch "script-src\s+'sha256-(?<hash>[A-Za-z0-9+/=]+)'") {
    Fail "deploy/website/nginx-raven-website.conf has no script-src sha256 hash"
}
$configHash = "sha256-" + $Matches['hash']
if ($inlineHash -ne $configHash) {
    Fail "CSP hash mismatch: index.html inline script is $inlineHash but nginx config allows $configHash; update deploy/website/nginx-raven-website.conf and deploy it"
}
$pageUploadPath = Join-Path ([IO.Path]::GetTempPath()) "raven-index-$versionName.html"
[IO.File]::WriteAllText($pageUploadPath, $pageHtml, [Text.UTF8Encoding]::new($false))

$publishCommand = "cd /root/raven-update-deploy; " +
    "python3 publish_with_notes.py --notes-file /root/raven-notes.txt " +
    "--apk $remoteApk --release-properties release.properties " +
    "--web-root /var/www/raven-update/public --base-url $remoteUrl"

if ($DryRun) {
    Write-Host "`n[DryRun] scp $apkPath ${VpsHost}:/root/raven-update-deploy/$remoteApk"
    Write-Host "[DryRun] scp release.properties ${VpsHost}:/root/raven-update-deploy/release.properties"
    Write-Host "[DryRun] ssh $VpsHost `"$publishCommand`""
    Write-Host "[DryRun] ssh $VpsHost `"install -m 0644 /root/raven-update-deploy/$remoteApk /var/www/raven-site/public/download/Raven-v$versionName-release.apk`""
    Write-Host "[DryRun] scp $pageUploadPath ${VpsHost}:/var/www/raven-site/public/index.html"
    Write-Host "[DryRun] scp deploy/update-server/publish.py ${VpsHost}:/root/raven-update-deploy/publish.py"
    Remove-Item -LiteralPath $pageUploadPath, $notesPath -Force -ErrorAction SilentlyContinue
    exit 0
}

Write-Host "Uploading APK and release metadata..."
& scp $apkPath "${VpsHost}:/root/raven-update-deploy/$remoteApk"
if ($LASTEXITCODE -ne 0) { Fail "scp APK failed" }
& scp $releaseFile "${VpsHost}:/root/raven-update-deploy/release.properties"
if ($LASTEXITCODE -ne 0) { Fail "scp release.properties failed" }
& scp $notesPath "${VpsHost}:/root/raven-notes.txt"
if ($LASTEXITCODE -ne 0) { Fail "scp release notes failed" }
& scp (Join-Path $repoRoot "deploy/update-server/publish_with_notes.py") "${VpsHost}:/root/raven-update-deploy/publish_with_notes.py"
if ($LASTEXITCODE -ne 0) { Fail "scp publish wrapper failed" }
& scp (Join-Path $repoRoot "deploy/update-server/publish.py") "${VpsHost}:/root/raven-update-deploy/publish.py"
if ($LASTEXITCODE -ne 0) { Fail "scp publish.py failed" }

Write-Host "Publishing update-channel manifest..."
& ssh $VpsHost $publishCommand
if ($LASTEXITCODE -ne 0) { Fail "Remote publish.py failed" }

Write-Host "Mirroring APK on the official website..."
& ssh $VpsHost "rm -f /root/raven-notes.txt"
& ssh $VpsHost "install -m 0644 /root/raven-update-deploy/$remoteApk /var/www/raven-site/public/download/Raven-v$versionName-release.apk"
if ($LASTEXITCODE -ne 0) { Fail "Website APK mirror failed" }

$remoteHash = (& ssh $VpsHost "sha256sum /var/www/raven-site/public/download/Raven-v$versionName-release.apk" 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) { Fail "Cannot hash the mirrored APK:`n$remoteHash" }
if ($remoteHash -notmatch '(?m)^([0-9a-fA-F]{64})\s') { Fail "Unexpected sha256sum output: $remoteHash" }
if ($Matches[1].ToLowerInvariant() -ne $sha256) {
    Fail "Mirrored APK SHA-256 $($Matches[1]) does not match built APK $sha256"
}

Write-Host "Uploading website page..."
& scp $pageUploadPath "${VpsHost}:/var/www/raven-site/public/index.html"
if ($LASTEXITCODE -ne 0) { Fail "Website upload failed" }

Write-Host "Verifying public endpoints..."
$manifest = (Invoke-WebRequest -Uri "$remoteUrl/latest" -UseBasicParsing).Content
if ($manifest -notmatch "`"tag_name`":\s*`"v$([regex]::Escape($versionName))`"") {
    Fail "Published manifest does not report v$versionName"
}
if ($manifest -notmatch [regex]::Escape($sha256)) {
    Fail "Published manifest digest does not match the APK"
}
$websiteResponse = Invoke-WebRequest -Uri "https://ywclgl258.us.ci/" -UseBasicParsing
$liveCsp = ($websiteResponse.Headers["Content-Security-Policy"] | Out-String)
if ($liveCsp -notmatch "script-src\s+'sha256-(?<hash>[A-Za-z0-9+/=]+)'") {
    Fail "Live nginx response has no script-src sha256 hash"
}
$liveHash = "sha256-" + $Matches['hash']
if ($liveHash -ne $inlineHash) {
    Fail "Live nginx CSP allows $liveHash but the uploaded page script hashes to $inlineHash; deploy the updated nginx-raven-website.conf"
}
Assert-WebPage $websiteResponse.Content "Official website"

Remove-Item -LiteralPath $pageUploadPath, $notesPath -Force -ErrorAction SilentlyContinue

Write-Host "`nRelease v$versionName published to the update channel and official website."
Write-Host "Manifest: $remoteUrl/latest"
Write-Host "Website:  https://ywclgl258.us.ci/"
