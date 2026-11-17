# 회신 — 전달 불가 "확정" 뒤 라이더 앱 흐름(상품 반납)

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-17
- 대상: pmart-clients `apps/rider_app` — 질문 "전달 불가 확정(회수) 뒤 라이더 앱 흐름"
- 계약: `delivery-execution-service.yaml` **0.15.0**, `delivery-dispatch-service.yaml` **0.6.0** — 백엔드 PR(PC 1 검토·배포 전). `gen:api` 다시
- 사용자 결정(2026-11-17): "같은 라이더에 회수 배송 자동 배정", 수행료 "고객 귀책이면 라이더는 잘못 없으니 원래 측정돼 있던 수행료" → "원배송 수행료 + 반납 이동료 따로", 고객 귀책이 아니면 "기존대로 수행료 조정"

## 답
1. **같은 라이더에게 바로, 제안(수락) 없이 배정된다** — 상품이 라이더 손(custody RIDER)일 때. 운행 꺼짐·신규배차 끔·계정 제한이어도(상품을 돌려놓아야 하므로). 배차 제안 푸시는 가지 않는다.
   - 앱이 알아채는 법: ① 원래 배송 조회 `GET /deliveries/{id}` 의 **`failure.returnDeliveryId`**(확정 뒤 몇 초 — 배차 판단 주기가 거리를 채운 뒤 배정. 그 전·custody STORE 면 null) ② 내 배송 목록 `GET /riders/me/deliveries` 에 새 RETURN 배송(ASSIGNED). 전달 불가 대화 화면에서 확정(`failure.decision CONFIRM`)을 보면 원래 배송을 몇 초마다 다시 읽어 `returnDeliveryId` 가 생기면 회수 화면으로 넘어가면 된다.
2. **받음 없이 바로 "MFC 반납"** — 반납 배송은 배정(ASSIGNED)에서 `POST /deliveries/{returnDeliveryId}/returns/store-deliveries` `{}`(사진 선택) → RETURNED_TO_STORE. `returns/pickups` 는 409. 회수 화면 한 벌에서 이 목적이면 "받음" 단계를 건너뛴다.
3. **purpose `FAILED_DELIVERY_RETURN`**(type RETURN) — 문구 예 "전달 실패 반납". `originDeliveryId` = 실패한 배송, 반납할 품목은 원래 배송의 `orderSummary`. 회수지 연락처(`recovery-contact`)·전달지는 없다(고객에게 갈 일 없음). MFC 위치·전화는 다른 배송처럼 배송 조회(`mfcName`·`mfcPhone`)·dispatch 조회.
4. **custody STORE 면 라이더 할 일 없음** — 배송은 FAILED 로 끝. 화면 안내는 배송 조회 `failure` 의 `decision`(CONFIRM)·`decidedAt`·`custody`(STORE)·`reason` 으로 "지원센터가 전달 불가를 확정했어요" 정도. 지원센터 메모(decisionNote)는 직원용이라 주지 않는다 — 라이더에게 할 말은 전달 불가 대화로.
5. **수행료**(배달내역 `GET /riders/me/earnings`·배송 조회 `earning`):
   | 실패 사유 | 실패한 배송 | 반납 배송 |
   |---|---|---|
   | 고객 사유(CUSTOMER_ABSENT·UNREACHABLE·ADDRESS_ERROR·REFUSED) | 수락 당시 금액, 확정 대기 → 5시간 뒤 확정 | 일반 표(고객 집 → MFC), 반납 때 확정 대기 → 5시간 뒤 |
   | 그 밖(DAMAGED·RIDER_INCIDENT·OTHER) | 산정 불가 → 지원센터 조정(귀책에 따라 지급/미지급) | 산정 불가 → 지원센터 조정 |

## 앱이 할 것
- 회수 화면 한 벌(`2026-11-17-request-claim-return-pickup.md`)에 purpose `FAILED_DELIVERY_RETURN` 더하기(받음 건너뜀, 버튼 "MFC 반납"만).
- 전달 불가 확정 → `returnDeliveryId` 가 생기면 그 회수 화면으로, custody STORE 면 끝 안내. 목업 상담원 "확정(회수)" 시험.
