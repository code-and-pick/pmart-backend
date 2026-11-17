# 회신 — "회신 반영 결과 + 남은 물음"(2026-11-17)

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-17
- 대상: pmart-clients `apps/rider_app` — 반영 결과 문서(남은 물음 1~4, 푸시)

## 남은 물음 1~4 — 이미 계약·LAN 에 있다
답은 **`2026-11-17-reply-failure-confirm-return.md`**(같은 폴더). 앱이 본 계약이 한 판 앞이다 — **지금 main = execution 0.15.1, dispatch 0.6.0**(PC 1 이 #142·#143 을 병합하며 execution 버전을 0.15.1 로 맞춤). **LAN 배포됨**(2026-11-17, PC 1 일지 — execution → dispatch, order). `gen:api` 다시.
1. custody RIDER 면 **같은 라이더에게 제안 없이 바로 배정**. 알아채는 법: 원래 배송 조회 `failure.returnDeliveryId`(확정 뒤 몇 초 — 배차 주기) + 내 배송 목록에 새 RETURN 배송(ASSIGNED). **받음 없이 바로 반납**(`returns/pickups` 409, ASSIGNED 에서 `returns/store-deliveries`).
2. **purpose `FAILED_DELIVERY_RETURN` = 위 1번 흐름**. 계약에 있다(execution 0.15.x `DeliveryExecution.purpose`, dispatch 0.6.0, 이벤트 DeliveryCreated·ReturnDelivered). 문구 예 "전달 실패 반납". 반품 줄이 없다 — 품목은 원래 주문 `orderSummary.items` 전부. 회수지 연락처·전달지 없음(고객에게 갈 일 없음).
3. 맞다 — custody STORE 면 FAILED 로 끝, 라이더 할 일 없음. 안내는 `failure.decision`(CONFIRM)·`decidedAt`·`custody`·`reason`. 지원센터 메모(note)는 직원용이라 주지 않는다 — 라이더에게 할 말은 전달 불가 대화로.
4. 고객 사유(CUSTOMER_ABSENT·UNREACHABLE·ADDRESS_ERROR·REFUSED): 앞 배송 = 수락 당시 금액, 반납 = 일반 표(고객 집 → MFC) — 각각 확정 대기 → 5시간 뒤 확정. 그 밖 사유: 둘 다 산정 불가(`calculable false`) → 지원센터 조정(귀책에 따라 지급/미지급), 조정되면 알림함 `RIDER_EARNING_ADJUSTED`.

## 푸시 — 서버 키 켜짐
PC 1 이 2026-11-17 라이더 FCM 서버 키(Firebase 프로젝트 `pmart-123` — 앱 `google-services.json` 과 같은 것)를 설정에 넣고 notification 을 다시 띄웠다. **지금 바로 실기기로 확인 가능**: 로그인(토큰 PUT) → 앱을 내려 둔 채
- 배차 제안이 오면 `dispatch_offer` 채널(높음, 만료되면 안 옴),
- 지원센터 답장·문의 답변·수행료 조정이면 `support`·`settlement` 채널.
받은 알림·안 온 알림을 시각과 함께 적어 주면 서버 로그로 맞춰 본다(토큰 값은 적지 말 것).

## 반영 결과 — 확인
- 회수 화면 한 벌·받기 전 실패 등록·회수 품목 이름·가게 전화(null 이면 숨김)·사진 다시 보기(메모리만)·문의 대화(authorType RIDER 를 답변에서 뺌)·채널 4개 — 모두 서버 계약과 맞다. 전달 실패 반납(FAILED_DELIVERY_RETURN)은 회수 화면에서 **받음 단계를 건너뛰게**만 더하면 된다.
