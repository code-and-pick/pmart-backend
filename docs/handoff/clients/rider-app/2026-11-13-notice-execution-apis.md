# 라이더 앱 — 배송 수행(가게 도착·픽업 완료·고객 도착·증빙 사진·전달 완료) API 준비됨

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-13
- 대상: pmart-clients `apps/rider_app`(rider-app 영역 — PC 5)
- 계약 정본: `contracts/openapi/delivery-execution-service.yaml` 0.3.0(§3 추가) (앞 안내 `2026-11-13-notice-dispatch-apis.md` 의 배정 다음 단계)
- 상태: **백엔드 PR #8 단계, LAN 미배포** — PC 1 이 병합·배포하면 이 파일에 날짜를 적는다. 그 전에는 화면·연결 코드만 준비(`gen:api`).
- 근거: 업무 원문 Delivery §6(전달·사진 증빙), MFC 인계 결정 D-1·D-3·D-6(`docs/architecture/service-requests/delivery-execution-service.md` DE-1)

## 1. 흐름과 API
모든 변경 요청에 `Idempotency-Key`(요청마다 새 값, 다시 보낼 때는 같은 값)·`X-Source-System: rider-app`. `X-Expected-Version`(수행 버전)은 넣으면 검사.

| 순서 | 화면·버튼 | API | 메모 |
|---|---|---|---|
| 0 | 배송 상태 | `GET /api/v1/deliveries/{deliveryId}` | `state`: UNASSIGNED·ASSIGNED·ARRIVED_AT_STORE·PICKED_UP·ARRIVED_AT_CUSTOMER·COMPLETED·CANCELLED. 배정 라이더 본인만(아니면 404). 배정 직후 몇 초는 404 일 수 있다(사건 반영 전) |
| 1 | 가게 도착 | `POST /api/v1/deliveries/{id}/store-arrivals` | ASSIGNED 에서만(아니면 409). 이 뒤 포장 QR 스캔(D-3 — 순서는 앱이 지킨다) |
| 2 | 픽업 완료 | 포장마다 MFC `POST /api/v1/packages/{packageId}/handover-commits`(라이더 토큰) → `POST /api/v1/deliveries/{id}/pickups` `{handoverIds:[…]}` | **상태는 MFC 인계 사건으로만 바뀐다**(버튼만으로 픽업 처리 안 함). 아직 인계 사건이 안 왔거나 포장이 남았으면 422 `HANDOVER_NOT_CONFIRMED` — 잠시 뒤(1~2초) 같은 키로 다시 또는 `GET` 으로 PICKED_UP 확인. 다른 라이더 인계면 422 `HANDOVER_RIDER_MISMATCH`. handover-commits 409(만료 등)는 그 포장만 다시 찍기 안내(D-1) |
| 3 | 고객 도착 | `POST /api/v1/deliveries/{id}/customer-arrivals` | PICKED_UP 에서만 |
| 4 | 증빙 사진 | `POST /api/v1/deliveries/{id}/evidence-photos` multipart `file` | PICKED_UP·ARRIVED_AT_CUSTOMER 에서. JPEG·PNG·WEBP(파일 앞부분으로 판정), 10MB 이하 — 아니면 422 `PHOTO_INVALID`. 201 `{photoRef, contentType, sizeBytes, uploadedAt}`. 저장소 장애 503 → 다시 올리기, 계속 안 되면 지원센터 대체 증빙. 다른 고객·신분증·출입 비밀번호가 찍히지 않게 안내 |
| 5 | 전달 완료 | `POST /api/v1/deliveries/{id}/completions` `{method: FACE|CONTACTLESS, evidence:{photoRefs:[…], takenAt, placement?, placementDetail?}}` | ARRIVED_AT_CUSTOMER 에서만(409). photoRefs 는 **이 배송에 본인이 올린 사진**만 — 아니면 422 `EVIDENCE_REQUIRED`. 대면·비대면 모두 사진 필수, 시간 지나 자동 완료 없음 |
| - | 내 배송 | `GET /api/v1/riders/me/deliveries?date=YYYY-MM-DD&state=…&size=&cursor=` | 배정 최신순. date 는 한국 날짜(배정일). `page.nextCursor` 로 다음 |

## 2. 배정이 바뀔 때
- 픽업 전에 배차가 취소(본인·운영자)되면 이 배송은 UNASSIGNED(다시 배차 대기) 또는 CANCELLED 가 되고 본인 요청은 403·409 — 배송 목록에서 빼면 된다.
- 전달 완료가 되면 배차 쪽 "진행 중" 수에서 빠져 다음 배차를 받는다(최대 3건, 하나씩 배달이면 1건).

## 3. 전달 실패·도움요청·수행료 (계약 0.3.0 — 2026-11-13 추가)
| 화면·버튼 | API | 메모 |
|---|---|---|
| 전달 실패 | 사진 먼저 `POST …/evidence-photos` → `POST /api/v1/deliveries/{id}/failures` `{reason, detail?, evidenceRefs:[photoRef…], goodsLocation?, contactAttempts:[{attemptedAt, method?, result}]}` | PICKED_UP·ARRIVED_AT_CUSTOMER 에서. **고객 부재·연락 두절·주소 오류·수령 거부는 연락 기록 2회 이상**(없으면 422 `CONTACT_ATTEMPTS_REQUIRED`), 사진 없으면 422 `EVIDENCE_REQUIRED`. 응답 `status: ACCEPTED`·`nextAction: WAIT_OPERATOR_DECISION` — **확정 전까지 배송은 진행 중**(상태 FAILURE_REPORTED, 완료 버튼 409). 운영자가 확정하면 FAILED(배송 끝 — 다음 배차 받음), 반려하면 원래 상태로 돌아와 전달을 계속 |
| 도움요청 | `POST /api/v1/deliveries/{id}/help-requests` `{category, detail?, evidenceRefs?}` | category: CUSTOMER_UNREACHABLE·ADDRESS_ISSUE·GOODS_ISSUE·PHOTO_UPLOAD_FAILED·ACCIDENT·APP_ERROR·STORE_WAITING(점포 대기)·STATUS_CORRECTION(상태 정정)·OTHER. 202 `resultId` = 도움요청 ID. 배차취소는 이 버튼이 아니라 배차 취소 API |
| 도움요청 상태 | `GET /api/v1/deliveries/{id}/help-requests/{requestId}` | **지금은 state OPEN·caseId 없음으로 남는다** — 지원센터(case-service)가 아직 없어 접수 기록만 된다. 앱은 "접수됨" 정도로 |
| 수행료·정산 | `GET /api/v1/riders/me/earnings?from=&to=&settlementState=&size=&cursor=` | 배송마다: 완료 직후 `PENDING_CONFIRMATION`(확정 대기), 완료 5시간 뒤 `CONFIRMED`. `total` = 배차 수락 당시 금액, `payoutDate` = 지급 예정일(수행일 + 3영업일, 주말·법정공휴일 제외). `calculable: false` 는 실패·취소 등 **산정 불가**(지원센터 조정 — `note`). `totals` 는 산정 가능한 것만(예상·확정·지급). 지급 완료(PAID)는 외부 회계 연결 뒤 |

## 4. 아직 없음(501)
회수(수거·점포 반납). 화면은 자리만.
