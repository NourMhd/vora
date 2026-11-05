[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$PackageRoot,
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-f]{40}$')][string]$Commit,
    [switch]$Execute
)
$ErrorActionPreference = 'Stop'
$backend = 'C:\vora\backend'
$frontend = 'C:\vora\frontend'
$dump = 'C:\mongodb-tools\bin\mongodump.exe'
$serviceName = 'backendvora'

function Copy-Web([string]$Source, [string]$Destination) {
    & robocopy.exe $Source $Destination /MIR /R:1 /W:1 /NFL /NDL /NJH /NJS
    if ($LASTEXITCODE -ge 8) { throw "Robocopy failed: $LASTEXITCODE" }
}
function Wait-State([string]$Desired) {
    $limit = (Get-Date).AddSeconds(90)
    do {
        if ((Get-Service $serviceName).Status.ToString() -eq $Desired) { return }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $limit)
    throw "Service did not reach $Desired"
}
function Stop-Backend {
    $state = (Get-Service $serviceName).Status.ToString()
    if ($state -ne 'Stopped' -and $state -ne 'StopPending') {
        & sc.exe stop $serviceName
        if ($LASTEXITCODE -ne 0) { throw 'Cannot stop backendvora' }
    }
    Wait-State 'Stopped'
    # NSSM must also have terminated Java before files can be replaced.
    $limit = (Get-Date).AddSeconds(30)
    do {
        $client = New-Object System.Net.Sockets.TcpClient
        try { $client.Connect('127.0.0.1', 9090); $open = $true }
        catch { $open = $false }
        finally { $client.Dispose() }
        if (-not $open) { return }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $limit)
    throw 'Port 9090 is still in use; no new version will be installed'
}
function Start-Backend {
    $state = (Get-Service $serviceName).Status.ToString()
    if ($state -eq 'StopPending') { Wait-State 'Stopped'; $state = 'Stopped' }
    if ($state -ne 'Running' -and $state -ne 'StartPending') {
        & sc.exe start $serviceName
        if ($LASTEXITCODE -ne 0) { throw 'Cannot start backendvora' }
    }
    Wait-State 'Running'
}
function Check-Http {
    $limit = (Get-Date).AddSeconds(180)
    do {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:9090/test' -TimeoutSec 5
            if ($response.StatusCode -eq 200 -and $response.Content.Trim() -eq 'backend ok') { break }
        } catch { }
        if ((Get-Date) -ge $limit) { throw 'Backend HTTP check failed' }
        Start-Sleep -Seconds 3
    } while ($true)
    $web = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:9091/' -TimeoutSec 15
    if ($web.StatusCode -ne 200 -or $web.Content -notmatch '<html') { throw 'Frontend HTTP check failed' }
}

$PackageRoot = (Resolve-Path -LiteralPath $PackageRoot).Path
$jars = @(Get-ChildItem -LiteralPath (Join-Path $PackageRoot 'backend') -Filter '*.jar' -File)
if ($jars.Count -ne 1) { throw 'Expected exactly one executable JAR' }
$newJar = $jars[0].FullName
$newWeb = Join-Path $PackageRoot 'frontend'
foreach ($file in @(
    (Join-Path $newWeb 'index.html'), (Join-Path $newWeb 'main.dart.js'),
    "$backend\app.jar", "$backend\start-backend.bat", "$backend\application-private.yml", $dump
)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing file: $file" }
}
if ((Get-Service $serviceName).Status -ne 'Running') { throw 'Backend must be running before deployment' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jarArchive = [System.IO.Compression.ZipFile]::OpenRead($newJar)
try {
    if (-not $jarArchive.GetEntry('META-INF/MANIFEST.MF') -or
        -not ($jarArchive.Entries | Where-Object { $_.FullName.StartsWith('BOOT-INF/classes/') } | Select-Object -First 1)) {
        throw 'Invalid Spring Boot JAR'
    }
} finally { $jarArchive.Dispose() }
foreach ($folder in @($backend, $frontend, 'C:\vora\backups')) {
    $probe = Join-Path $folder ('deploy-check-' + [guid]::NewGuid() + '.tmp')
    try { Set-Content -LiteralPath $probe -Value 'check' }
    finally { if (Test-Path -LiteralPath $probe) { Remove-Item -LiteralPath $probe -Force } }
}
Check-Http
Write-Host "Validated delivery: $Commit"
Write-Host "JAR SHA256: $((Get-FileHash -LiteralPath $newJar -Algorithm SHA256).Hash)"
if (-not $Execute) {
    Write-Host 'PREPARATION OK: no application files replaced and no service restart.'
    exit 0
}

$backup = Join-Path 'C:\vora\backups' ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $Commit.Substring(0,8) + '-' + [guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Path $backup | Out-Null
$stopping = $false
$touched = $false
$oldHash = (Get-FileHash -LiteralPath "$backend\app.jar" -Algorithm SHA256).Hash
try {
    Copy-Item -LiteralPath "$backend\app.jar" -Destination "$backup\app.jar"
    Copy-Item -LiteralPath "$backend\start-backend.bat" -Destination "$backup\start-backend.bat"
    Copy-Web $frontend "$backup\frontend"
    if ((Get-FileHash "$backup\app.jar" -Algorithm SHA256).Hash -ne $oldHash) { throw 'JAR backup checksum failed' }
    $stopping = $true
    Stop-Backend
    & $dump '--uri=mongodb://localhost:27017/monitoring' "--archive=$backup\monitoring.archive.gz" '--gzip'
    if ($LASTEXITCODE -ne 0) { throw 'MongoDB backup failed' }
    if ((Get-Item "$backup\monitoring.archive.gz").Length -eq 0) { throw 'Empty MongoDB backup' }
    $touched = $true
    Copy-Item -LiteralPath $newJar -Destination "$backend\app.jar" -Force
    Copy-Web $newWeb $frontend
    if ((Get-FileHash "$backend\app.jar" -Algorithm SHA256).Hash -ne (Get-FileHash $newJar -Algorithm SHA256).Hash) { throw 'Installed JAR checksum failed' }
    Start-Backend
    Check-Http
    # Independent check of local MongoDB availability, not app authentication/readiness.
    & $dump '--uri=mongodb://localhost:27017/monitoring' '--collection=field_tracking_meta' "--archive=$backup\mongo-poststart-check.archive.gz" '--gzip'
    if ($LASTEXITCODE -ne 0) { throw 'Post-start MongoDB check failed' }
    @{ commit=$Commit; installedAt=(Get-Date).ToString('o'); backup=$backup } |
        ConvertTo-Json | Set-Content -LiteralPath "$backend\deployment.json" -Encoding UTF8
    Write-Host "DEPLOYMENT OK. Backup: $backup"
} catch {
    $failure = $_
    Write-Warning "Deployment failed: $($failure.Exception.Message)"
    if ($stopping) {
        try {
            if ($touched) {
                Stop-Backend
                Copy-Item -LiteralPath "$backup\app.jar" -Destination "$backend\app.jar" -Force
                Copy-Web "$backup\frontend" $frontend
                if ((Get-FileHash "$backend\app.jar" -Algorithm SHA256).Hash -ne $oldHash) { throw 'Restored JAR checksum failed' }
            }
            Start-Backend
            Check-Http
            Write-Host 'Previous application restored/restarted. MongoDB was not restored.'
        } catch {
            Write-Warning "AUTOMATIC RECOVERY FAILED: $($_.Exception.Message). Backup: $backup"
        }
    }
    throw $failure
}
