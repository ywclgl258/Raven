# Raven 源码静态基线统计（只读：不构建、不运行测试、不改文件）
# 用法：pwsh -File .\scripts\audit-stats.ps1
# 目的：README / docs 中的模块、表、路由、Key、测试数量必须与源码一致；改代码后先跑本脚本再改文档。

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

$allKt = Get-ChildItem -Path . -Recurse -Filter *.kt -File |
    Where-Object { $_.FullName -notmatch '[\\/]build[\\/]' }

function Get-Lines([string]$Path) {
    [System.IO.File]::ReadAllLines((Resolve-Path -LiteralPath $Path)).Count
}

function Get-GroupLines($Files) {
    $items = @($Files | Where-Object { $_ -ne $null })
    if ($items.Count -eq 0) { return 0 }
    ($items | ForEach-Object { [System.IO.File]::ReadAllLines($_.FullName).Count } |
        Measure-Object -Sum).Sum
}

function Get-MatchCount($Files, [string]$Pattern) {
    $items = @($Files | Where-Object { $_ -ne $null })
    if ($items.Count -eq 0) { return 0 }
    $sum = (($items | Select-String -Pattern $Pattern -AllMatches) |
        ForEach-Object { $_.Matches.Count } | Measure-Object -Sum).Sum
    if ($null -eq $sum) { return 0 }
    return [int]$sum
}

# 1. 模块与代码量（模块清单来自 settings.gradle.kts）
$settings = Get-Content -Raw -LiteralPath "settings.gradle.kts"
$modules = [regex]::Matches($settings, '"(:[^"]+)"') |
    ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique

$root = (Get-Location).Path
$moduleRows = foreach ($module in $modules) {
    $relative = $module.TrimStart(":").Replace(":", [System.IO.Path]::DirectorySeparatorChar)
    $prefix = (Join-Path $root $relative) + [System.IO.Path]::DirectorySeparatorChar
    $files = @($allKt | Where-Object { $_.FullName.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase) })
    [pscustomobject]@{ Module = $module; Files = $files.Count; Lines = Get-GroupLines $files }
}

# 2. 测试注解静态计数
$unitFiles = $allKt | Where-Object { $_.FullName -match '[\\/]src[\\/]test[\\/]' }
$androidFiles = $allKt | Where-Object { $_.FullName -match '[\\/]src[\\/]androidTest[\\/]' }

# 3. Room schema
$databaseSource = Get-Content -Raw -LiteralPath "core/database/src/main/kotlin/com/raven/app/core/database/RavenDatabase.kt"
$entities = ([regex]::Matches($databaseSource, '\w+Entity::class')).Count
$schemaVersion = [regex]::Match($databaseSource, 'SCHEMA_VERSION:\s*Int\s*=\s*(\d+)').Groups[1].Value
$migrations = ([regex]::Matches($databaseSource, 'Migration\(\s*\d+\s*,\s*\d+\s*\)')).Count
$exportedSchemas = @(Get-ChildItem -Path "core/database/schemas/com.raven.app.core.database.RavenDatabase" -Filter *.json -File).Count

# 4. DataStore Key
$dataStoreSource = Get-Content -Raw -LiteralPath "core/database/src/main/kotlin/com/raven/app/core/database/datastore/SettingsDataStore.kt"
$fixedKeys = ([regex]::Matches($dataStoreSource, 'const val KEY_[A-Z0-9_]+\s*:\s*String\s*=')).Count
$dynamicKeyFamilies = ([regex]::Matches($dataStoreSource, 'const val SITE_[A-Z0-9_]*_PREFIX\s*:\s*String\s*=')).Count

# 5. 路由
$routeSource = Get-Content -Raw -LiteralPath "core/model/src/main/kotlin/com/raven/app/core/model/RavenRoute.kt"
$routeTypes = ([regex]::Matches($routeSource, '(?m)^\s*data (object|class) \w+')).Count
$navHostSource = Get-Content -Raw -LiteralPath "app/src/main/kotlin/com/raven/app/RavenNavHost.kt"
$navDestinations = ([regex]::Matches($navHostSource, 'composable<RavenRoute\.')).Count

# 6. 装配与站点
$mainFiles = $allKt | Where-Object { $_.FullName -match '[\\/]src[\\/]main[\\/]' }
$koinModules = Get-MatchCount $mainFiles 'val \w+Module\s*:\s*Module\s*=\s*module\s*\{'
$plugins = @($mainFiles | Where-Object {
        $_.FullName -match '[\\/]sites[\\/]' -and $_.Name -like '*SitePlugin.kt'
    }).Count
$repositories = @($allKt | Where-Object {
        $_.FullName -match '[\\/]core[\\/]database[\\/]' -and $_.Name -like '*Repository.kt'
    }).Count

Write-Host "=== Raven 源码静态基线 ===" -ForegroundColor Cyan
$moduleRows | Sort-Object -Property Lines -Descending | Format-Table -AutoSize | Out-String | Write-Host

Write-Host "模块数              : $($moduleRows.Count)" -ForegroundColor Green
Write-Host "Kotlin 文件/行数    : $($allKt.Count) / $(Get-GroupLines $allKt)"
Write-Host "Koin 模块           : $koinModules"
Write-Host "站点插件            : $plugins"
Write-Host "Room 实体/DAO       : $entities"
Write-Host "Room Schema 版本    : v$schemaVersion（迁移 $migrations 段，导出 JSON $exportedSchemas 个）"
Write-Host "领域仓储            : $repositories"
Write-Host "DataStore 固定 Key  : $fixedKeys + $dynamicKeyFamilies 类动态站点 Key"
Write-Host "路由类型/注册目的地 : $routeTypes / $navDestinations"
Write-Host "JVM @Test           : $(Get-MatchCount $unitFiles '@Test\b')（$($unitFiles.Count) 个文件）"
Write-Host "androidTest @Test   : $(Get-MatchCount $androidFiles '@Test\b')（$($androidFiles.Count) 个文件）"
Write-Host ""

# 7. 超大文件守卫（> 1200 行需拆分，见 docs/DESIGN_AND_UI_GUIDE.md 分层约定）
$big = $allKt | ForEach-Object {
    [pscustomobject]@{ Lines = [System.IO.File]::ReadAllLines($_.FullName).Count; Path = $_.FullName.Replace($root + [System.IO.Path]::DirectorySeparatorChar, '') }
} | Where-Object { $_.Lines -gt 1200 } | Sort-Object Lines -Descending
if ($big) {
    Write-Host "超过 1200 行（建议拆分）: $($big.Count) 个" -ForegroundColor Red
    $big | ForEach-Object { Write-Host ("  {0,6} {1}" -f $_.Lines, $_.Path) }
} else {
    Write-Host "没有超过 1200 行的源文件。" -ForegroundColor Green
}
Write-Host ""
Write-Host "把以上数字与 README.md / docs/*.md 中的声明逐项核对。" -ForegroundColor Yellow
