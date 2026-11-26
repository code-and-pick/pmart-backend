# PC 1 할 일 (platform — 작업 세션)

PC 1(작업 세션) 할 일을 한곳에 모은다. 다른 PC 할 일은 `other-pcs-todo.md`, 일부러 미룬 것은 `docs/business/flows/pending-user-decisions.md` "나중에 하기로 한 것", 한 일·배포 기록은 `docs/worklog/platform/YYYY-MM-DD.md`.

- 고칠 때: 끝나면 줄 앞을 `- [x]` 로 바꾸고 날짜·커밋을 붙인다. 다 끝난 절은 맨 아래 "끝난 것"으로 옮긴다.
- PR 검토·병합·PR 배포는 PR 세션 몫(이 문서에 넣지 않는다).
- 마지막 갱신: 2026-11-24.

## 00. 2026-11-24 재부팅 뒤 이어서 할 것
- [x] **LAN 다시 올리기** — 2026-11-24 끝(19:2x 확인: 다섯 PC 컨테이너 모두 running — 멈춘 것은 init 3개뿐·정상, 감시 작업 Ready·마지막 결과 0·5분 주기, 게이트웨이 health UP, 웹 443·8444~8446 200). 옛 줄: 2026-11-24 전체 `down`(볼륨 남김) 뒤 재부팅. `deploy/lan/lan.sh deploy all`(config-server 먼저) → `lan.sh status all` → **`Enable-ScheduledTask -TaskName 'PMart LAN Watch'`**(내릴 때 사용 안 함으로 바꿈). 올린 뒤 rag-warm pc2, 시뮬레이터들에 알림. 시작은 사용자 지시 뒤.
- [x] **1호점 부산진구 다시 설계(R-45)** — 2026-11-24 PC 1 반영 끝(move 171·close 6,343·positions 9,185, 회신 `handoff/simulator/mfc/2026-11-24-reply-r45-busanjin-applied.md`, 시뮬레이터 대조 어긋남 0). 옛 줄: — 요청서 `handoff/simulator/mfc/2026-11-24-request-busanjin-redesign.md`(피킹 222랙 8,756·보충 29랙 435·대형 10, 랙당 칸 지금 그대로). 순서: 사용자 시뮬레이터 "확정"(로컬 6e35a62) → 시뮬레이터 push → PC 3 새 PR(브랜치 `mfc/r45-reshape`, 사본·계획 숫자) → PC 1 병합·`mfc_layout.py r45plan` 숫자 사용자 확인 → 1호점 가상 작업자·실주문 멈춘 뒤 `r45` 실행(없어지는 칸 약 6,268 — 재고는 같은 온도대·구역 빈 칸으로 정정 뒤 닫기, 예약·보류 줄 남김) → 시뮬레이터 대조.
- [ ] **세종·춘천·부산남구 라이더** — 라이더 시뮬레이션이 다섯 지점을 넣었지만 RD-sim-01/02 를 한 센터씩 돌려 씀, 새 센터 라이더 0. 센터마다 출근(ride 시작) 필요.
- [ ] **세종·춘천·부산남구 끝까지 주문 시험** — 고객앱 주문 → MFC 처리 → 라이더 배달 → 백오피스 표시. 세종 시험 고객 = 사용자 본인 CU-eae131fa(쇼핑 주소 세종시청 ADR-f42967b1 — 시험 뒤 원래 주소로 돌릴지 사용자). 세종 시험 주문은 MFC 시뮬레이터 창에서 사용자 승인 대기. 시뮬레이터 대조(세종·춘천·남구)도.
- [ ] **HR-6 직원 표시 상태**(사용자 "이건 나중에") — 요청서 `service-requests/hr-service.md` HR-6, PC 1 추천 `loginAvailable` boolean.
- [ ] (보류 — 사용자 결정) CA-2 AI 채팅 막기·정지 단어 — 끄고 기록만(`service-requests/customer-assistant-service.md`).
- [ ] 결제 모니터링 초안(작업 트리 payment-service·monitoring 미커밋) — 아래 §0 줄과 같음.
- [x] **칠곡 DC `CDC01` center-master 등록** — 2026-11-25 LAN 끝(`deploy/lan/seed/cdc_center.py --apply`, 승인 AP-858d6c29, version 1). local 은 로컬 스택 띄울 때 같은 스크립트(로컬 토큰) —(cdc 세션 요청 2026-11-24, CDC 시뮬레이터 R-01) — `service-requests/center-master-service.md` CM-3. 값은 `docs/simulator/cdc/chilgok-dc.md` §1.
- [x] **cdc-location-service 설정 정본**(2026-11-25 PR 세션 pmart-config 9ae3fbb, #445 병합 전) — pmart-config 에 `cdc-location-service.yml`(시험 사본 `services/cdc-location-service/src/integrationTest/resources/pmart-config/cdc-location-service.yml` 과 같게 — 포트 8602·`pmart.messaging.domain: cdc`·소비 없음). LAN 배치·게이트웨이 라우트는 시뮬레이터 실서버 모드 때 cdc 세션이 따로 요청(cdc 세션 2026-11-25).
- [x] **cdc-outbound-service 설정 정본**(2026-11-25 PR 세션 pmart-config 13d64d4 — 본 파일·-local.yml, lan 은 LAN 배치 때) — pmart-config 에 `cdc-outbound-service.yml`(시험 사본 `services/cdc-outbound-service/src/integrationTest/resources/pmart-config/cdc-outbound-service.yml` 과 같게 — 포트 8603·`pmart.messaging.domain: cdc`·소비 없음)과 프로필별 `pmart.outbound.cdc-inventory-base-url`(local `http://localhost:8601`, lan 은 cdc-inventory 배치 주소). LAN 배치·게이트웨이 라우트(`/api/v1/waves/**`·`/api/v1/cdcs/*/tasks/**`·`/api/v1/cdcs/*/workers/*/next-tasks`)는 시뮬레이터 실서버 모드 때 cdc 세션이 따로 요청(cdc 세션 2026-11-25).
- [x] **CDC 시뮬레이터 실서버 모드 준비(R-16, cdc 세션 2026-11-25)** — 2026-11-25 PC 1 끝(사용자 "전부 진행"·"푸시하고 배포해"): pc3 cdc-inventory·cdc-location·cdc-outbound(8601·8602·8603), 게이트웨이 cdc-outbound·`/cdcs/*/locations` 라우트·CORS 5182, 역할 CDC_SUPERVISOR·CDC_WORKER, 계정 29개(`lan-test-accounts.md` §2-5), 위치 32,436칸(승인 AP-1fd3f64d). 시작 재고(#460)는 병합 뒤 — — 근거 `docs/simulator/cdc/chilgok-dc.md` CDC-9(사용자 "LAN 서비스 직접 호출"), 시뮬레이터 `docs/backend-requests.md` R-16.
  1. **LAN 배치**: cdc-inventory·cdc-location 먼저(설정 정본 있음), 이어서 cdc-outbound(#452 병합 뒤 — 정본 `cdc-outbound-service.yml` 과 `pmart.outbound.cdc-inventory-base-url`). **cdc-inventory 0.5.0(PR `cdc/shelf-first`)부터 `pmart.inventory.cdc-location-base-url`**(cdc-inventory-service-<프로필>.yml, 예 lan `http://<cdc-location>:8602`) — 없으면 기본값 `http://cdc-location-service:8602`, 닿지 않아도 할당은 되지만 피킹 선반 먼저가 빠진다. 배치 뒤 시드 `deploy/lan/seed/cdc_locations.py --apply --approval-ref <BO-06>`(위치 23,076칸). 시작 재고·안전량 시드는 SKU 정해지면 cdc 세션이 스크립트로 준다.
  2. **게이트웨이 라우트(공개 경로만 — `/internal/**` 차단은 그대로)**: cdc-outbound `/api/v1/waves/**`·`/api/v1/cdcs/*/tasks/**`·`/api/v1/cdcs/*/workers/*/next-tasks`, cdc-location `/api/v1/cdcs/*/locations/**`(0.3.0 경로 — PC1 R-16 진행 중 겹침 지적으로 바꿈)(cdc-inventory `/api/v1/cdcs/*/sku-handlings/**`·`sku-handling-batches/**` 는 이미 있음). fleet·transport-execution 은 TMS 쪽 결정과 함께.
- [x] **CDC 미출발 취소 게이트웨이 라우트(cdc 세션 2026-11-25, PR `cdc/pre-departure-cancel` 병합 뒤)** — 2026-11-25 끝(pmart-config bea3329, 게이트웨이 fa190338, 토큰 없이 401 확인) — — cdc-outbound 0.7.0 `/api/v1/cdcs/*/transfers/*/pre-departure-cancellations/**`(`/api/v1/transfers/**` 는 replenishment 라 CDC 아래로 옮김). 이미지 재배포 함께(Flyway V6).
  3. **시뮬레이터 → LAN 내부 경로 직접**: 시뮬레이터 PC 에서 cdc-outbound·cdc-inventory 서비스 포트(8603·8601)에 닿게(방화벽·주소 안내). 게이트웨이로 `/internal` 을 열지 않는다.
  4. **CORS** `http://localhost:5182`(R-06).
  5. **시험 계정(칠곡 `CDC01`)**: 운영자 1명(제안 `claude-cdc-sup`)과 가상 작업자(제안 `cdcw-fc-01~10` FC·`cdcw-dl-01~05` 분배/라인·`cdcw-qc-01~05` 검품·`cdcw-ld-01~05` 상차·`cdcw-fk-01~03` 지게차) — 센터 클레임 `CDC01`. CDC 역할 이름은 `staff-roles.md` 에 아직 없음(제안 `CDC_SUPERVISOR`·`CDC_WORKER` — 지금 CDC 서비스는 역할을 보지 않고 운영자 토큰·centers 만 본다). 만들면 `lan-test-accounts.md` 에 적고 시뮬레이터 세션에 이름을 알린다(토큰 발급 흐름을 그 이름으로 맞춤).
- [ ] **cdc-outbound 첫 사건 구독 바인딩(cdc 세션 2026-11-25, PR `cdc/count-shortfall` 병합 뒤)** — pmart-config `cdc-outbound-service.yml` 에 `spring.cloud.function.definition: inventoryEvents` + `spring.cloud.stream.bindings.inventoryEvents-in-0.destination: cdc.inventory.v1`(시험 사본과 같게 — 빠지면 기동이 멈춘다), 이미지 재배포 순서 cdc-inventory 0.10.0 → cdc-outbound 0.17.0.
- [x] **CDC 실사 게이트웨이 라우트·설정(2026-11-25 PR 세션 — pmart-config 76958a5: 라우트 `/cdcs/*/count-sessions`·`/**`, lan `approval-ref.mode: RECORD_ONLY`, pc3 cdc-inventory v6)(cdc 세션 2026-11-25, PR `cdc/count-sessions` 병합 뒤)** — cdc-inventory 0.8.0 `/api/v1/cdcs/*/count-sessions/**`(운영자 — 허용 목록은 PR 에서 재생성), pmart-config cdc-inventory `pmart.inventory.approval-ref.mode`(lan 은 AP-4 전까지 `RECORD_ONLY` — 비우면 결정 APPROVE 가 422 CONFIG_REQUIRED), 이미지 재배포(Flyway V6).
- [ ] **cdc-inventory MFC 수령 구독 바인딩(cdc 세션 2026-11-25, PR `cdc/receipt-transit` 병합 뒤)** — pmart-config `cdc-inventory-service.yml` 의 `spring.cloud.function.definition: productEvents;mfcEvents` + `spring.cloud.stream.bindings.mfcEvents-in-0.destination: mfc.inventory.v1`(시험 사본과 같게 — 빠지면 기동이 멈춘다), 이미지 재배포(Flyway V7). 실제 반영은 mfc-inventory IV-8(PC 3) 발행 뒤.
- [x] (2026-11-26 PR 세션 — pc3 배치·pmart-config 0aff457·라우트·DB) **cdc-quality-service 새 서비스(cdc 세션 2026-11-26, PR `cdc/quality-service` 병합 뒤)** — ① pmart-config `cdc-quality-service.yml`(시험 사본 `services/cdc-quality-service/src/integrationTest/resources/pmart-config/cdc-quality-service.yml` 과 같게) + `cdc-quality-service-lan.yml`(`pmart.quality.cdc-inventory-base-url` = LAN cdc-inventory 주소, 승인 `mode: RECORD_ONLY`, pricing·approval 주소는 VERIFY 때) ② LAN DB `cdc_quality_service`·배치(로컬 포트 8604) ③ 게이트웨이 라우트 `/api/v1/cdcs/*/quality-holds/**`·`/api/v1/cdcs/*/quality-holds`·`/api/v1/cdcs/*/disposal-approvals/**`·`/api/v1/cdcs/*/disposal-approvals` → cdc-quality(허용 목록은 PR 에 있음) ④ AP-4 덧붙임 2(targetService cdc-quality-service).
- [ ] **cdc 리콜(cdc 세션 2026-11-26, PR `cdc/recall-inventory`·`cdc/recall-outbound`·`cdc/recall-quality` — 이 순서로 함께 배포)** — ① 게이트웨이 라우트 `/api/v1/cdc-recalls`·`/api/v1/cdc-recalls/**` → cdc-quality(허용 목록은 PR) ② cdc-quality 의 첫 사건 발행 `RecallIssued`(토픽 `cdc.quality.v1`) — LAN Debezium outbox 커넥터에 `cdc_quality_service` DB 를 넣기(다른 서비스와 같은 방식) ③ cdc-outbound Flyway V16, cdc-inventory 0.18.0(Flyway 없음).
- [ ] **cdc-inventory 상품 이름 주소(cdc 세션 2026-11-26, CI-1 PR `cdc/inventory-names-numbers` 병합 뒤)** — pmart-config `cdc-inventory-service-lan.yml` 에 `pmart.inventory.product-base-url`(LAN product-service 주소, cdc-outbound 의 product 주소와 같게). 비어 있으면 productName 이 null 로만 온다. 이미지 재배포(Flyway V10).
- [ ] **cdc-quality-service 새 서비스(cdc 세션 2026-11-26, PR `cdc/quality-service` 병합 뒤)** — ① pmart-config `cdc-quality-service.yml`(시험 사본 `services/cdc-quality-service/src/integrationTest/resources/pmart-config/cdc-quality-service.yml` 과 같게) + `cdc-quality-service-lan.yml`(`pmart.quality.cdc-inventory-base-url` = LAN cdc-inventory 주소, 승인 `mode: RECORD_ONLY`, pricing·approval 주소는 VERIFY 때) ② LAN DB `cdc_quality_service`·배치(로컬 포트 8604) ③ 게이트웨이 라우트 `/api/v1/cdcs/*/quality-holds/**`·`/api/v1/cdcs/*/quality-holds`·`/api/v1/cdcs/*/disposal-approvals/**`·`/api/v1/cdcs/*/disposal-approvals` → cdc-quality(허용 목록은 PR 에 있음) ④ AP-4 덧붙임 2(targetService cdc-quality-service).
- [ ] **CDC 실사 게이트웨이 라우트·설정(cdc 세션 2026-11-25, PR `cdc/count-sessions` 병합 뒤)** — cdc-inventory 0.8.0 `/api/v1/cdcs/*/count-sessions/**`(운영자 — 허용 목록은 PR 에서 재생성), pmart-config cdc-inventory `pmart.inventory.approval-ref.mode`(lan 은 AP-4 전까지 `RECORD_ONLY` — 비우면 결정 APPROVE 가 422 CONFIG_REQUIRED), 이미지 재배포(Flyway V6).
- [ ] **approval AP-4 — CDC 재고 조정 승인 종류 `CDC_STOCK_ADJUSTMENT`(cdc 세션 2026-11-25)** — `docs/architecture/service-requests/approval-service.md` AP-4. 함께 pmart-config cdc-inventory `approval-ref.hq-loss-krw: 100000`·`hq-sku-qty: 20`(사용자 "MFC 와 같게"), lan 처음엔 `mode: RECORD_ONLY`.
- [x] **CDC LAN 실제 확인 실행(2026-11-25 PR 세션 — 사용자 확인 뒤 `--apply` 9개 OK: 선반 먼저 할당 CDC01-C-5-61-01-1-1, Wave 릴리스 202(기본 주소로 닿음 — lan 주소 넣을 필요 없음), 작업 1 DISCRETE, 정리 CANCELLED·RELEASED)(cdc 세션 2026-11-25, 사용자 "차례대로" — PR `cdc/lan-outbound-check` 병합 뒤)** — `python -I deploy/lan/checks/cdc_outbound_e2e.py --apply`(PC 1 — `.secrets` 필요). 확인: 같은 LOT 이면 피킹 선반 먼저 할당, Wave 릴리스 202(cdc-outbound → cdc-location 기본 주소), 뒷정리 미출발 취소 → TRF·할당 CANCELLED/RELEASED. 남는 것: 취소된 `TRF-LANCHK-…` 하나. 결과(OK/FAIL 줄)를 cdc 세션에 알려 주기 — 릴리스 503 이면 lan 파일에 `pmart.outbound.cdc-location-base-url`.
- [x] **CDC 재계수 대기 게이트웨이 라우트(2026-11-25 PR 세션 — pmart-config de62933·시험 사본 c9bfb703)(cdc 세션 2026-11-25, PR `cdc/shortage-reallocation-inventory` 병합 뒤)** — cdc-inventory 0.7.0 `/api/v1/cdcs/*/recount-holds/**`(GET 목록·POST 풀기, 운영자 — 허용 목록은 PR 에서 재생성). 이미지 재배포 함께(Flyway V5).
- [x] **CDC 통로 피킹 방식 게이트웨이 라우트(2026-11-25 PR 세션 — pmart-config·시험 사본 2곳, #483 와 함께)(cdc 세션 2026-11-25, PR `cdc/location-pick-method` 병합 뒤)** — cdc-location 0.5.0 `/api/v1/cdcs/*/aisle-pick-methods`(GET·POST, 운영자 — 허용 목록은 PR 에서 재생성). 이미지 재배포 함께(Flyway V3 `aisle_pick_method`).
- [x] **CDC 묶음 피킹 배포(cdc 세션 2026-11-25, PR `cdc/batch-picking` 병합 뒤 — cdc-location `cdc/location-pick-method` 먼저)** — 2026-11-25 PR 세션 끝: #484·#485 main(79893c5e·88109992·cc6d61fe), 라우트 `/cdcs/*/regions`(pmart-config 7dfe17a), pc3 cdc-location v3·cdc-outbound v7·pc4 게이트웨이, ERROR 0, LAN regions·aisle-pick-methods 200. `cdc-location-base-url` 은 lan 파일에 넣지 않고 기본 `http://cdc-location-service:8602`(pc3 같은 compose 망 — cdc-inventory 주소와 같은 방식). **실제 Wave 릴리스 호출로는 아직 확인 안 함** — 첫 릴리스가 503 이면 lan 파일에 주소를 넣는다. — cdc-outbound 0.9.0: 게이트웨이 라우트 `/api/v1/cdcs/*/regions`(GET·POST, 허용 목록은 PR 에서 재생성), pmart-config `cdc-outbound-service-<프로필>.yml` 에 `pmart.outbound.cdc-location-base-url`(lan 은 cdc-location 배치 주소 — 없으면 기본 `http://cdc-location-service:8602`, 닿지 않으면 Wave 릴리스 503), 이미지 재배포(Flyway V7 — 칠곡 지역표 5곳 시드). `pmart.outbound.batch-max-lines` 는 비워 둔다(기본값 없음).

## 0. 2026-11-21 정리 — 사용자 "9시 이후에 할게"
- [x] **LAN 행사 승인 2건(사용자)** — 2026-11-23 사용자 승인, 두 행사 APPROVED(기간 안). 대기 보상·가입 지급 0건이라 발급 없음(다음 배차 불가 취소·새 가입부터). — 백오피스 승인 화면, MARKETING·OPERATOR_ADMIN: 배차 불가 보상 2,000원 `AP-477d415d-…`(행사 `PRM-f1be8d6d-…`), 가입 축하 3,000원 `AP-5b307c83-…`(`PRM-45734b27-…`). 내가 다른 시험 계정으로 승인하는 것은 자동 모드가 막음(감독 우회). 승인 뒤 PC 1: 행사 RUNNING·대기 보상 발급·"보상 쿠폰이 도착했어요" 알림 확인.
- [x] **처리방침 가입 쿠폰 지급 기록 기간** — 2026-11-21 사용자 "추천대로": 지급 후 5년(처리방침 2026-11-21.2 판, promotion `signup-grant-retention` P1826D 매일 04:50 파기).
- [ ] **RAG 채팅 14일 자동 삭제 켜기** — PC 4 가 `PolicyQuestionFlowIT` 시험을 고친 뒤 pmart-config `pmart.assistant.answers.retention: 14d` → 켜면 프론트 백오피스 세션에 알림(#339 화면 안내 문구가 이것을 말함).
- [ ] **결제 모니터링(Grafana) 마무리** — 2026-11-20 초안(지표·경보·대시보드), 커밋·시험·배포 안 함(작업 트리에 그대로).
- [ ] 고객 앱 회신 기다림 — 쿠폰·BR-71 LAN 실기기 확인(**할인가·찜은 2026-11-23 사용자 실기기 확인 끝**), 쿠폰 알림 목적지(쿠폰함) 필요 여부(`handoff/clients/customer-app/2026-11-21-reply-coupons-questions.md`).
- [x] **검색 영양·알레르기**(2026-11-23) — product-content 0.6.0·search 0.11.0 products-v9, 358개 등록·승인, 옛 색인 v7·v8 삭제. 고단백 기준 100g 11g·100mL 5.5g(기준치 55g 의 20%) 2026-11-23 사용자 확정("추천대로").
- [ ] 나중(ADR-0082 범위 밖): 할인가 기준 가격순·할인율순 정렬, 상품 쿠폰·1+1.

## 1. 사용자 확인·결정 기다림
- [x] **HR-5 `activeOnly`(재직자만)** — 2026-11-24 사용자 "정정된걸로 해서 넣어" → hr 0.11.0(퇴사자만 뺌, 정지는 남김). 옛 메모: — 2026-11-24 사용자 "정해진 것만" → 사번 찾기(hr 0.10.0)만 만들고 이것은 결정 대기. 요청서 "있으면 좋음"(`service-requests/hr-service.md` HR-5). PC 1 추천: 기본은 지금처럼 퇴사자 포함, `activeOnly=true` 면 재직(ACTIVE·SUSPENDED?) 직원만 — 후임·넘길 사람 고르기용. 정할 것: 넣을지, SUSPENDED(정지) 직원을 재직으로 볼지.
- [x] **O-1 전달 실패 표시** — 2026-11-21 사용자 "추천대로"(나): 이미 동작(2026-11-19 BR-61 — 배송 목록 FAILED). 코드 변경 없음.
- [x] Docker Hub·LAN 배포 — 2026-11-18: Jib 은 push 토큰(`registry.env`)으로, employee-auth·hr(459135d8)·claim·고객앱 갭 A 7개·api-gateway 배포, 고객 FAQ 색인(63건).
- [x] **CL-2** — 2026-11-21 사용자 "추천대로": claim 0.12.0 `pickup.feeRefundOnCancel`(REFUND·NO_REFUND·AGENT_CHOOSES·null).
- [x] Firebase `pmart-123` 합치기 — 서버 쪽 끝(2026-11-18, pmart-config b625d04). 남은 것(클라이언트): 새 `google-services.json` 을 `apps/customer_app/android/app/` 에 두고 다시 빌드 → 고객 휴대폰 푸시 확인(직접 확인 3번).

- [ ] **Tailscale 시험 경로 내리기**(설정·계정 바꾸기·내리기 방법 `docs/operations/tailscale-lte-test-setup.md`, 2026-11-19 — 사용자가 계정 바꾸는 중)(2026-11-18 — PC 1 이 192.168.0.40/32 를 tailnet 에 광고 중, 라이더 폰 LTE 위치 시험용): 시험 끝나면 `"C:\Program Files\Tailscale	ailscale.exe" up --advertise-routes=` + 관리 화면 승인 해제(사용자).

- [x] **CL-5 쿠폰** — 2026-11-21 ADR-0082 5단계(a0d2c884) + 쿠폰 도착 알림(3404d76b) + MFC 백오피스 DC-1(claim 0.11.0 c5a4fc54). 남은 것은 아래 §0 행사 승인.

- [x] **검색 영양 정보** — 2026-11-23 끝(위 "검색 영양·알레르기" 줄, products-v9). 열량 구간 필터는 사용자가 고르지 않아 안 넣음. products-v7 삭제 끝. 동의어 2낱말(그릭요구르트·스파클링 워터)도 2026-11-23 products-v10 으로 끝.
- [ ] **상품 검색 개선**(2026-11-19 분석 — `docs/operations/2026-11-19-search-performance-analysis.md`, 사용자 "할일목록에 추가" — 시작은 사용자 지시 뒤). 지금 규모에서 검색은 빠르다(서버 약 10ms) — 고칠 것은 오류·튀는 지연·장애 번짐:
  - **다음 계획(2026-11-19 밤): `docs/operations/2026-11-19-search-next-plan.md`** — 사용자 선택 5(별점·리뷰 정렬)·6(인기순)·7(동의어·오타), 순서 11(앱 쪽 크기 20, 요청서) → 5+6(products-v6 한 번에) → 7. 시작 전 결정은 그 문서 끝. ④ LAN 배포는 사용자가 나중에.
  - 1순위 ~~① 커서 위치 상한~~(2026-11-19 밤 구현) ~~② 카테고리 캐시 한 번만·미리 갱신~~(**2026-11-19 완료·배포 c2f7ff61** — 사용자 "카테고리 들어갈 때도 느려") ~~③ `ensureIndices` 서비스 시작 때 한 번·상품 색인만(검색 기록 색인 문제로 상품 검색 실패 가능) — 계약 변경 없음 — ③도 2026-11-19 밤 구현(배포는 승인 뒤)~~
  - 2순위 ~~④ ES 호출 지표·응답 시간 히스토그램~~(2026-11-19 밤 구현) ⑤ PC 3 ES·Logstash 메모리 97% — 확인함(OOM 없음·힙 밖 캐시). **2026-11-21 사용자 "추천대로": 올리지 않음**(OOM·상품 수 증가 때 다시)
  - 3순위 ⑥ `_source` 칸 제한 ⑦ 앱 첫 쪽 size 50→20(고객 앱 몫) ⑧ track_total_hits·search_after·1글자 조각은 규모 커지면 다시 ~~⑨ includeNotCarried 쓰지 않는 분기 정리~~(2026-11-23) ~~⑥ _source 칸 제한~~(2026-11-23)

- [ ] **홈 화면 프로모션**(2026-11-20) — 범위 정함(사용자 "탭 + 배너·테마", ADR-0075), 탭·배너·테마 구현·LAN 배포(6e2f6d8a, 예시 편성 layoutVersion 1). 탭 정책값 **확정 2026-11-21 사용자 "추천대로"** — 신상품 14일·랭킹 30위(LAN 값 그대로). 남은 것: 백오피스 편성 화면·고객 앱 배너. 옛 메모: 1 랭킹·신상품 탭 → 2 일반 배너·테마(BO-05) → 3 할인·쿠폰(1차 제외 — 사용자 결정) 중 무엇부터인지. 지금 홈 상태·근거: `docs/operations/2026-11-20-pending-identity-switch-and-home-promotion.md` §2.

## 2. 어제(11-17) 저녁 목록
- [x] 1. 역할·센터 섞임 막기 — 253090cc, LAN 배포 11-18
- [ ] 2. **MFC 앱 로그인 브라우저**(PC 1 — 로그인 코드, pmart-clients PR): ⓐ Chrome → 삼성 인터넷 → Firefox 순서로 열기 ⓑ Firefox 만 있는 기기에서도 결과가 앱으로 ⓒ `TokenPair.refreshToken` nullable(ADR-0059) 맞추기. **MFC 앱만**. ⓑ 는 실기기(S25, 기본 브라우저 Firefox)로 확인.
- [x] 3. 고객센터 주문 취소 — 39d86a83, LAN 배포 11-18, PC 2 요청서
- [x] 4. 직원 권한 요청 검사 HR-2 — ①③④ 결정(2026-11-18), ③ 71ddc112, ① PC 2 요청서, ④ 나중(L-3)

## 3. 남은 권한 빈틈 (`docs/architecture/auth/permissions.md` §9·§10 — 고치기 전 사용자 승인)
- [ ] 게이트웨이 사용자 종류 허용 목록 `mode: RECORD` → ENFORCE — **2026-11-21 사용자 "추천대로"(기록 확인 → 걸린 것 없으면 전환 → 하루 지켜보기)**: 로그(es-logs 3일치) 53건 — 실제 경로의 NOT_LISTED·TYPE_NOT_ALLOWED 는 그 뒤 허용 목록 재생성(마지막 4c5fdec4, 11-21 00:26)으로 모두 고쳐졌고, `/control`·`/bff`·`/tracking` 등은 라우트에 없는 경로(어차피 404). 마지막 재생성 뒤 깨끗한 시간이 밤 7시간뿐이라 **낮 하루(11-21 업무 시간) 경고 0 이면 전환**. 확인: `curl es-logs:9200/pmart-logs-*/_search` match_phrase "user-type allowlist" 이후 시각. 전환 뒤 새 경로 PR 이 허용 목록 재생성을 빠뜨리면 바로 403 — PR 세션 점검 항목. **전환하면 PR 처리용 백엔드 세션에 시각·설정 커밋을 알린다**(그 세션 요청 2026-11-21).
- [ ] `/internal/**` 호출자 확인 없음(네트워크로만 막힘 — orchestration manual-actions operatorId 본문, order 내부 배송지)
- [ ] (PC 3 영역 — 요청만) MFC 재고 정정 등 역할 검사 없음, approvalRef 확인(RECORD_ONLY)

- [x] **CL-8 클레임 센터 거르기**(구현 2026-11-23 claim 0.13.0)(2026-11-23 사용자 "주문에 붙은 것만") — claim `GET /claims`·`/claims/counts`·`/claims/metrics/refunds` `mfcId`, 취소·교환 행 센터 채우기. 시작은 사용자 지시 뒤.

- [ ] **고객 관리 원천(PC 2 PR #365 요청서 — 병합 대기)** 2026-11-23 사용자 "목록·분류부터"·"추천대로": 1차 CU-5 직원 고객 목록·검색 + O-12 고객별 구매 요약(분류 신규 1달·우수 3개월 100만원·휴면 1년·위험 6개월) → 2차 CU-6·PY-1·PRM-1·RV-1 → 3차 O-13 → CU-7 은 사용자 결정 뒤. 정의: 이름 검색 2글자 이상 앞부분 일치(목록 가린 이름·전화, 검색 감사 기록), lastActiveAt = max(마지막 로그인·토큰 갱신, 마지막 주문). 공용 표는 #365 병합 뒤.
- [x] **BR-84 타이머 테마 모양 timerStyle**(storefront 0.12.0 구현 2026-11-23)(PC1 프론트 2026-11-23, 요청서 pmart-clients `customer-app/2026-11-23-backend-timer-theme-style.md`) — 사용자 확인 뒤 시작.

## 4. 기다리는 것(다른 쪽이 끝나면 PC 1 이 이어서)
- [x] **R-39 목포 MFC(MFC-MOKPO-01)** — 끝(2026-11-23 시뮬레이터 대조: 9,671 NORMAL·STORED, SKU 503, SKU·소비기한별 1호점과 같음, 칸 다른 줄 67 = 나눠 넣기·기타 칸)(2026-11-22~23): 센터·실제 주소, 계정 30+관리자, 설정, 권역 v2, 칸 15,529, 재고 9,671 전부 선반(마지막 1개는 #357 뒤 적치), 시험 고객 `CU-d58124e3`·취급 503. **남은 것**: 라이더 목록 센터별 거르기 필요하면 PC 5 요청(사용자 판단).
- [ ] 푸시 FID 전환 — 앱이 `register()`(FID)로 옮길 때 PC 1 이 먼저 서버를 FCM `fid` 칸으로(회신 `handoff/clients/customer-app/2026-11-18-reply-customer-push-q6-q8.md`).
- [ ] 고객앱 레퍼런스 갭 — 고객 앱이 B-2(백엔드 없음) 중 먼저 필요한 것을 알려 오면 요청서로(`handoff/clients/customer-app/2026-11-18-notice-reference-gap-update.md`).

- [x] **휴대폰 확인 스위치 + 처리방침** — 2026-11-20 켬(require-identity·처리방침 2026-11-20), 2026-11-21 처리방침 2026-11-21 판(가입 쿠폰 지급 기록).

## 5. 사용자가 직접 확인할 것 (브라우저·실기기 — 확인했다고 하면 지운다)
1. 고객 앱 실서버: 라이더 앱으로 주문 하나 배달 → 리뷰 쓰기·사진·수정·삭제, 알림함(주문 확정 → 준비 → 출발 → 완료).
2. MFC 시뮬레이터 바코드 탭 "현장 기록"(서버는 2026-11-18 `claude-mfc-wrk` 로 200 확인 — 화면만).
3. 실기기 푸시: 라이더 앱, 고객 앱 7종(Firebase 합친 뒤 다시).
4. LOT 라벨 인쇄(`deploy/lan/seed/labels/lots/*.html`) → PDA·스캐너로 스캔.
5. (새로) 고객 앱 실서버: 주문 내역 대표 상품·기간·숨기기, 장바구니 "N원 더 담기", 품절 "다음에도 적용", FAQ 화면, 배송 지연 신고, 리뷰 요청 알림(배송 완료 1시간 뒤) — 앱이 붙인 뒤.
7. (새로) 백오피스 쿠키(2026-11-18 employee-auth 0.12.1 배포): `192.168.0.40` 사이트 데이터 한 번 지우고 로그인 → 개발자 도구 Application → Cookies 에서 `__Secure-pmart_ea_csrf_…` 만료가 약 12시간 뒤인지, 431 이 다시 안 나는지.
6. 다시 올린 웹 4개(2026-11-18 `web-sync` — clients 1869c49): PR #64~#102 화면, 443·8444·8445·8446(Ctrl+F5). 올라간 버전은 `https://192.168.0.40/version.txt` 등.

## 끝난 것 (2026-11-18)
- CL-1 반품 추가 결정 9가지 LAN 배포(453dbbcf), LOT 라벨(5b1e9626), 주문서·장바구니 재고 = `shippableAvailable`(a8101448, LAN 배포), 카트 `CRT-C-01` 닫기(11-17).
- 고객 푸시 Q6·Q8 회신(ec2b7b3e), `staff_auth` 비밀번호 저장(a1733e91)·PR #163(73eaa8e7), `claude-mfc-sup` 비밀번호 파일 PC 5·PC 3.
- LAN 웹 맞추기 `lan.sh web-sync`(bfee6e07) — PR 세션이 PR 확인 때·30분마다.
- 나중에 하기로 한 것 L-1~L-13 기록, 고객앱 레퍼런스 갭 B 갱신.
- 고객앱 갭 A 8가지(리뷰 요청 알림·주문 내역 상품·기간·숨기기·장바구니 부족액·품절 다음에도·지연 신고·FAQ) + 후속 3가지(지연 신고 범위 확정·오배송 리뷰 보류·최소 주문 금액 설정 하나) — LAN 배포, FAQ 63건 색인.
- Docker Hub: Jib 저장 토큰 무효 원인 확인, 사용자 재로그인(지금 `pmart-ci-push` 와 같음).
