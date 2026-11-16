# 라이더 앱 — 오배송 회수 배송(DD-2) 화면

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-16
- 대상: pmart-clients `apps/rider_app`(rider-app 영역 — PC 5)
- 계약: `contracts/openapi/delivery-dispatch-service.yaml` 0.4.0, `delivery-execution-service.yaml` 0.5.0 — **`gen:api` 다시**
- 상태: **백엔드 PR 단계, LAN 미배포** — PC 1 이 병합·배포하면 이 파일에 날짜를 적는다

## 무엇인가
상담원·지원센터가 오배송을 확인하면(case) 서버가 **회수 배송**을 만들어 원래 라이더가 아닌 **회수지 근처 라이더**에게 제안한다(사용자 결정 2026-11-15·16). 회수 라이더는 잘못 간 곳에서 상품을 받아 **MFC 로 가져온다**. 고객에게 가는 배송이 아니다.

## 앱이 할 것
| 단계 | 서버 | 앱 |
|---|---|---|
| 제안 | `GET /offers` 항목에 `deliveryType: RETURN`, `purpose: MISDELIVERY_RECOVERY`. `summary.distanceKm` = 회수지 → MFC, `pickupDistanceKm`·`pickupEtaSeconds` = 나 → 회수지, `dropAreaLabel` = 회수지 근처 동, `estimatedFee` = 일반 표 | "오배송 회수" 표시(일반 배송과 구분). 수락·거절은 같다 |
| 회수지 | 수락 뒤 `GET /deliveries/{id}/dispatch` 의 `recovery.pickup`(lat·lng·source — `RIDER_COMPLETION_LOCATION` 원래 라이더가 완료를 누른 곳 / `DROP_ADDRESS` 원래 배송지). 원래 배송의 완료 증빙은 `GET /deliveries/{id}`(execution)의 `recoveryOrigin`(placement·placementDetail·photoRefs·takenAt) | 지도에 회수지 핀, "원래 놓은 곳: 문 앞" 등. 정확한 호수는 지원센터가 전화로 알려 준다 |
| 원래 완료 사진 | `GET /deliveries/{원래 배송 ID}/evidence-photos/{photoRef}` — 이미지 원본(image/jpeg·png·webp), `Cache-Control: no-store, private`. 회수 중(배정~회수)인 회수 라이더가 볼 수 있다. 반납 뒤·남이면 404 | recoveryOrigin.photoRefs 를 이 경로로 보여 준다(토큰 헤더 필요 — 이미지 위젯에 헤더를 붙인다). 기기에 저장하지 않는다 |
| 원래 고객 전화 | `GET /deliveries/{id}/recovery-contact` → `receiverPhone`(원래 고객 전화번호 — 안심번호 아님), `originDeliveryId`. **전화만**(주소 없음). 회수 중(ASSIGNED·RETURN_PICKED_UP)만, 반납 뒤 409 RECOVERY_CONTACT_CLOSED | "원래 고객에게 전화" 버튼. 번호는 화면 밖에 남기지 않는다 |
| 전달지 | `GET /deliveries/{id}/drop-info` 는 **409 NO_CUSTOMER_DROP**(원래 고객 주소는 열지 않는다 — 전화는 위 recovery-contact) | 회수 배송에서는 부르지 않는다 |
| 회수 | 회수지에서 사진 `POST /deliveries/{id}/evidence-photos`(**배정 상태에서 올릴 수 있다**) → `POST /deliveries/{id}/returns/pickups` `{items: [], evidenceRefs: [photoRef]}` → 상태 `RETURN_PICKED_UP` | "상품 받음" 버튼. 사진 1장 이상 필수(없으면 400, 남의 사진이면 422 EVIDENCE_REQUIRED) |
| 반납 | MFC 도착 → `POST /deliveries/{id}/returns/store-deliveries` `{}`(사진·`receivedByStaffRef` 선택) → `RETURNED_TO_STORE`(끝, 수행료 기록) | "MFC 반납" 버튼. 지금은 MFC 도착이 반납이다(점원 확인은 나중) |
| 못 받을 때 | 일반 배송 단계(가게 도착·픽업·고객 도착·전달 완료·실패 등록)는 409 | 부재·거부면 **도움요청**(`POST …/help-requests`) — 지원센터가 판단해 끝낸다 |

- 회수 배송은 배달리스트(일반 배차모드)에 나오지 않는다 — AI 추천 제안으로만 온다.
- 상태 값 `RETURN_PICKED_UP`·`RETURNED_TO_STORE` 는 계약에 이미 있던 값이다(진행 목록·이력 표시에 더한다).
- 사진 보기 경로는 일반 배송에도 쓸 수 있다 — 배정된 라이더가 자기 배송 사진을 다시 볼 때(완료 화면 확인 등).
