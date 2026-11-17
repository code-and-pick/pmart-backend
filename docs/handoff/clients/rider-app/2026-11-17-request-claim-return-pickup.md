# 라이더 앱 — 회수 배송 화면 한 벌(고객 반품 회수·오배송 회수 공통)

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-17 — **같은 날 고침**: 처음에는 "반품 회수 화면"을 따로 요청했으나, 사용자 "백오피스에서는 반품 회수지만 라이더 입장에서는 MFC 로 이동하는 배달이 하나 더 생긴 것" → **회수 화면은 한 벌**(반품·오배송 구분 없이)
- 대상: pmart-clients `apps/rider_app`(rider-app 영역 — PC 5)
- 계약: `delivery-dispatch-service.yaml` 0.5.x, `delivery-execution-service.yaml`(회수지 연락처 통합 — 백엔드 PR) — `gen:api` 다시
- 상태: 회수 배송 수행·반품 회수 배차는 LAN 배포됨, 회수지 연락처 통합은 PR 단계
- 이 문서가 `2026-11-16-request-misdelivery-recovery.md`(오배송 회수 화면)를 대신한다 — 그 문서의 표는 참고로만

## 무엇인가
라이더에게 회수 배송은 **"어딘가에서 상품을 받아 MFC 로 가져가는 배달"** 이다. 반품(고객 집)이든 오배송(잘못 둔 곳)이든 단계는 같다. 일반 배송과 순서만 거꾸로다.

| | 일반 배송 | 회수 배송(`deliveryType: RETURN`) |
|---|---|---|
| 받는 곳 | MFC(포장 QR 인계) | 회수지 — 사진으로 증빙(QR 없음) |
| 두는 곳 | 고객(사진 → 전달 완료) | MFC(반납) |
| 버튼 | 가게 도착 → 픽업 → 고객 도착 → 전달 완료 | **받음 → MFC 반납** |

`purpose` 는 **문구만** 바꾼다: `CLAIM_RETURN` "반품 회수", `MISDELIVERY_RECOVERY` "오배송 회수".

## 앱이 할 것(한 벌)
| 단계 | 서버 |
|---|---|
| 제안 | `GET /offers` 의 `deliveryType RETURN`·`purpose`. `summary.distanceKm` = 회수지 → MFC, `pickupPoint` = 회수지 근처(약 1km 격자), `estimatedFee` = 일반 표 |
| 회수지·연락처 | 수락 뒤 **`GET /deliveries/{id}/recovery-contact` 하나로**(두 목적 모두): `receiverPhone` 은 늘, **주소(`roadAddress`·`detailAddress`·`receiverName`·`entranceMethod`·`entranceNote`)는 있을 때만 보이면 된다** — 반품 회수는 고객 집이라 있고, 오배송 회수는 원래 고객 주소를 주지 않아 null. 좌표는 dispatch `GET /deliveries/{id}/dispatch` 의 `recovery.pickup`. `drop-info` 는 쓰지 않아도 된다 |
| 받을 것 | 배송 조회 `GET /deliveries/{id}` 의 `returnLines`(반품 — 품목·수량, 이름은 `orderSummary.items`), 오배송은 `recoveryOrigin`(원래 놓은 곳·사진) |
| 받음 | 회수지에서 사진 `POST …/evidence-photos`(배정 상태에서 가능) → `POST …/returns/pickups` `{items: [], evidenceRefs: [photoRef]}` → `RETURN_PICKED_UP` |
| 반납 | MFC 에서 `POST …/returns/store-deliveries` `{}` → `RETURNED_TO_STORE`(배송 끝, 수행료) |
| 못 받음 | 반품 회수: 받기 전 `POST …/failures`(일반 배송 실패와 같은 본문 — 사진 + 고객 사유면 연락 2회, 응답 `resultId` 로 지원센터 대화). 오배송 회수: 도움요청 |
| 막힌 단계 | 가게 도착·픽업·고객 도착·전달 완료는 409 — 회수 배송에서는 숨긴다 |

## 회신 받을 것
- 회수 화면 한 벌로 반영했는지, 실패 등록을 받기 전에 여는 것이 앱 흐름에 맞는지.
