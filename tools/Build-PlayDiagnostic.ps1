param(
    [Parameter(Mandatory=$true)][string]$KeystorePath,
    [Parameter(Mandatory=$true)][string]$KeyAlias,
    [string]$GradleCommand = "gradle"
)
$ErrorActionPreference = "Stop"
$resolvedKey = (Resolve-Path -LiteralPath $KeystorePath).Path
$names = @("KINETIC_KEYSTORE_PATH", "KINETIC_KEYSTORE_PASSWORD", "KINETIC_KEY_ALIAS", "KINETIC_KEY_PASSWORD")
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, "Process") }
$storePassword = Read-Host "Existing keystore password (stays local)" -AsSecureString
$keyPassword = Read-Host "Existing key password (stays local)" -AsSecureString
function LocalPlainText([Security.SecureString]$secret) {
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    $env:KINETIC_KEYSTORE_PATH = $resolvedKey
    $env:KINETIC_KEY_ALIAS = $KeyAlias
    $env:KINETIC_KEYSTORE_PASSWORD = LocalPlainText $storePassword
    $env:KINETIC_KEY_PASSWORD = LocalPlainText $keyPassword
    & $GradleCommand --no-daemon :app:testDebugUnitTest :app:bundleDiagnostic :app:assembleDiagnostic
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
    Write-Host "Signed AAB: app/build/outputs/bundle/diagnostic/app-diagnostic.aab"
    Write-Host "Signed APK: app/build/outputs/apk/diagnostic/app-diagnostic.apk"
    Write-Host "Verify signatures and match the Play upload certificate before uploading."
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], "Process") }
    $storePassword.Dispose()
    $keyPassword.Dispose()
    Pop-Location
}
