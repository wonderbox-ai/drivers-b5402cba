param(
    [string]$Alias = "wonderapps-upload"
)

$ErrorActionPreference = "Stop"

Write-Host "Wonder Apps - préparation de la clé de signature" -ForegroundColor Cyan
Write-Host "Cette clé est l’identité de publication de l’application. Conserve-la hors du dépôt GitHub." -ForegroundColor Yellow

$storePassword = Read-Host "Mot de passe du keystore" -AsSecureString
$keyPassword = Read-Host "Mot de passe de la clé" -AsSecureString

$storePlain = [System.Net.NetworkCredential]::new("", $storePassword).Password
$keyPlain = [System.Net.NetworkCredential]::new("", $keyPassword).Password

if ($storePlain.Length -lt 12 -or $keyPlain.Length -lt 12) {
    throw "Utilise des mots de passe d’au moins 12 caractères."
}

$outDir = Join-Path $PSScriptRoot "..\release-signing"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$keystore = Join-Path $outDir "wonderapps-upload.jks"
$base64File = Join-Path $outDir "ANDROID_KEYSTORE_BASE64.txt"

if (Test-Path $keystore) {
    throw "Le keystore existe déjà : $keystore. Ne le remplace pas si Wonder Apps a déjà été publiée."
}

$args = @(
    "-genkeypair", "-v",
    "-keystore", $keystore,
    "-storepass", $storePlain,
    "-keypass", $keyPlain,
    "-alias", $Alias,
    "-keyalg", "RSA",
    "-keysize", "4096",
    "-validity", "10000",
    "-dname", "CN=Wonder Apps, OU=IT, O=Wonderbox, C=FR"
)
& keytool @args
if ($LASTEXITCODE -ne 0) { throw "Échec de keytool." }

[Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore)) | Set-Content -NoNewline -Encoding ascii $base64File

Write-Host ""
Write-Host "Clé créée : $keystore" -ForegroundColor Green
Write-Host "Base64 créé : $base64File" -ForegroundColor Green
Write-Host ""
Write-Host "Secrets GitHub à créer :" -ForegroundColor Cyan
Write-Host "ANDROID_KEYSTORE_BASE64 = contenu de ANDROID_KEYSTORE_BASE64.txt"
Write-Host "ANDROID_KEYSTORE_PASSWORD = mot de passe du keystore"
Write-Host "ANDROID_KEY_ALIAS = $Alias"
Write-Host "ANDROID_KEY_PASSWORD = mot de passe de la clé"
Write-Host ""
Write-Host "IMPORTANT : sauvegarde wonderapps-upload.jks dans au moins deux emplacements sécurisés." -ForegroundColor Yellow

Remove-Variable storePlain, keyPlain
