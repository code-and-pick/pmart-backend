# 회신 — 라이더 백오피스 "화면은 있는데 직원용 API 가 없는 것"(2026-11-18)

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery 백엔드 세션), 2026-11-18
- 대상: pmart-clients `web/rider-backoffice` — 요청 `docs/handoff/rider-backoffice/2026-11-18-backend-rider-ops-gaps.md`(PR #89 함께)
- 사용자 2026-11-18: "응 전부 진행해" — 동의 이력은 **최신만**(저장 구조 안 바꿈), 시험 라이더 전용 정리 경로 없음
- 상태: 백엔드 PR 두 개(PC 1 검토·배포 전) — `gen:api` 다시

| # | 요청 | 답 | 계약 |
|---|---|---|---|
| 1 | 가입 승인·거절, `signupStatus` 거르기 | 정책 정해질 때까지 없음(지금 늘 APPROVED·AUTO) | — |
| 2 | 정산계좌 등록 여부 | 라이더 상세 **`bankAccountRegistered`**(true/false) + **`bankName`**(은행 이름, 없으면 null). 계좌번호·예금주는 주지 않는다 | rider **0.10.0** |
| 3 | 약관·개인정보 동의 | 라이더 상세 **`consents[]`**(`consentType`·`version`·`agreedAt`) — 항목마다 **마지막 동의만**(과거 이력은 저장하지 않음 — "동의 이력"이 아니라 "지금 동의한 버전"으로 보여 주세요) | rider 0.10.0 |
| 4 | 라이더 배정·배차 취소 이력 | **`GET /rider-assignments?riderId=&size&cursor`**(dispatch) — 배정 최신순, 줄마다 `deliveryId`·`orderId`·`mfcId`·`type`·`purpose`·`source`(AI_RECOMMENDED·DELIVERY_LIST·FAILED_RETURN)·`fee`·`assignedAt`·`deliveryState`·**`outcome`**(CURRENT 진행 중 / DONE 이 라이더로 끝남 / RELEASED 배정 풀림 / CANCELLED 픽업 전 취소)·`cancellation`(배차 취소면 `requestedBy`·`reason`·`reasonDetail`·`at`, 계정 제한·재배정처럼 취소 기록이 없으면 null). RIDER_OPS·OPERATOR_ADMIN·CS_AGENT | dispatch **0.8.0** |
| 5 | 배송 목록 `orderNumber` | `StaffDeliverySummary.orderNumber`(배정 전 null) | execution **0.21.1** |
| 6 | 상태 정정 근거 사진 | **`POST /deliveries/{id}/staff-evidence-photos`**(multipart `file`, RIDER_OPS·OPERATOR_ADMIN, JPEG·PNG·WEBP 10MB) → `photoRef` 를 상태 정정 `evidenceRefs` 에. 이 배송의 사진이 아니면 정정이 422 `PHOTO_NOT_FOUND`. 보기는 기존 `GET …/evidence-photos/{photoRef}` | execution **0.22.0** |
| 7 | 완료 방법 값 목록 | `Evidence.method` 를 계약 **enum** 으로: `FACE`·`CONTACTLESS`(라이더 사진 완료), `CUSTOMER_CONFIRMED`(고객 "받았어요" — 사진 없음, 이력 actor `CUSTOMER`), `AUTO_COMPLETED`(픽업 5시간 뒤 자동 — 사진 없음, 이력 actor `SYSTEM_EVENT`, 수행료 산정 불가). **자동 완료는 `evidence.method` 로 온다**(증빙 객체가 있고 `photoRefs` 는 빈 배열) — 화면 이름표 그대로 맞다 | execution **0.22.1** |

- 게이트웨이 경로(PC 1): `/api/v1/rider-assignments` → delivery-dispatch, `/staff-evidence-photos` 는 기존 `/deliveries/**` 길.
- 함께 고친 것: 운행시간 날짜 나누기가 자정을 걸친 구간에서 1초 모자라던 것(라이더 상세 운행시간 값이 1초 다를 수 있었다).

## 추가 — 추가 배달료 "전체" 탭(execution 0.22.2, 2026-11-18)
- 클라이언트 8429fb0 의 "전체" 탭은 `state` 를 빼고 부르는데, 예전 서버는 비우면 REQUESTED 로 걸러 승인·반려가 빠졌다. **이제 `state` 를 비우면 모든 상태(최근 신청부터)**, 주면 그 상태만(오래된 것부터). 화면은 고칠 것 없음.
