# Reads only the backend's unauthenticated discovery document. Never imports TV secrets or signing keys.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$endpoint = 'https://nuvio.adenstack.com/.well-known/nuvio'
$document = Invoke-RestMethod -Method Get -Uri $endpoint
if ($document.version -ne 1 -or $document.service -ne 'nuvio' -or $document.self_hosted -ne $true -or
    $document.backend_url.TrimEnd('/') -ne 'https://nuvio.adenstack.com' -or
    $document.capabilities.email_password_auth -ne $true -or [string]::IsNullOrWhiteSpace($document.publishable_key) -or
    $document.publishable_key -match '[\r\n]') {
    throw 'The public discovery document did not match the expected managed backend.'
}
$target = Join-Path $projectRoot 'local.properties'
$existing = if (Test-Path -LiteralPath $target) { [IO.File]::ReadAllLines($target) } else { @() }
$preserved = @($existing | Where-Object { $_ -notmatch '^\s*NUVIO_SUPABASE_(URL|ANON_KEY|FALLBACK_URL)\s*=' })
$lines = $preserved + @(
    'NUVIO_SUPABASE_URL=https://nuvio.adenstack.com',
    ('NUVIO_SUPABASE_ANON_KEY=' + $document.publishable_key),
    'NUVIO_SUPABASE_FALLBACK_URL='
)
[IO.File]::WriteAllLines($target, $lines, [Text.UTF8Encoding]::new($false))
Write-Output 'Configured the public managed backend client settings in ignored local.properties.'