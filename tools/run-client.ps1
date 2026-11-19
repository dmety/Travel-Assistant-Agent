# Launch the interactive CLI with UTF-8 console and JVM encodings.
# Usage: .\tools\run-client.ps1 [base URL]

param(
    [Parameter(Position = 0)]
    [string]$BaseUrl = 'http://localhost:8080'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$jar = Join-Path $root 'travel-client/target/travel-client-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) {
    Write-Host '[error] CLI jar not found. Build first: mvn -pl travel-client -am package -DskipTests'
    exit 1
}

$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$null = chcp 65001

# This script is explicitly interactive. Windows Terminal/ConPTY supports VT;
# JLine otherwise treats an unset TERM as `dumb` and disables the full-screen UI.
if (-not $env:TERM -or $env:TERM -eq 'dumb') {
    $env:TERM = 'xterm-256color'
}

$javaArgs = @(
    '-Dfile.encoding=UTF-8',
    '-Dstdout.encoding=UTF-8',
    '-Dstderr.encoding=UTF-8',
    '-Dtravel.cli.forceInteractive=true',
    '-jar', $jar, $BaseUrl
)
& java @javaArgs
exit $LASTEXITCODE
