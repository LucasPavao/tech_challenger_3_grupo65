$ErrorActionPreference = "Stop"

$rootDir = Split-Path -Parent $PSScriptRoot
$authDir = Join-Path $rootDir "auth-service\src\main\resources"
$authPrivateKey = Join-Path $authDir "app.key"
$authPublicKey = Join-Path $authDir "app.sub"
$appointmentPublicKey = Join-Path $rootDir "appointment-service\src\main\resources\app.sub"
$historyPublicKey = Join-Path $rootDir "history-service\src\main\resources\app.sub"

if (-not (Get-Command openssl -ErrorAction SilentlyContinue)) {
    throw "O comando openssl não foi encontrado. Instale o OpenSSL e execute este script novamente."
}

if (Test-Path $authPrivateKey) {
    if (-not (Test-Path $authPublicKey)) {
        Write-Host "Chave privada encontrada; derivando a chave pública..."
        & openssl pkey -pubout -in $authPrivateKey -out $authPublicKey
        if ($LASTEXITCODE -ne 0) { throw "Não foi possível derivar a chave pública." }
    }
} elseif (Test-Path $authPublicKey) {
    throw "Existe app.sub, mas app.key não existe. Gere ou restaure o par completo."
} else {
    Write-Host "Gerando par RSA de 2048 bits..."
    & openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out $authPrivateKey
    if ($LASTEXITCODE -ne 0) { throw "Não foi possível gerar a chave privada." }
    & openssl pkey -pubout -in $authPrivateKey -out $authPublicKey
    if ($LASTEXITCODE -ne 0) { throw "Não foi possível gerar a chave pública." }
}

Copy-Item -LiteralPath $authPublicKey -Destination $appointmentPublicKey -Force
Copy-Item -LiteralPath $authPublicKey -Destination $historyPublicKey -Force

Write-Host "Chaves JWT prontas."
Write-Host "  Privada: auth-service/src/main/resources/app.key"
Write-Host "  Pública: auth-service/src/main/resources/app.sub"
Write-Host "  Cópias públicas atualizadas nos serviços consumidores."
Write-Host "A chave privada não deve ser commitada."
