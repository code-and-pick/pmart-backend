# 알림 — 라이더 백오피스용 API(관제·라이더 기록·주문번호 찾기) + 정해진 것(ADR-0068)

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery 백엔드 세션), 2026-11-17
- 대상: pmart-clients `web/rider-backoffice`(PC 5 클라이언트 세션)
- 상태: 백엔드 PR(PC 1 검토·배포 전). 게이트웨이 경로는 PC 1(설정 저장소) — 배포 때 같이

## 정해진 것(사용자 2026-11-17 "추천대로 전부 시작해" → ADR-0068)
1. 기본 백오피스의 라이더 운영 화면을 **이 앱으로 옮긴다** — 같은 화면이 여기 생겨 LAN 에 올라간 뒤 기본 백오피스 메뉴를 내린다(PC 2 에 알림 — `clients/backoffice/2026-11-17-notice-rider-ops-menus-moving.md`). 상담원(CS_AGENT)의 라이더·배송 조회는 기본 백오피스에 남는다.
2. **관제는 RIDER_OPS·OPERATOR_ADMIN 만**(서버 403). 센터 관리자는 다음 단계.
3. **운행 중인 라이더의 현재 위치만**, 이동 이력 없음.
4. **10초마다 다시 불러오기**(SSE 는 다음).

## 새 API
| 메뉴 | API | 비고 |
|---|---|---|
| 관제 | `GET /rider-locations?mfcId=`(tracking 0.8.0) | items: riderId·nameMasked·vehicleType·newDispatchEnabled·restriction·location{lat,lng,accuracyM,recordedAt,receivedAt,quality}\|null·staleSinceSeconds·**stale**(위치 없음 또는 3분 넘게 안 옴)·activeDeliveries[{deliveryId,stage,pickedUp,etaPickup,etaDrop}] + asOf. 200명까지, no-store. 센터 목록·좌표는 center-master `GET /centers`·`/centers/{id}` |
| 라이더 찾기 상세 | `GET /rider-earnings?riderId=&from&to&settlementState&size&cursor`(execution 0.16.0) | 라이더 앱 수행료 내역과 같은 모양(items·totals·page). RIDER_OPS·OPERATOR_ADMIN·CS_AGENT |
| 〃 | `GET /rider-operating-time?riderId=&from&to` | 라이더 앱 운행시간과 같은 규칙·모양(최대 31일) |
| 배송 찾기 | `GET /deliveries?orderNumber=12345678` | 화면용 주문번호 8자리(배정 뒤 배송만 — 배정 사건이 번호를 실어 줌) |

나머지 메뉴(라이더 찾기·배송 찾기·접수 건)의 기존 API 는 클라이언트 세션의 표 그대로 — rider `/riders`·`/riders/{id}`·`pii-reveals`·`restrictions`·`restriction-reviews`, execution `/deliveries`·`/deliveries/{id}`·`failure-decisions`·도움요청·`/delivery-help-requests`·`/rider-earnings/adjustment-queue`·`adjustments`, dispatch `/deliveries/{id}/dispatch`·`assignment-cancellations`, tracking `/deliveries/{id}/eta`, case `/cases`(kind RIDER_HELP·DELIVERY_INCIDENT·RIDER_LOCATION_STALE·DISPATCH_FAILED·MISDELIVERY)·`/inquiries?requesterType=RIDER`·`/inquiries/{id}/messages`.

## 지도
카카오 지도 JavaScript 키는 카카오 개발자 콘솔에 LAN 주소(`https://192.168.0.40:8446`)를 웹 도메인으로 등록해야 한다 — 키 값은 문서·커밋에 적지 않는다(PC 1 에 알리면 설정으로).
