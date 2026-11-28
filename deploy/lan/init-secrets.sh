#!/usr/bin/env bash
# LAN 공유 개발 환경용 비밀정보 생성 → deploy/lan/.secrets/ (git 제외). 이미 있으면 그대로 둔다(덮어쓰지 않음).
# - db-password: 각 PC postgres 의 pmart 사용자 비밀번호
# - signing-key.pem / signing-kid: auth-service RS256 서명키(PKCS#8). 재생성하면 발급된 토큰이 모두 무효
# - rider-auth-signing-key.pem / rider-auth-signing-kid: rider-auth-service(라이더 토큰 발급자, ADR-0054 E) RS256 서명키 — auth-service 와 다른 키
# - employee-auth-signing-key.pem / employee-auth-signing-kid: employee-auth-service(직원 토큰 발급자, ADR-0056) RS256 서명키 — 다른 발급자와 다른 키
# - keycloak-hr-provisioner-client-secret: Keycloak 서비스 계정 클라이언트 hr-provisioner(hr-service 의 Admin API — manage/view/query-users 만, ADR-0056 결정 16). pmart-config 에는 {cipher}. 교체: 새 값 → 설정·Keycloak 반영·재시작 → 옛 값 폐기
# - keycloak-employee-auth-client-secret: Keycloak 로그인 클라이언트 employee-auth-service(기밀, ADR-0056). pmart-config 에는 {cipher}, Keycloak 에는 kcadm 으로
# - s3-access-key / s3-secret-key: s3(SeaweedFS) 자격 증명(ADR-0016)
# - (2026-11-13 폐기) operator-admin-login / operator-admin-password / operator-admin-hash: auth-service 첫 관리자 — 운영자 로그인을 없애 더 만들지 않는다.
#   비밀번호 원문은 이 파일에만 있다(사용자가 읽어 로그인). 서비스에는 해시만 넘긴다. 해시는 BcryptHash.java(spring-security-crypto) 로 만든다.
# - config-encrypt-key / config-encrypt-salt: config-server {cipher} 암호화 키(ADR-0027). 바꾸면 render 가 저장소 값을 모두 다시 암호화한다.
#   salt 는 16진수여야 한다(Encryptors.text).
# - config-password: 서비스 → config-server Basic 인증 비밀번호(사용자 pmart)
# - config-deploy-key(.pub): config-server 가 GitHub 설정 저장소를 읽는 SSH 키(ed25519). 공개키를 저장소 Deploy key(읽기 전용)로 등록한다
# - config-webhook-secret: GitHub 웹훅 서명 비밀(ADR-0033). pmart-config 저장소 웹훅 설정의 Secret 에 같은 값을 넣는다
# - smee-url: 웹훅 중계 채널 주소(https://smee.io/<채널>, ADR-0033). 저장소 웹훅의 Payload URL. 주소를 아는 사람은 웹훅 내용(파일 이름·커밋 메시지)을
#   볼 수 있어 비밀로 둔다(서명 비밀은 웹훅에 실리지 않는다). 새 채널은 smee.io 에 HEAD 요청 한 번으로 만든다
# - eureka-password: 서비스 → eureka-server Basic 인증 비밀번호(사용자 pmart, ADR-0032). 대시보드(http://<pc4>:8761) 로그인도 이것
# - grafana-admin-password: Grafana 관리자(admin) 비밀번호(ADR-0030). 원문은 이 파일에만 있다
# - keycloak-admin-password / keycloak-db-password: Keycloak(ADR-0054, 직원 로그인) 첫 관리자(admin)·Keycloak DB 계정(keycloak). 원문은 이 파일에만
# - (2026-11-13 폐기) keycloak-relay-client-secret: Keycloak 중계 클라이언트 pmart-auth-relay 를 지워 더 만들지 않는다. 남은 파일은 지워도 된다
# - rag-db-operator-password / rag-db-customer-password / rag-db-embedding-password: RAG 전용 pgvector 의 서비스 계정 ai_assistant_service·customer_assistant_service(ADR-0051)·embedding_service(ADR-0098)
# - mail-noreply-password / mail-postmaster-password: 사내 메일 서버(ADR-0056) 기본 사서함. 메일 서버에는 SHA512-CRYPT 해시만 넘긴다
# - tls/pmart-lan-root-ca.key: LAN 개발용 HTTPS 사설 루트 CA 개인키(10년). 공개 인증서는 deploy/lan/tls/pmart-lan-root-ca.crt(커밋).
#   PC 서버 인증서(tls/<pc>.crt·key)는 lan.sh tls-issue 가 만든다
# 이 값들은 LAN 개발 환경 전용이다. 운영은 Secret Manager.
set -euo pipefail
cd "$(dirname "$0")"
umask 077
mkdir -p .secrets
[ -f .secrets/db-password ] || { openssl rand -hex 24 > .secrets/db-password; echo "created db-password"; }
if [ ! -f .secrets/signing-key.pem ]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out .secrets/signing-key.pem 2>/dev/null
  echo "lan-$(date +%Y%m%d)" > .secrets/signing-kid
  echo "created signing-key.pem (kid $(cat .secrets/signing-kid))"
