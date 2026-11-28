---
paths:
  - "**/security/**"
  - "**/config/**"
  - "**/application*.yml"
  - "**/application*.yaml"
  - "libraries/common-security/**"
  - "platform-services/api-gateway/**"
---

# 보안·설정 규칙

## 인증·인가
- Spring Security + OAuth2/OIDC.
- Gateway뿐 아니라 각 서비스에서도 사용자 토큰·권한을 검증한다(공개 API).
- 사용자 역할과 센터 접근 권한을 구분한다.
- 서비스 간 호출(`/internal/**`)은 인증하지 않는다(ADR-0026 — private subnet·게이트웨이 차단이 신뢰 경계). 호출자는 `X-Source-System`(자기 신고, 식별용). 사용자 토큰으로 내부 경로를 부르면 403. 내부 경로를 게이트웨이로 노출하지 않는다.
- 관리 API와 Actuator 노출 범위를 제한한다.

## 비밀정보·로그
- 비밀정보를 Git, `application.yml`, 컨테이너 이미지에 저장하지 않는다 (Vault 또는 Cloud Secret Manager).
  - LAN(ADR-0100): 서비스 비밀은 Vault(pc2 `https://192.168.0.34:8200`)의 `config/<앱>,lan` — 서비스가 Spring Cloud Vault(서비스별 AppRole)로 직접 읽는다. 기본 꺼짐, LAN 컨테이너만 환경변수로 켠다(application.yml 에 `vault://` 를 넣지 않는다). 비밀을 바꾸면 Vault 를 고치고 그 앱을 재시작한다. 토큰·unseal 키·secret-id 를 Git·문서·로그에 넣지 않는다.
  - 전환 중 예외(ADR-0027 → ADR-0100 결정 9): {cipher} 삭제가 끝날 때까지 `pmart-config` 에 `{cipher}` 암호문이 남는다. 새 비밀을 {cipher} 로 더하지 말고 Vault 에 넣는다. 암호화 키·salt 는 Git·이미지·application.yml 밖(`deploy/lan/.secrets/`, config-server 컨테이너, Vault `pmart/ops/config-server`). 키가 새면 키와 비밀값을 함께 바꾼다.
- 개인정보, 인증 토큰, PG 자격증명을 로그에 남기지 않는다.

## 설정·서비스 검색
- Eureka 환경: Spring Cloud Config + Eureka
- Kubernetes 환경: Service DNS + ConfigMap + Secret Manager
- 지금 방식(ADR-0029): Spring Cloud Config Server — 정본은 설정 저장소(pmart-config), 서비스 yml 은 부트스트랩만, 환경은 프로필. 시험만 config-server 를 끈다
- LAN 실행 중 갱신(ADR-0033): 웹훅 `/monitor` 는 GitHub 서명(`X-Hub-Signature-256`)으로만 지킨다 — 서명 검증을 끄지 않는다. Bus 는 기본 꺼짐, lan 프로필만 켠다
- 두 체계를 이유 없이 중복 도입하지 않는다. 현재 환경의 방식을 먼저 확인한다.
