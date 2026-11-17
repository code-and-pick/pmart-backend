# 라이더 앱 — 고객 반품 회수 배송(CLAIM_RETURN) 화면

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-17
- 대상: pmart-clients `apps/rider_app`(rider-app 영역 — PC 5)
- 계약: `contracts/openapi/delivery-dispatch-service.yaml` 0.5.0, `delivery-execution-service.yaml` 0.10.0 — **`gen:api` 다시**
- 상태: **백엔드 PR 단계, LAN 미배포**. 반품 클레임(claim)이 회수 요청을 내기 시작해야 실제로 생긴다(claim RETURN 은 PC 1 진행 중) — PC 1 이 병합·배포하면 이 파일에 날짜를 적는다
- 앞 문서: 오배송 회수 화면 `2026-11-16-request-misdelivery-recovery.md` — 대부분 같은 화면을 쓴다

## 무엇인가
고객이 반품을 신청하고 상담원이 회수를 승인하면 서버가 **반품 회수 배송**을 만들어 **고객 집 근처 라이더**에게 바로 제안한다(사용자 결정 2026-11-17 — 원래 기사도 근처면 받을 수 있다). 라이더는 **고객 집에서 반품 상품을 받아 MFC 로 가져온다**.

## 앱이 할 것
| 단계 | 서버 | 앱 |
|---|---|---|
| 제안 | `GET /offers` 항목에 `deliveryType: RETURN`, `purpose: CLAIM_RETURN`. `summary.distanceKm` = 고객 집 → MFC, `pickupDistanceKm`·`pickupEtaSeconds` = 나 → 고객 집, `pickupPoint` = 고객 집 근처(약 1km 격자), `estimatedFee` = 일반 표(고객 집 → MFC) | "반품 회수" 표시(일반 배송·오배송 회수와 구분). 수락·거절은 같다 |
| 회수지 | 수락 뒤 `GET /deliveries/{id}/dispatch` 의 `recovery`(`claimId`, `pickup.lat·lng`, `pickup.source: DROP_ADDRESS`) | 지도에 고객 집 핀 |
| 고객 주소·전화 | `GET /deliveries/{id}/drop-info` — **배정(ASSIGNED)·실패 등록 중에 열린다**(일반 배송은 픽업 뒤부터지만 반품 회수는 받으러 가는 곳이 고객 집). 받은 뒤(`RETURN_PICKED_UP`)는 409 DROP_INFO_CLOSED. `Cache-Control: no-store` | 주소·호수·출입 방법·고객 전화 버튼. 화면 밖에 남기지 않는다 |
| 받음 | 고객 집에서 사진 `POST /deliveries/{id}/evidence-photos`(배정 상태에서 올릴 수 있다) → `POST /deliveries/{id}/returns/pickups` `{items: [], evidenceRefs: [photoRef]}` → `RETURN_PICKED_UP` | "반품 받음" 버튼. 사진 1장 이상 |
| 반납 | MFC 에서 `POST /deliveries/{id}/returns/store-deliveries` `{}`(사진·점원 확인은 선택) → `RETURNED_TO_STORE`(배송 끝, 수행료 = 수락 당시 금액) | "MFC 에 반납" 버튼 |
| 회수 실패 | 고객 부재·연락 두절·거부 등: 받기 전(ASSIGNED)에 `POST /deliveries/{id}/failures`(일반 배송 실패와 같은 본문 — 사유 + 이 배송에 올린 사진 + 고객 사유면 연락 2회 이상) → `FAILURE_REPORTED`. 지원센터가 확정하면 배송 끝(`FAILED`), 반려하면 다시 `ASSIGNED` | 일반 배송의 "전달 실패" 화면을 반품 회수에서는 **받기 전에** 연다. 문구는 "회수 실패". 고객 사정(부재·거부 등)이면 수행료는 수락 당시 금액으로 나온다(사용자 결정) |
| 막힌 단계 | 가게 도착·픽업·고객 도착·전달 완료는 409 | 반품 회수에는 이 버튼을 숨긴다 |
| 받은 뒤 문제 | 실패 등록은 받기 전만 — 받은 뒤 문제는 도움요청(`POST /deliveries/{id}/help-requests`) | 기존 도움요청 그대로 |

## 회신 받을 것
- 화면 반영 여부와, 실패 화면을 받기 전에 여는 것이 앱 흐름에 맞는지.
