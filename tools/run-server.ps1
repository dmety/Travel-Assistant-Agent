# Travel-Assistant-Agent server launcher for Windows (PowerShell).
# Reads the gitignored .env, exports values as process environment variables,
# maps AMAP_API_KEY -> AMAP_KEY, then starts the Spring Boot jar.
# Usage: run-server.bat            (double-click, default port 8080)
#        run-server.bat 18080      (explicit port)
# NOTE: keep this file ASCII-only; non-ASCII text breaks under GBK consoles.

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$envFile = Join-Path $root '.env'
if (Test-Path -LiteralPath $envFile) {
    Get-Content -LiteralPath $envFile -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith('#')) {
            $idx = $line.IndexOf('=')
            if ($idx -gt 0) {
                $name = $line.Substring(0, $idx).Trim()
                $value = $line.Substring($idx + 1).Trim()
                if ($value.StartsWith('"') -and $value.EndsWith('"') -and $value.Length -ge 2) {
                    $value = $value.Substring(1, $value.Length - 2)
                }
                [Environment]::SetEnvironmentVariable($name, $value, 'Process')
            }
        }
    }
} else {
    Write-Host '[warn] .env not found; starting without map/model keys.'
}

if (-not $env:AMAP_KEY -and $env:AMAP_API_KEY) {
    $env:AMAP_KEY = $env:AMAP_API_KEY
}

$port = '8080'
if ($args.Count -ge 1 -and $args[0]) { $port = $args[0] }
$env:SERVER_PORT = $port
if (-not $env:TRAVEL_DATA_DIR) { $env:TRAVEL_DATA_DIR = './data' }

$jar = Join-Path $root 'travel-server/target/travel-server-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) {
    Write-Host '[error] jar not found. Build first: mvn -pl travel-server -am package -DskipTests'
    exit 1
}

$amap = if ($env:AMAP_KEY) { 'yes' } else { 'no' }
$baidu = if ($env:BAIDU_AK) { 'yes' } else { 'no' }
$deepseek = if ($env:DEEPSEEK_API_KEY) { 'yes' } else { 'no' }
Write-Host ("starting on port {0} (amap={1} baidu={2} deepseek={3})" -f $port, $amap, $baidu, $deepseek)
Write-Host ("open http://localhost:{0}/ in your browser" -f $port)

java -jar $jar
