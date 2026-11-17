# 알림 — 라이더 백오피스 조치 API(상태 정정·실패 확정·추가 배달료·자동 완료) 한데 모음

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery 백엔드 세션), 2026-11-17
- 대상: pmart-clients `web/rider-backoffice`(PC 5 클라이언트 세션)
- 상태: **main 병합·배포됨**(백엔드 #150~#153 — execution **0.20.0**, dispatch **0.7.0**, tracking 0.8.1). `gen:api` 다시
- 먼저 읽은 것: 같은 폴더 `2026-11-17-notice-control-and-staff-apis.md`(관제·직원용 라이더 기록·주문번호 찾기, ADR-0068). 클라이언트 PR #87(접수 건·배송 찾기 보기만) 위에서 이어 만들면 파일 충돌이 없다.

## 사용자 결정(2026-11-17, "백오피스 백엔드에 부족한 거" → "차례대로 전부 진행해")
1. 상태 정정은 **라이더 운영 관리자가 승인**(정정하는 RIDER_OPS·OPERATOR_ADMIN 이 곧 승인자)
2. 픽업 뒤 라이더가 더 갈 수 없으면 **다른 라이더에게** 회수를 보낼 수 있게
3. 추가 배달료는 **엘리베이터 고장만**(다른 사유 없음)
4. **픽업 5시간 뒤 자동 완료**, 도착 예정 + 20분이면 고객에게 받았는지 묻고 고객이 누르면 완료
5. 수행료 지급 처리(지급 중·완료·실패)는 **외부 회계를 기다림** — 화면에 지급 버튼 없음

## 배송 찾기 상세에 붙일 조치
| 조치 | API | 누가 | 화면 메모 |
|---|---|---|---|
| **상태 정정** | `POST /deliveries/{id}/status-corrections` `{targetState, reason(2~500), evidenceRefs?}` + `X-Expected-Version` | RIDER_OPS·OPERATOR_ADMIN(그 밖 403) | 바꿀 수 있는 것만: 픽업 전 ASSIGNED ↔ ARRIVED_AT_STORE, 픽업 뒤 PICKED_UP ↔ ARRIVED_AT_CUSTOMER. 그 밖 422 `CORRECTION_NOT_ALLOWED`. 이력에 actor OPERATOR 로 남는다 |
| **전달 실패 확정·반려** | `POST /deliveries/{id}/failure-decisions` `{decision, note, custody, returnBy?, reason?}` | 그 센터 관리자·OPERATOR_ADMIN·CS_AGENT·RIDER_OPS(기존) | **`returnBy`**: custody RIDER 일 때 SAME_RIDER(기본 — 같은 라이더 바로 반납) / OTHER_RIDER(근처 다른 라이더에게 회수 제안, 회수지 = 원래 라이더 마지막 위치) |
| **지원센터 직접 실패 처리** | 같은 API에 **`reason`**(RIDER_INCIDENT 등) | RIDER_OPS·OPERATOR_ADMIN 만(상담원 403) | 라이더가 사고 등으로 실패 등록을 못 했을 때 — PICKED_UP·ARRIVED_AT_CUSTOMER 배송을 등록 없이 바로 CONFIRM. 사고면 보통 `returnBy OTHER_RIDER` 와 같이 |
| 반납 배송 따라가기 | 원래 배송 `GET /deliveries/{id}` 의 `failure.returnDeliveryId`, 반납 배송의 `purpose FAILED_DELIVERY_RETURN`·`returnPickupRequired` | — | 다른 라이더 반납이면 `returnPickupRequired true`(받음 → 반납) |

## 수행료 메뉴(라이더 찾기 상세 또는 별도 메뉴)
| 조치 | API | 누가 | 화면 메모 |
|---|---|---|---|
| **추가 배달료 신청 목록** | `GET /surcharge-requests?state=REQUESTED&riderId=` | RIDER_OPS·OPERATOR_ADMIN·CS_AGENT 보기 | 오래된 것 먼저. 항목: 배송·층·금액(서버 계산: 15~19층 500, 20~24층 1,000 …)·사진 참조(`GET /deliveries/{id}/evidence-photos/{ref}`)·상태 |
| **승인·반려** | `POST /surcharge-requests/{id}/decisions` `{decision APPROVE\|REJECT, note}` | RIDER_OPS·OPERATOR_ADMIN | 반려는 메모 필수. 승인하면 그 수행료 합계에 더해지고(`earning.compensation`) 승인자·시각이 남는다. 지급 중·지급됨이면 409 `EARNING_NOT_OPEN` |
| **산정 불가 조정 대기**(기존) | `GET /rider-earnings/adjustment-queue`, `POST /rider-earnings/{earningId}/adjustments` | RIDER_OPS·OPERATOR_ADMIN | **새로 이 대기에 들어오는 것**: ① 자동 완료(픽업 5시간 — 배송 조회 `evidence.method AUTO_COMPLETED`) ② 고객 사유가 아닌 전달 실패와 그 반납. 고객 사유 실패·고객 "받았어요" 완료는 대기 없이 지급 |

## 보기 화면에 보일 새 값
- 배송 이력 `history[].actor` 에 **CUSTOMER**(고객이 "받았어요"로 완료) — 완료 방법 `CUSTOMER_CONFIRMED`. 자동 완료는 actor SYSTEM_EVENT·방법 `AUTO_COMPLETED`(사진 없음).
- 진행 단계는 상태 정정을 따라 뒤로도 바뀐다(tracking).
- 문의 대화(라이더 문의에 라이더 메시지가 섞임 — `all/2026-11-17-notice-rider-inquiry-chat.md`): 라이더 문의 화면은 `GET /inquiries/{id}/messages` 로 그린다.

## 권한 정리(서버가 판정 — 메뉴 숨김은 편의)
- 정정·직접 실패 처리·추가 배달료 승인·조정: **RIDER_OPS·OPERATOR_ADMIN**
- 보기만: CS_AGENT(추가 배달료 목록·조정 대기·라이더 수행료)
- 관제: RIDER_OPS·OPERATOR_ADMIN(ADR-0068)
