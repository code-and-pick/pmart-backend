# 배송(delivery) 인수인계 (2026-11-13)

delivery 영역(`docs/operations/pc-split.md` §1 — PC 5, 192.168.0.101)을 맡는 사람·Claude 가 **처음 읽는 문서**. 무엇이 어디 있고, 지금 어떤 상태이며, 무엇부터 할지를 한곳에 모았다. 항목별 상세는 링크가 정본이다.
그 전에: 루트 `CLAUDE.md` → `docs/operations/other-pcs-todo.md`(PC 5 절) → `docs/operations/dev-on-other-pcs.md`.

## 1. 먼저 읽을 것 (순서대로)

1. **`docs/architecture/service-requests/delivery-kickoff-from-mfc.md`** — 배송 시작 안내 한 장(2026-11-10). MFC 가 주는 사건·API, MFC 가 받으려는 사건, 결정된 규칙(D-1~D-9), 구현 범위·방식 결정, 남은 결정, 구독 시작 위치. **이 문서가 사실상 작업 지시서.**
2. 받는 서비스별 요청 파일(같은 폴더): `delivery-dispatch-service.md`(DD-1·DD-2)·`delivery-execution-service.md`(DE-1)·`rider-service.md`(RS-1)·`delivery-capacity-service.md`(CP-1)·`delivery-tracking-service.md`(TR-1)
3. 업무 원문 `docs/business/Delivery-business-logic.md` — 확정 정책은 설계가 바꾸지 않는다. 원문에 없는 규칙은 추측하지 말고 사용자에게 묻는다.
4. 인계 흐름: `docs/business/flows/package-handover-contract.md`(§5 서비스별 할 일·§7 열린 질문)·`package-handover-decisions.md`(D-1~D-9)·`cancel-vs-handover.md`
5. 서비스마다 `services/<서비스>/CLAUDE.md`(그 서비스 안 규칙·상태)

## 2. 서비스 7개 — 상태

| 서비스 | 로컬 포트 | 상태 | 계약 |
|---|---|---|---|
| delivery-serviceability-service | 8401 | **구현**(2026-10-30~31): 권역 매핑 버전(승인 SERVICEABILITY)·좌표 판정·카카오 주소 변환·배송 조건. 고객 주소 등록에 쓰임 | `contracts/openapi/delivery-serviceability-service.yaml` |
| delivery-capacity-service | 8402 | **구현**(2026-10-31): 예약 슬롯 조회·점유(주문에 쓰임). 교체 점유·`SlotHoldResult` 발행은 아직 | `…/delivery-capacity-service.yaml` |
| rider-service | 8403 | 스캐폴딩(전부 501) | `…/rider-service.yaml` |
| delivery-dispatch-service | 8404 | 스캐폴딩(501) — **1순위(DD-1)** | `…/delivery-dispatch-service.yaml` |
| delivery-execution-service | 8405 | 스캐폴딩(501) | `…/delivery-execution-service.yaml` |
| delivery-tracking-service | 8406 | 스캐폴딩(501) | `…/delivery-tracking-service.yaml` |
| rider-auth-service | 8510 | **구현·LAN 배치**(2026-11-10 구현, 11-11 PC 5 첫 배치): 라이더 Google 로그인(ID 토큰 검증만)·자체 JWT(`typ=RIDER`, `aud=pmart-api`)·갱신·로그아웃 | `…/rider-auth-service.yaml` |

- 결정: ADR-0023(카카오 주소), ADR-0052(취소 마감·오배송 병행 재발송), ADR-0053(OR-Tools 쓰면 JVM — dispatch 가 OR-Tools 를 넣으면 대상), ADR-0054(라이더 인증 = rider-auth-service), ADR-0055(Google = ID 토큰만).
- 사건 목록 `docs/architecture/events.md`, 스키마 `contracts/events/Delivery*.v1.json` 등. 배송이 발행하는 토픽 `delivery.delivery.v1`(mfc-fulfillment 가 이미 구독 중 — **항목 이름 그대로** 발행).

## 3. 지금 멈춰 있는 곳

