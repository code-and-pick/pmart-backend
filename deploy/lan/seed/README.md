# 시험 데이터 seed (LAN·로컬)

승인 경로 API 로만 적재한다(ADR-0014, DB 직접 입력 없음). JDK 21 만 있으면 된다.

```bash
java deploy/lan/seed/Seed.java                                  # LAN: 게이트웨이 = placement.conf 의 api-gateway PC :8080
java deploy/lan/seed/Seed.java --initial-stock                  # + 시험 초기 재고(SKU 마다 initialStockPerSku). 한 번만 — 다시 돌리면 그만큼 더 들어간다
java deploy/lan/seed/Seed.java --initial-stock --initial-stock-skus E-00000008,E-00000009   # 추가 SKU 도 함께
```

- 직원 토큰(2026-11-13, ADR-0056 — auth-service 운영자 로그인을 없앰): `deploy/lan/.secrets/keycloak-admin-password`·`keycloak-claude-dev-password`(또는 `PMART_SEED_KEYCLOAK_ADMIN_PASSWORD`·`PMART_SEED_DEV_PASSWORD`). Keycloak(pmart-dev-cli) 로그인 → employee-auth-service 시험용 교환. 로컬 프로필에는 Keycloak 이 없어 LAN 에서만 돈다(`--keycloak`·`--employee-auth` 로 주소 변경).
- 시험 직원 `seed-md`(MD, 센터 `MFC-SEED-01`)·`seed-approver`(PRODUCT·DISPLAY·PRICE·SERVICEABILITY·CENTER_MASTER·INVENTORY 승인)의 역할·센터를 맞춘다(관리자 claude-dev). 계정은 만들지 않는다(hr-service 로 등록). Keycloak 비밀번호는 실행마다 무작위로 정하고 저장하지 않는다(발의자 ≠ 승인자 규칙 때문에 두 계정).
- 순서: 센터·시간표 → 카테고리 트리 → 배송 권역 → 상품 → 콘텐츠·대표 이미지(합성 PNG) → 기본가 → MFC 취급 ON → (--initial-stock) 초기 재고. 이미 있으면 건너뛴다(상품은 바코드로 확인). 초기 재고 말고는 다시 돌려도 안전하다.
- 초기 재고는 INITIAL_STOCK 승인 → mfc-inbound 초기 보유 반입 → mfc-inventory 전기(D2, 조정으로 넣지 않음). 반영은 `GET /initial-stock-loads?approvalId=` 로 확인한다.
- 다른 카테고리 트리나 권역 매핑이 이미 발효 중이면 덮어쓰지 않는다(트리 발효본은 그대로 쓰고, 권역은 넣지 않는다). 발효 중인 권역이 모두 seed 권역(`Z-SEED-*`)이면 `seed-data.json` 의 권역으로 새 버전을 올려 교체한다.
- 센터가 이미 있어도 이름·주소·좌표가 `seed-data.json` 과 다르면 CENTER_MASTER 승인(UPDATE_PROFILE)으로 맞춘다. 시험 위치: 부산진구(업무 문서의 초기 부산 MFC 5곳 중 하나) — 2026-11-16 부산진구지점 실제 주소 "부산 부산진구 동천로 109 삼한골든게이트 2층"(35.158501, 129.062021)로 바꿈(그 전 서면 부근 시험 좌표). 권역 `inside` 시험 점(서면역 부근)은 고객 쪽 시험 좌표라 그대로.
- 데이터: `seed-data.json`. 모두 합성(상품명 `[시험] …`). `local-reference/` 캡처 자료는 쓰지 않는다.
