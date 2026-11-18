# 회신 — 라이더 백오피스 관제 "직원이 진행 중 배송의 전달지(정확한 주소·좌표) 보기"

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-18
- 대상: pmart-clients `web/rider-backoffice` — 요청 `docs/handoff/rider-backoffice/2026-11-18-backend-staff-drop-info.md`(PR #121)
- 사용자 2026-11-18: 개인정보 정책 **(나) 사유 없이 + 열람 기록**
- 상태: 백엔드 PR(PC 1 검토·배포 전) — `gen:api` 다시

## 1번 — 직원용 전달지: `GET /api/v1/deliveries/{deliveryId}/drop-location` (delivery-execution **0.24.0**)
- 누가: **RIDER_OPS·OPERATOR_ADMIN 만**(관제와 같은 범위 — ADR-0068). 상담원·센터 직원 403.
- 언제: 배정 ~ 전달 전(ASSIGNED·ARRIVED_AT_STORE·PICKED_UP·ARRIVED_AT_CUSTOMER·FAILURE_REPORTED). 고객 반품 회수는 회수지(고객 집)라 ASSIGNED·FAILURE_REPORTED 동안. 그 밖 409 `DROP_LOCATION_CLOSED`. 오배송 회수·전달 실패 반납은 고객 전달지가 없어 409 `NO_CUSTOMER_DROP`(도착지 = MFC).
- 응답: `kind`(DELIVERY_DROP 전달지 / RETURN_PICKUP 반품 회수지 / REDELIVERY_DROP 오배송 다시 배송 실제 집)·`roadAddress`·`detailAddress`·`postalCode`·`lat`·`lng`(다시 배송은 null)·`piiAccessLogId`. **수령인·전화·출입 방법·출입 메모·요청사항은 없음.** `Cache-Control: no-store`.
- 열람 기록: 주소를 주기 **전에** audit-service 개인정보 열람 기록(fields ADDRESS·LOCATION, purpose 는 서버 고정 문구 — 화면 입력 없음). 기록이 안 되면 **503 — 주소 없음**("잠시 뒤 다시"로). 같은 직원·같은 배송은 10분 안 다시 불러도 기록 하나.
- 화면 부탁: 고른 라이더의 배송을 열 때 한 번 부르고, **10초 관제 새로 고침마다 부르지 마세요**(기록은 묶이지만 order·audit 호출이 늘어남). 주소를 화면 밖(로컬 저장·로그·분석 이벤트)에 남기지 마세요.
- 404 `DROP_INFO_NOT_FOUND`: 주문 당시 배송지 사본이 없는 옛 주문.

## 2번 — 관제 목록 `activeDeliveries[]` 에 전달지 좌표: 이번에는 안 함
- 정확한 좌표도 고객 주소라 배송마다 열람 기록을 남겨야 하는데, 관제 목록은 10초마다 운행 중 라이더 전부를 다시 불러 기록이 크게 늘어난다. tracking 은 전달지 좌표를 갖고 있지 않아(ETA 때 dispatch 에서 읽고 저장 안 함) 새로 들고 와야 한다.
- 그래서 지금은 **고른 라이더의 배송만 1번으로** 핀을 찍어 주세요. 여러 라이더 전달지를 한 화면에 꼭 같이 봐야 하면 다시 요청해 주세요(그때 목록 한 번 = 기록 한 건(recordCount n) 방식으로 정함).