- MFC 는 피킹 → 포장 → 배차 요청(`DispatchRequested`) → 출하 대기(READY) → 라이더 QR 인계까지 구현·LAN 배포. **배송 쪽이 없어 실제 주문이 READY 에서 멈춰 있다**(ORD-0e67de38-…, 2026-11-10 — kickoff §1).
- 그래서 첫 일은 **DD-1**: `DispatchRequested` 구독 → 배송 생성(`DeliveryCreated`, shipmentId 포함) → 제안·수락 → `DeliveryAssigned`. 첫 범위는 세로 조각 rider(등록·운행 상태) + tracking(위치) + dispatch → MFC 인계(kickoff §6 결정 1). execution 은 그다음.
- **dispatch 를 처음 켜기 전에** `mfc.fulfillment.v1` 소비 그룹 시작 위치를 2026-11-10 00:30Z 로 맞춘다(kickoff §6 — 명령 거기). LAN 실행은 PC 1 이 배포할 때 같이 — PR 설명에 "첫 배포 전 오프셋 맞춤 필요"를 적는다.

## 4. 남은 결정 (사용자 — 손대기 전에 확인)

kickoff §6 "남은 결정"이 정본. 요약:
- ~~AI 배정 계산 엔진~~ — **결정 2026-11-13**: Python AI 최적화 서버(GreedRL·RouteFinder), Spring 이 계산 요청(ADR-0058). 서버 세부(이름·위치·프레임워크·장애 때 대체)는 ADR-0058 "구현 때 정할 것"
- ~~라이더 앱 기반~~ — **결정 2026-11-13: 클라이언트(pmart-clients rider-app, PC 5)가 새로 만든다**(PMart-Delivery-Test 기반 아님)
- **2026-11-13 사용자 결정 묶음**(PR #8 검토 답):
  - AI 추천 배차(RouteFinder)는 **LAN 에서 끄고 OR-Tools 로**, **추가 학습** 진행(ADR-0058, `ai-optimizer-evaluation.md`)
  - **배송 약속 = 주문 50분 안 전달**(예약은 시간대), 약속보다 20분 더 늦을 것 같으면 지연 안내 — 알뜰배달 묶음도 약속 안에서만
  - **고객센터 취소 없음**(ADR-0052 2번 변경 — platform, `claim-service.md` CL-D1), **피킹 시작 전 포장소요시간 입력 → 배차 요청**(mfc, `mfc-fulfillment-service.md` F-2)
  - 배차 실패 → 시스템이 자동으로 다시 배차 요청, 재배차 없이 취소는 MFC 백오피스 관리자
  - 반복 배차취소 → 제재 없이 배차 알림 자동 감소
  - 배차 취소 결과는 백오피스 권한자·고객센터·지원센터(지원센터 역할은 아직 없음)
  - 픽업한 배송은 배달 완료 전까지 "진행 중"(3건이면 새 배차 없음) → delivery-execution 배달 완료가 필요
- **2026-11-14 사용자 결정(RS-1 라이더 계정 제한)**: 컴플레인이 기준 이상 쌓이면 시스템이 검토 대상으로 올리고 **백오피스 운영자가 확인 뒤 제한**(승인 화면 전에는 운영자 API), **픽업한 배송은 끝까지 완료**, **신규배차 제한도 새 배차 차단 + 픽업 전 배정 취소 후 재배차**(D-4 변경 — 세 단계 모두 같음). 구현 rider 0.3.0·dispatch `RiderEventHandler`. **컴플레인 누적 기준(RS-3, 같은 날)**: 같은 종류(신고·오배송·파손) 7일 안 3건 이상 → 검토 대상(rider 구현, case 발행 R-5 대기)
- 라이더·지원센터 화면을 기존 백오피스에 넣을지 새로 만들지 — 조사 결과 보고(2026-11-13), 사용자 결정 대기
- ~~카카오모빌리티 REST 키~~ — **2026-11-13 받음**: PC 5 `deploy/lan/.secrets/kakao-rest-api-key`(git 제외, 길찾기 호출 확인). LAN 설정(pmart-config `{cipher}`) 등록은 PC 1. 키를 문서·커밋·로그에 적지 않는다
- ~~배차 가능 시간 충돌~~ — **결정 2026-11-13: PMart 배달 서비스 시간 08~24시**
- CP-1(`CapacityReduced` 영향 주문 처리) — 사용자 결정 필요, 오배송 확인 사건 기록 주체(DD-2)

## 5. 토큰·시험

- **라이더 토큰**: LAN 서명키로 만든 시험 라이더 토큰(`typ=RIDER`, `RD-claude-*`, `aud: pmart-api`, 15분) — 서명키는 PC 1 에만 있으니 **PC 1 에 요청**(kickoff §6). 실제 라이더 로그인은 rider-auth-service(라이더 앱 Google 로그인).
- **직원 토큰**(배송 관리 API·권역 승인 등): 2026-11-13 부터 employee-auth-service 토큰만 — `dev-on-other-pcs.md` §1. 로컬은 `dev-tokens`, LAN 은 `deploy/lan/checks/staff_auth.py`. **배송 쪽 직원 시험 계정은 없다** — 필요하면 PC 1 에 요청(인사팀 등록으로 만든다).
- 로그아웃하면 그 세션 access token 이 바로 401 `SUBJECT_BLOCKED` — 직원·고객·**라이더**(rider-auth-service 2026-11-13 PC 1 추가: 로그아웃·모든 기기·refresh 재사용). 라이더 **정지** → 세션 끊기(`RiderRestricted`)는 ADR-0054 ⑤ 남은 일.
- 시험 명령: `./gradlew :services:<서비스>:check`(단위·통합 Testcontainers·계약). OR-Tools 를 넣으면 PC 에서 직접이 막힐 수 있다(PC 3 사례 — `tools/ortools-test-docker.sh`, `docs/mfc/HANDOVER.md`).
- MFC 쪽 끝까지 확인: `deploy/lan/checks/mfc_e2e.py`(라이더 인계 없음). 추적 Zipkin `http://192.168.0.40:9411`.

- **카카오모빌리티 키**: LAN 설정 `delivery-tracking-service-lan.yml` → `pmart.tracking.kakao-mobility.rest-api-key`(`{cipher}`)·`base-url`(2026-11-13 PC 1 등록, 주소 판정 키와 다른 앱 키). 읽는 코드는 아직 없음.

## 6. LAN 배치·주의

- PC 5 에 배송 6개 + rider-auth-service(`deploy/lan/placement.conf` pc5 줄). 같은 PC 에 keycloak·employee-auth-service·hr-service·메일 서버·RAG(ollama·rag-pgvector)도 있다 — **PC 5 남은 메모리 약 5GB**(2026-11-13). 빌드·시험이 무거울 때 LAN 서비스가 느려지면 PC 1 에 알린다.
- 배포는 PC 1 만(`lan.sh`). PC 5 는 PR.
- **사고 기록(2026-11-10)**: PC 5 로 옮기며 옛 DB 를 지워 권역 매핑·슬롯 점유가 사라졌다(권역은 시드로 다시 발효). 구현된 두 서비스 DB 를 옮기거나 지울 때는 `count(*)` 로 행 수를 확인한다(통계값 `n_live_tup` 금지).

## 7. 다른 영역·저장소와 연결

- **mfc**(PC 3): 인계 계약·사건. MFC 서비스·계약을 고쳐야 하면 직접 고치지 않고 `service-requests/<mfc 서비스>.md` 에 요청. 배송이 받는 요청은 그 폴더의 delivery 파일들.
- **order-service**(platform): O-1 `PackageHandedOver` → 인계 수량·주문 상태 — 인계 시험 전에 같이(PC 1 에 요청).
- **클라이언트**: 라이더 앱(`apps/rider_app`, 클라이언트 영역 rider-app). 라이더 로그인 안내 `docs/handoff/clients/rider-app/2026-11-10-clients-notice-rider-google-login.md`. 라이더 앱 handoff 는 `docs/handoff/clients/YYYY-MM-DD-rider-app-<종류>-<주제>.md`, 첫머리 `보낸 영역: delivery`.
- 일지 `docs/worklog/delivery/YYYY-MM-DD.md`(2026-11-14 부터). 이전 배송 기록은 루트 일지(`docs/worklog/2026-10-30~11-11.md`)에 섞여 있다.
