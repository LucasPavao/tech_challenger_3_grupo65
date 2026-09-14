#!/usr/bin/env bash
set -eu

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AUTH_DIR="$ROOT_DIR/auth-service/src/main/resources"
AUTH_PRIVATE_KEY="$AUTH_DIR/app.key"
AUTH_PUBLIC_KEY="$AUTH_DIR/app.sub"
APPOINTMENT_PUBLIC_KEY="$ROOT_DIR/appointment-service/src/main/resources/app.sub"
HISTORY_PUBLIC_KEY="$ROOT_DIR/history-service/src/main/resources/app.sub"

if ! command -v openssl >/dev/null 2>&1; then
  echo "Erro: o comando openssl não foi encontrado." >&2
  echo "Instale o OpenSSL e execute este script novamente." >&2
  exit 1
fi

if [ -f "$AUTH_PRIVATE_KEY" ]; then
  if [ ! -f "$AUTH_PUBLIC_KEY" ]; then
    echo "Chave privada encontrada; derivando a chave pública..."
    openssl pkey -pubout -in "$AUTH_PRIVATE_KEY" -out "$AUTH_PUBLIC_KEY"
  fi
elif [ -f "$AUTH_PUBLIC_KEY" ]; then
  echo "Erro: existe app.sub, mas app.key não existe. Gere ou restaure o par completo." >&2
  exit 1
else
  echo "Gerando par RSA de 2048 bits..."
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$AUTH_PRIVATE_KEY"
  openssl pkey -pubout -in "$AUTH_PRIVATE_KEY" -out "$AUTH_PUBLIC_KEY"
fi

cp "$AUTH_PUBLIC_KEY" "$APPOINTMENT_PUBLIC_KEY"
cp "$AUTH_PUBLIC_KEY" "$HISTORY_PUBLIC_KEY"

echo "Chaves JWT prontas."
echo "  Privada: auth-service/src/main/resources/app.key"
echo "  Pública: auth-service/src/main/resources/app.sub"
echo "  Cópias públicas atualizadas nos serviços consumidores."
echo "A chave privada não deve ser commitada."