fi
if [ ! -f .secrets/rider-auth-signing-key.pem ]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out .secrets/rider-auth-signing-key.pem 2>/dev/null
  echo "rider-lan-$(date +%Y%m%d)" > .secrets/rider-auth-signing-kid
  echo "created rider-auth-signing-key.pem (kid $(cat .secrets/rider-auth-signing-kid))"
fi
if [ ! -f .secrets/employee-auth-signing-key.pem ]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out .secrets/employee-auth-signing-key.pem 2>/dev/null
  echo "employee-lan-$(date +%Y%m%d)" > .secrets/employee-auth-signing-kid
  echo "created employee-auth-signing-key.pem (kid $(cat .secrets/employee-auth-signing-kid))"
fi
[ -f .secrets/keycloak-employee-auth-client-secret ] || { openssl rand -hex 32 > .secrets/keycloak-employee-auth-client-secret; echo "created keycloak-employee-auth-client-secret"; }
[ -f .secrets/keycloak-hr-provisioner-client-secret ] || { openssl rand -hex 32 > .secrets/keycloak-hr-provisioner-client-secret; echo "created keycloak-hr-provisioner-client-secret"; }
[ -f .secrets/s3-access-key ] || { echo "pmart-lan-$(openssl rand -hex 4)" > .secrets/s3-access-key; echo "created s3-access-key"; }
[ -f .secrets/s3-secret-key ] || { openssl rand -hex 24 > .secrets/s3-secret-key; echo "created s3-secret-key"; }
[ -f .secrets/config-encrypt-key ] || { openssl rand -hex 32 > .secrets/config-encrypt-key; echo "created config-encrypt-key"; }
[ -f .secrets/config-encrypt-salt ] || { openssl rand -hex 16 > .secrets/config-encrypt-salt; echo "created config-encrypt-salt"; }
[ -f .secrets/config-password ] || { openssl rand -hex 24 > .secrets/config-password; echo "created config-password"; }
if [ ! -f .secrets/config-deploy-key ]; then
  ssh-keygen -q -t ed25519 -N "" -C "pmart-config-server-lan" -f .secrets/config-deploy-key
  echo "created config-deploy-key — 공개키(.secrets/config-deploy-key.pub)를 GitHub pmart-config 저장소 Deploy key(읽기 전용)로 등록"
fi
[ -f .secrets/config-webhook-secret ] || { openssl rand -hex 32 > .secrets/config-webhook-secret; echo "created config-webhook-secret — GitHub pmart-config 웹훅 Secret 에 이 값"; }
if [ ! -f .secrets/smee-url ]; then
  url="$(curl -sfI -m 15 https://smee.io/new | tr -d '\r' | awk 'tolower($1)=="location:"{print $2}')"
  [[ "$url" =~ ^https://smee\.io/[A-Za-z0-9_-]+$ ]] || { echo "smee.io 채널 생성 실패(응답: ${url:-없음})" >&2; exit 1; }
  echo "$url" > .secrets/smee-url
  echo "created smee-url — GitHub pmart-config 웹훅 Payload URL 에 이 주소(README '설정 실행 중 갱신')"
fi
[ -f .secrets/eureka-password ] || { openssl rand -hex 24 > .secrets/eureka-password; echo "created eureka-password"; }
[ -f .secrets/grafana-admin-password ] || { openssl rand -base64 18 | tr -d '\r\n/+=' > .secrets/grafana-admin-password; echo "created grafana-admin-password"; }
[ -f .secrets/rag-db-operator-password ] || { openssl rand -hex 24 > .secrets/rag-db-operator-password; echo "created rag-db-operator-password"; }
[ -f .secrets/rag-db-customer-password ] || { openssl rand -hex 24 > .secrets/rag-db-customer-password; echo "created rag-db-customer-password"; }
[ -f .secrets/rag-db-embedding-password ] || { openssl rand -hex 24 > .secrets/rag-db-embedding-password; echo "created rag-db-embedding-password"; } # embedding-service(ADR-0098) — 설정 저장소 embedding-service-lan.yml 의 {cipher} 와 같은 값
[ -f .secrets/keycloak-admin-password ] || { openssl rand -base64 24 | tr -d '\r\n/+=' > .secrets/keycloak-admin-password; echo "created keycloak-admin-password"; }
[ -f .secrets/keycloak-db-password ] || { openssl rand -hex 24 > .secrets/keycloak-db-password; echo "created keycloak-db-password"; }
# 사내 메일(ADR-0056 결정 9) 기본 사서함 비밀번호: no-reply@pmart.com(Keycloak 발신 주소 — 로그인하지 않음), postmaster@pmart.com(관리 메일 받는 곳, Roundcube 로 읽음)
[ -f .secrets/mail-noreply-password ] || { openssl rand -base64 24 | tr -d '\r\n/+=' > .secrets/mail-noreply-password; echo "created mail-noreply-password"; }
[ -f .secrets/mail-postmaster-password ] || { openssl rand -base64 18 | tr -d '\r\n/+=' > .secrets/mail-postmaster-password; echo "created mail-postmaster-password"; }
# LAN 개발용 HTTPS 루트 CA(docs/architecture/lan-https-private-ca.md) — 개인키 .secrets/tls, 공개 인증서 tls/(커밋). 없을 때만
( cd ../.. && source deploy/lan/tls/tls.sh && tls_ensure_ca )
echo "secrets: $(pwd)/.secrets"
