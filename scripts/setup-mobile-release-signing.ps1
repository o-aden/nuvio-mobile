$ErrorActionPreference = 'Stop'
$keyDir = Join-Path $env:USERPROFILE 'NuvioSigningKeys\NuvioPlusMobile'
$keyPath = Join-Path $keyDir 'nuvioplus-mobile-release.jks'
$credentialPath = Join-Path $keyDir 'nuvioplus-mobile-release-password.xml'
if ((Test-Path -LiteralPath $keyPath) -or (Test-Path -LiteralPath $credentialPath)) {
    throw "Mobile signing material already exists at $keyDir; refusing to replace it."
}

New-Item -ItemType Directory -Path $keyDir -Force | Out-Null
$account = [Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $keyDir /inheritance:r /grant:r "$($account):(OI)(CI)F" 'SYSTEM:(OI)(CI)F' 'BUILTIN\Administrators:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not restrict Mobile signing directory permissions.' }

$random = [byte[]]::new(32)
[Security.Cryptography.RandomNumberGenerator]::Fill($random)
$password = [Convert]::ToHexString($random)
$securePassword = ConvertTo-SecureString $password -AsPlainText -Force
$credential = [pscredential]::new('nuvioplus-mobile', $securePassword)
$credential | Export-Clixml -LiteralPath $credentialPath

$keytool = (Get-Command keytool.exe -ErrorAction Stop).Source
$env:NUVIO_MOBILE_KEYSTORE_PASSWORD = $password
$env:NUVIO_MOBILE_KEY_PASSWORD = $password
try {
    & $keytool -genkeypair -noprompt -alias nuvioplus-mobile -keyalg RSA -keysize 4096 -validity 10000 -storetype JKS -keystore $keyPath '-storepass:env' NUVIO_MOBILE_KEYSTORE_PASSWORD '-keypass:env' NUVIO_MOBILE_KEY_PASSWORD -dname 'CN=NuvioPlus Mobile Release, OU=Mobile, O=AdenStack, C=CA'
    if ($LASTEXITCODE -ne 0) { throw 'Mobile release keystore creation failed.' }
} catch {
    Remove-Item -LiteralPath $keyPath,$credentialPath -Force -ErrorAction SilentlyContinue
    throw
} finally {
    Remove-Item Env:NUVIO_MOBILE_KEYSTORE_PASSWORD,Env:NUVIO_MOBILE_KEY_PASSWORD -ErrorAction SilentlyContinue
    $password = $null
    $random = $null
}

$inspectPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $env:NUVIO_MOBILE_KEYSTORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($inspectPointer)
    $details = & $keytool -list -v -alias nuvioplus-mobile -keystore $keyPath '-storepass:env' NUVIO_MOBILE_KEYSTORE_PASSWORD 2>&1
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($inspectPointer)
    Remove-Item Env:NUVIO_MOBILE_KEYSTORE_PASSWORD -ErrorAction SilentlyContinue
}
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the newly created signing certificate.' }
$fingerprintLine = @($details | Where-Object { $_ -match '^\s*SHA256:\s*([A-Fa-f0-9:]+)\s*$' }) | Select-Object -First 1
if (-not $fingerprintLine -or $fingerprintLine -notmatch '([A-Fa-f0-9:]{64,95})\s*$') { throw 'Could not read the signing certificate fingerprint.' }
$fingerprint = ($Matches[1] -replace ':','').ToLowerInvariant()
Write-Output "Created dedicated Mobile release signing identity at $keyDir"
Write-Output "Certificate SHA-256: $fingerprint"
Write-Output 'Back up the JKS and its recovery credential securely; the credential file is protected for this Windows user.'
