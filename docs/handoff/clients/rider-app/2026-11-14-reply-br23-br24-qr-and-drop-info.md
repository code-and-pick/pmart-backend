# 라이더 앱 — BR-23(포장 QR 형식)·BR-24(전달지) 회신

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-14
- 대상: pmart-clients `apps/rider_app`(rider-app 영역 — PC 5), 클라이언트 `docs/backend-requests.md` BR-23·BR-24
- 상태: BR-23 은 **이미 서버에 있는 형식**(바로 반영 가능). BR-24 는 **백엔드 PR 단계, LAN 미배포** — PC 1 이 병합·배포하면 이 파일에 날짜를 적는다

## BR-23 포장 QR 원문 — MFC 형식 그대로 (사용자 결정 2026-11-14 "MFC 형식에 맞춰")
- 형식: **`PKG:<packageId>:<packageVersion>`** — mfc-fulfillment 가 밀봉 때 발급하는 `Package.qrCode` 와 같다(`mfc-fulfillment-service.yaml` `authorizePackageHandover` 설명, 재포장하면 새 ID·버전으로 새 QR).
- 앱이 할 일:
  - 목 형식 `PMART-PKG:{id}:v{n}` 해석을 **`PKG:` 접두어**로 바꾼다. 버전에는 `v` 가 붙지 않는다.
  - **마지막 `:` 를 기준으로 나누어** 앞부분은 `PKG:` 뒤 packageId, 뒷부분은 정수 버전으로 읽는다.
  - 경로 `POST /packages/{packageId}/handover-authorizations` 의 `scannedCode` 에는 **스캔한 원문 그대로**를 넣는다.
- 서버 판정: 원문이 지금 포장의 qrCode 와 다르면(옛 버전 라벨 — 재포장됨) 409 `PACKAGE_INVALIDATED` → "새 라벨을 받아 다시 찍어 주세요".
- 배송의 포장 목록(`GET /deliveries/{id}` `packageIds`)에 없는 packageId 면 서버에 보내지 말고 "이 배송의 포장이 아닙니다"로 안내.

## BR-24 라이더용 전달지 — `GET /api/v1/deliveries/{deliveryId}/drop-info` (delivery-execution 0.3.2)
- 누가: **배정된 라이더 본인**만. 다른 라이더면 404, 직원 토큰이면 403.
- 언제: **픽업 뒤 ~ 전달 완료 전**(상태 PICKED_UP·ARRIVED_AT_CUSTOMER·FAILURE_REPORTED). 그 전·뒤에는 409 `DROP_INFO_CLOSED`.
- 응답: `roadAddress`·`detailAddress`·`postalCode`·`receiverName`·`receiverPhone`·`entranceMethod`(FREE·PASSWORD·CALL·OTHER)·`entranceNote`·`deliveryNote`(배송 요청사항)·`lat`·`lng`(고객 지도 핀, 참고).
  - **연락처는 고객 전화번호 그대로**입니다(안심번호 없음 — 사용자 결정 2026-11-14).
  - 값은 주문 당시 사본이라 고객이 주소록을 고쳐도 바뀌지 않습니다.
- 오류:
  - 2026-11-02 이전 주문처럼 사본이 없으면 404 `DROP_INFO_NOT_FOUND` → "전달지 정보를 불러오지 못했어요. 지원센터에 문의".
  - 주문 서비스 장애면 503 → 잠시 뒤 다시.
- **개인정보 취급(앱)**:
  - 응답은 `Cache-Control: no-store` 입니다. 앱도 디스크·로그·크래시 리포트에 남기지 않습니다.
  - 화면을 나가거나 전달 완료·실패 확정이 되면 메모리에서도 지웁니다.
  - 출입 메모는 공동현관 비밀번호일 수 있습니다. 화면에만 보이고 복사·공유 기능은 두지 않는 것을 권합니다.
  - 전화는 `tel:` 링크로 겁니다.
- 전달 실패 등록에 필요한 "연락 2회 이상"(고객 사유)은 지금처럼 앱이 기록해 보냅니다. 서버는 통화 여부를 알 수 없습니다.

## 같이 바뀐 것(앱 영향 없음)
- 위치 업로드: 서버가 **30일** 지난 위치 기록을 지웁니다(사용자 결정 2026-11-14).
- 배달 중(배정 ~ 완료 전) 위치가 **3분** 넘게 안 오면 서버가 "위치 끊김"을 기록해 지원센터·배차가 봅니다. 앱은 배달 중에 위치 업로드를 멈추지 않으면 됩니다(백그라운드 포함).
