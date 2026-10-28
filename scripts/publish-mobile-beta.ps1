param(
    [Parameter(Mandatory)][string]$VersionName,
    [Parameter(Mandatory)][int]$VersionCode
)

$ErrorActionPreference = 'Stop'
$expectedPackage = 'com.adenstack.nuvioplus.mobile'
$expectedCertificate = 'c5ff71e1a714993850188080aa6b0969935d62617a93b370d3412bad6736bebf'
$remoteHost = 'ubuntu@150.230.26.198'
$remoteDirectory = '/srv/nuvio-releases'
$publicUrl = 'https://manager.adenstack.com/nuvio-mobile-beta.apk'
$sshKey = Join-Path $env:USERPROFILE '.ssh\oracle\ssh-key-2026-09-16.key'
$knownHosts = Join-Path $env:USERPROFILE '.ssh\known_hosts'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$keyDir = Join-Path $env:USERPROFILE 'NuvioSigningKeys\NuvioPlusMobile'
$keyPath = Join-Path $keyDir 'nuvioplus-mobile-release.jks'
$credentialPath = Join-Path $keyDir 'nuvioplus-mobile-release-password.xml'
$apkPath = Join-Path $repo 'androidApp\build\outputs\apk\full\release\androidApp-full-universal-release.apk'

if ($VersionName -notmatch '^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$' -or $VersionCode -lt 1) { throw 'Provide a valid version name and positive version code.' }
foreach ($path in @($keyPath,$credentialPath,$sshKey,$knownHosts)) { if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required protected file is missing: $path" } }

$credential = Import-Clixml -LiteralPath $credentialPath
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($credential.Password)
$password = $null
$oldEnvironment = @{}
try {
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
    foreach ($name in 'NUVIO_RELEASE_STORE_FILE','NUVIO_RELEASE_KEY_ALIAS','NUVIO_RELEASE_STORE_PASSWORD','NUVIO_RELEASE_KEY_PASSWORD','JAVA_HOME','GRADLE_USER_HOME','ANDROID_HOME','ANDROID_SDK_ROOT','ANDROID_USER_HOME','TEMP','TMP') { $oldEnvironment[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
    $env:NUVIO_RELEASE_STORE_FILE = $keyPath
    $env:NUVIO_RELEASE_KEY_ALIAS = 'nuvioplus-mobile'
    $env:NUVIO_RELEASE_STORE_PASSWORD = $password
    $env:NUVIO_RELEASE_KEY_PASSWORD = $password
    $jbr = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    if (-not (Test-Path (Join-Path $jbr 'bin\java.exe'))) { throw 'Android Studio JBR was not found. Install Android Studio or configure its JBR path before publishing.' }
    $env:JAVA_HOME = $jbr
    $env:GRADLE_USER_HOME = Join-Path $repo '.gradle'
    $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (-not (Test-Path (Join-Path $sdk 'platforms'))) { throw "Android SDK not found under $sdk" }
    $env:ANDROID_HOME = $sdk
    $env:ANDROID_SDK_ROOT = $sdk
    $env:ANDROID_USER_HOME = Join-Path $repo 'build\android-user-home'
    $env:TEMP = Join-Path $repo 'build\tmp'
    $env:TMP = $env:TEMP
    New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null
    New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null
    & (Join-Path $repo 'gradlew.bat') :androidApp:assembleFullRelease "-Pnuvioplus.mobile.versionName=$VersionName" "-Pnuvioplus.mobile.versionCode=$VersionCode" --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Mobile release build failed ($LASTEXITCODE)." }
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
    $password = $null
    foreach ($name in $oldEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name,$oldEnvironment[$name],'Process') }
}

if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) { throw "Universal release APK not found: $apkPath" }
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$buildTools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Sort-Object { [version]($_.Name -replace '-.*$','') } -Descending | Select-Object -First 1
if (-not $buildTools) { throw "Android SDK build-tools not found under $sdk" }
$aapt = Join-Path $buildTools.FullName 'aapt.exe'
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
$badging = & $aapt dump badging $apkPath 2>&1
if ($LASTEXITCODE -ne 0) { throw "Could not inspect the Mobile APK: $($badging -join ' ')" }
$package = @($badging | Where-Object { $_ -match '^package:' })
if ($package.Count -ne 1 -or $package[0] -notmatch "name='([^']+)' versionCode='(\d+)' versionName='([^']+)'") { throw 'Could not read APK package/version.' }
if ($Matches[1] -ne $expectedPackage -or $Matches[2] -ne "$VersionCode" -or $Matches[3] -ne $VersionName) { throw 'Built APK package or version did not match the requested Mobile release.' }
$signing = & $apksigner verify --verbose --print-certs $apkPath 2>&1
if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed: $($signing -join ' ')" }
$certLine = @($signing | Where-Object { $_ -match '^Signer #1 certificate SHA-256 digest:' })
if ($certLine.Count -ne 1 -or $certLine[0] -notmatch '([a-fA-F0-9]{64})\s*$') { throw 'Could not read APK signing certificate fingerprint.' }
$certificate = $Matches[1].ToLowerInvariant()
if ($certificate -ne $expectedCertificate) { throw "Signing identity changed: expected $expectedCertificate, got $certificate" }
$apk = Get-Item -LiteralPath $apkPath
$identity = @{ Size = [long]$apk.Length; Hash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant() }

$ssh = 'C:\Windows\System32\OpenSSH\ssh.exe'
$scp = 'C:\Windows\System32\OpenSSH\scp.exe'
if (-not (Test-Path $ssh) -or -not (Test-Path $scp)) { throw 'Windows OpenSSH ssh.exe/scp.exe is required.' }
$sshOptions = @('-i',$sshKey,'-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','StrictHostKeyChecking=yes','-o',"UserKnownHostsFile=$knownHosts")
$suffix = [guid]::NewGuid().ToString('N')
$remoteFile = "$remoteDirectory/nuvio-mobile-beta.apk"
$stageFile = "$remoteDirectory/.nuvio-mobile-beta.apk.$suffix.tmp"
$backupFile = "$remoteDirectory/.nuvio-mobile-beta.apk.$suffix.bak"
$uploadFile = "/tmp/.nuvio-mobile-beta.apk.$suffix.upload"
function Invoke-Remote([string]$Command) {
    $output = & $ssh @sshOptions $remoteHost "set -eu; $Command" 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Remote publishing command failed: $($output -join ' ')" }
    return ($output -join "`n").Trim()
}
function Get-RemoteIdentity([string]$Path) {
    $output = Invoke-Remote "sudo -n stat -c %s -- $Path; sudo -n sha256sum -- $Path"
    $lines = @($output -split "`r?`n")
    if ($lines.Count -ne 2 -or $lines[0] -notmatch '^\d+$' -or $lines[1] -notmatch '^([a-fA-F0-9]{64})\s+') { throw "Unexpected remote size/hash for $Path" }
    return @{ Size = [long]$lines[0]; Hash = $Matches[1].ToLowerInvariant() }
}
function Assert-Identity($Actual, $Expected, [string]$Description) {
    if ($Actual.Size -ne $Expected.Size -or $Actual.Hash -ne $Expected.Hash) { throw "$Description does not match the local APK." }
}
function Assert-PublicApk($Expected) {
    $publicTemp = "/tmp/.nuvio-mobile-beta.$suffix.public-check"
    $result = Invoke-Remote "trap 'rm -f -- $publicTemp' EXIT; curl -fsS --retry 2 --retry-delay 1 --max-time 180 '$publicUrl' -o $publicTemp; stat -c %s -- $publicTemp; sha256sum -- $publicTemp"
    $lines = @($result -split "`r?`n")
    if ($lines.Count -ne 2 -or $lines[0] -notmatch '^\d+$' -or $lines[1] -notmatch '^([a-fA-F0-9]{64})\s+') { throw 'Could not verify the public Mobile Beta download.' }
    $publicHash = $Matches[1].ToLowerInvariant()
    if ([long]$lines[0] -ne $Expected.Size -or $publicHash -ne $Expected.Hash) { throw 'Public Mobile Beta download did not match the signed local APK.' }
    Write-Output "Verified public APK: $publicUrl ($($Expected.Size) bytes, SHA-256 $publicHash)"
}

try {
    & $scp @sshOptions $apkPath "${remoteHost}:$uploadFile"
    if ($LASTEXITCODE -ne 0) { throw 'APK upload failed.' }
    Invoke-Remote "sudo -n install -m 0644 -- $uploadFile $stageFile && rm -f -- $uploadFile" | Out-Null
    Assert-Identity (Get-RemoteIdentity $stageFile) $identity 'Staged APK'
    Invoke-Remote "if sudo -n test -e $remoteFile; then sudo -n cp -p -- $remoteFile $backupFile; fi; sudo -n mv -fT -- $stageFile $remoteFile" | Out-Null
    Assert-Identity (Get-RemoteIdentity $remoteFile) $identity 'Published APK'
    Assert-PublicApk $identity
    Write-Output "Published Mobile Beta $VersionName ($VersionCode), package $expectedPackage, certificate SHA-256 $certificate"
    Write-Output "Recovery copy if an older Mobile Beta existed: $backupFile"
} catch {
    Write-Error "Mobile Beta publication stopped: $($_.Exception.Message). Inspect the public path before retrying. Backup path if created: $backupFile" -ErrorAction Continue
    exit 1
} finally {
    try { Invoke-Remote "sudo -n rm -f -- $stageFile; rm -f -- $uploadFile" | Out-Null } catch { }
}
