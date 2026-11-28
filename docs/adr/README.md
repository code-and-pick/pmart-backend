# 아키텍처 결정 기록 (ADR)

| 번호 | 제목 | 상태 |
|---|---|---|
| [0001](0001-product-master-in-product-service.md) | 상품 정본은 `product-service`가 소유한다 | 제안 |
| [0002](0002-inventory-single-writer-per-center-type.md) | 재고 원장은 센터 유형별 단일 쓰기 서비스(cdc/mfc inventory) | 제안 |
| [0003](0003-order-saga-orchestration.md) | 주문 계열 사가는 orchestration, 사실 전파는 choreography (패턴별 책임: `docs/architecture/payment-saga-patterns.md`) | 승인 (2026-11-04) |
| [0004](0004-backoffice-ledgers-approval-case-center-master.md) | approval/case/center-master 서비스 추가 | 제안 |
| [0005](0005-payment-service-and-pg-source-of-truth.md) | payment-service 내부화, 거래 사실 정본은 PG | 제안 |
| [0006](0006-handover-fence-and-cancel-arbitration.md) | 인계 fence는 mfc-fulfillment, 취소 판정은 order-orchestration | 제안 |
| [0007](0007-tms-delivery-split-and-serviceability.md) | TMS/Delivery 분리, 권역 정본은 delivery-serviceability | 제안 |
| [0008](0008-slot-capacity-ownership.md) | 슬롯은 delivery-capacity, 작업 여력은 mfc-capacity, 결제 전 둘 다 확보 | 제안 |
| [0009](0009-outbox-event-envelope-idempotency.md) | Outbox + Kafka, 공통 봉투, 2계층 멱등 | 제안 |
| [0010](0010-time-utc-storage-seoul-business-dates.md) | UTC 저장, Asia/Seoul 기한 판정 | 제안 |
| [0011](0011-service-identity-tokens.md) | 서비스 신원 토큰: auth-service client_credentials, /internal 호출에만 | 일부 대체(ADR-0026) |
| [0012](0012-image-registry-docker-hub-public.md) | 이미지 저장소: Docker Hub 개인 계정 공개 저장소 `<계정>/pmart-<service>` | 제안 |
| [0013](0013-deployment-lan-compose-and-helm.md) | 배포 정의: LAN 은 배치 파일 + compose 생성, Kubernetes 는 공통 Helm 차트 | 제안 |
| [0014](0014-master-data-and-initial-stock-loading.md) | 기준정보·MFC 초기 재고는 정식 승인·반입 경로로만, 적재는 API 스크립트 | 제안 |
| [0015](0015-category-tree-follows-reference-data.md) | 카테고리 트리는 참고 자료 구조(4단계 + 노드별 필터)를 따른다 — Commerce 1·2단계 정책 대체 | 제안 |
| [0016](0016-object-storage-seaweedfs-s3.md) | 상품 이미지는 S3 호환 저장소, 로컬·LAN 은 SeaweedFS 4.48 + AWS SDK v2 2.55.11 (수집 저장소와 동일) | 제안 |
| [0017](0017-production-db-servers-five.md) | 운영 PostgreSQL 은 도메인별 DB 서버 5대, 서비스별 논리 DB·계정 분리 | 제안 |
| [0018](0018-operator-accounts-and-jwt-login.md) | 운영자는 auth-service 운영자 계정(아이디·비밀번호)으로 로그인, JWT·refresh 회전, 게이트웨이 웹 CORS | 제안 |
| [0019](0019-customer-google-login-id-token.md) | 고객 소셜 로그인은 Google 먼저 — 앱 ID 토큰을 auth-service 가 검증, 필수 동의 단계 임시 생략 | 제안 |
| [0020](0020-payment-spec-mapping-toss-widget.md) | 결제 명세를 payment-service 에 반영 — 결제 시도 ID = 토스 orderId, 고객 승인 요청 API, 웹훅은 대사 신호 | 제안 |
| [0021](0021-search-index-opensearch-projection.md) | 상품 검색 색인은 OpenSearch 사건 투영 — REST·n-gram, 버전 비교 멱등(inbox 없음), 콘텐츠 카드 보강 | 제안 (결정 1~4 는 ADR-0031 로 대체) |
| [0022](0022-guest-session-token-and-guest-paths.md) | 비회원 세션 토큰(typ=GUEST)은 guest-paths 에서만, 장바구니 병합은 X-Guest-Token 으로 소유 증명 | 제안 |
| [0023](0023-address-geocoding-kakao-local.md) | 주소 ↔ 좌표는 카카오 로컬(서버 호출, 고객·비회원), 판정은 도로명주소 좌표 — 지도 핀은 배달 위치 참고 | 제안 |
| [0024](0024-provider-profile-to-customer-service.md) | 로그인 제공자(Google) 프로필(이름·이메일·사진)은 auth 에 저장하지 않고 CustomerAuthenticated 로 customer-service 에 넘긴다 | 제안 |
| [0025](0025-customer-google-login-server-auth-code.md) | 고객 Google 로그인 하이브리드 — 앱은 서버 인가 코드, auth-service 가 OAuth 클라이언트로 교환(ADR-0019 결정 1·8 대체) | 기각 — ADR-0055 로 대체 |
| [0026](0026-no-service-to-service-authentication.md) | 서비스 간 호출은 인증하지 않는다 — 신뢰 경계는 private subnet 과 API Gateway(ADR-0011 서비스 토큰 대체) | 승인 |
| [0027](0027-config-server-github-cipher.md) | LAN 설정은 Spring Cloud Config Server(GitHub 저장소, {cipher})로 부팅 때 받는다 — 클라이언트 기본 꺼짐, 운영 Kubernetes 는 그대로 | 승인 (결정 2·3·5 는 ADR-0029 로 대체) |
| [0028](0028-dev-base-image-temurin-jdk.md) | 개발 단계 컨테이너 베이스는 eclipse-temurin:21-jdk(Exec 탭 셸·JVM 진단, 비루트 ubuntu) — 운영은 GraalVM Native 로 따로 정한다 | 승인 |
| [0029](0029-config-repo-as-source-of-truth.md) | 설정의 정본은 설정 저장소(pmart-config) — 서비스 yml 은 부트스트랩만, 환경은 프로필(local·lan·prod). ADR-0027 결정 2·3·5 대체 | 승인 |
| [0030](0030-observability-metrics-logs-dead-letters.md) | 관측 — 지표 Prometheus·Grafana·Alertmanager, 로그 Fluent Bit → OpenSearch(Dashboards), 미처리 메시지는 서비스별 dead_letter 표·재처리 API | 승인 (결정 2·4 는 ADR-0031 로 대체) |
| [0031](0031-elastic-stack-search-and-logs.md) | 검색·로그를 Elastic Stack 으로 — Elasticsearch 8.18(nori+n-gram), 검색 색인은 Logstash 가 도메인 이벤트로, 로그는 Filebeat → Logstash → ES → Kibana(상한 유지) | 승인 |
| [0032](0032-eureka-service-discovery-local-lan.md) | local·LAN 서비스 등록·조회는 Eureka(Basic 인증, PC IP+포트로 등록, 게이트웨이 lb://·RestClient 로드밸런서) — 인스턴스는 하나씩, 운영 Kubernetes 는 Service DNS | 승인 |
| [0034](0034-kafka-event-history-retention-and-reindex.md) | 상품·가격 이벤트 이력(`commerce.product.v1`·`commerce.pricing.v1`)만 무제한 보관(compaction 없음, 배포가 멱등 보정), 감시·백업·복원 절차, 검색 재색인은 Kafka 전체 재생(스냅샷 API 없음, 선행 버그 2건 수정 전 운영 실행 금지). ADR-0031 결정 3·8 보완 | 승인 |
| [0033](0033-config-refresh-webhook-bus.md) | LAN 설정 실행 중 갱신 — GitHub 웹훅(smee.io 중계) → config-server /monitor(서명 검증) → Spring Cloud Bus(Kafka springCloudBus) → 바뀐 파일의 앱이 다시 받음. ADR-0027 결정 6 대체 | 승인 |
| [0035](0035-event-consumption-spring-cloud-stream.md) | 업무 이벤트 소비·DLT·재처리는 Spring Cloud Stream(Kafka 바인더) — `PmartEventConsumer` 빈 + 설정 저장소 바인딩, 바인더 DLQ → `{topic}.DLT`+`dead_letter`, 재처리 StreamBridge. 발행은 outbox→Debezium 그대로. ADR-0009 소비 방식·ADR-0030 결정 3 수단 보완 | 승인 |
| [0036](0036-ai-assistant-phase1-policy-rag.md) | ai-assistant-service 1차(내부 운영자 정책 RAG) 앞당김 — 명시 목록 색인, 지위·생애·구현 기록 세 축, 승인+결정 번호일 때만 대체 활성, 스냅숏 색인(INGEST·SNAPSHOT 잠금 분리), 선점형 처리(조건부 쓰기), 현재 권한 재검사, 외부 모델 없는 기준선 | 제안 |
| [0037](0037-kafka-three-node-kraft-cluster.md) | LAN Kafka 3노드 KRaft 클러스터(pc2·pc3·pc4, 노드마다 브로커+컨트롤러, 컨트롤러 포트 9094) — 복제 3·min ISR 2, 클러스터 ID 고정, 브로커 자동 토픽 생성 끔(Connect·바인더·`topics-init.sh` 가 명시 생성), kafka-exporter+경보, 크기 측정 리더 기준. ADR-0034·0035 보완 | 승인 |
| [0038](0038-split-search-and-logs-elasticsearch.md) | 검색 ES·Logstash(pc3)와 로그 ES·Logstash·Kibana(pc4) 분리 — 각 ES 색인 자동 생성 범위 제한, 경보 클러스터별. ADR-0031 결정 5 대체 | 승인 |
| [0039](0039-approval-application-receipt-event.md) | 승인 반영 회신은 이벤트 `ApplicationReceiptRecorded`(`platform.approval-receipt.v1`) — 정본이 반영 트랜잭션에서 outbox, approval 이 실행 축 갱신(1차 pricing) | 승인 |
| [0040](0040-mfc-inventory-model-v2.md) | MFC 재고 모델 v2 — 위치·LOT·품질·업무상태별 단위 행 + LOT·센터 요약, LOT 단위 예약(FEFO·줄당 LOT 수 제한 없음, 최소 잔여일 냉장2/냉동30/상온30), 수령→검수→적치 후에만 판매 가능, 원장에 단위 키. 슬라이스 4 간략 모델 대체 | 승인 |
| [0041](0041-per-product-shelf-life-guarantee.md) | 상품별 소비기한 보장 — product SKU `shelfLifeGuarantee`(주문일 기준 최소 잔여일·제조 후 최대 경과일), 재고 배정은 상품 값 우선·보관 유형 기본값은 대체값, 보장 확인 못 하는 LOT(기한·제조일 없음) 미배정, LOT 제조일(검수·정정 입력). ADR-0040 결정 5 일부 대체 | 승인 |
| [0042](0042-mfc-open-decisions-lot-substitution-hold-ttl-location-code-bulk-inbound.md) | MFC 미결 4건 — 다른 LOT 대체 허용(같은 SKU·적격 기한만, IV-09 때 적용), 결제 전 점유 10분 확정·설정값(`hold-ttl`·`default-hold-ttl`), 위치 코드 계층형 고정 문법(`C-S1-03-2-04`, `RCV-C-01`, 새 등록만), 일괄 입고 단계별 파일 | 승인 |
| [0043](0043-slice-6b-picking-packing.md) | 슬라이스 6b 피킹·포장 — 감독자 수동 배정(mfc-capacity), 작업자 = 운영자 + `MFC_WORKER`, 시작은 배정 동기 조회·첫 PICK 시작 = PreparationStarted(→ 주문 PREPARING), 피킹은 포장 구역으로(예약 조각 집품량, 총량 불변), 포장 밀봉이 PACK 실적, 결품은 BLOCKED 만, 피킹 뒤 중지는 PENDING | 승인 |
| [0044](0044-pick-shortage-hold-and-resecure.md) | 피킹 결품·파손(IV-09 1부) — 파손은 격리(Q), 못 찾은 물량은 분실 조사 보류(E 만 제외, H 그대로), 아직 집지 않은 예약에서 R−q, 같은 SKU 적격 LOT 자동 재확보, 못 잡은 양은 BLOCKED(D)·ReservationBlocked. 결품 보고는 한 줄짜리 따로 | 승인 |
| [0045](0045-shortage-saga-cancel-out-of-stock-only.md) | 결품 사가(IV-09 2부) — 품절 상품만 취소는 자동(작업 취소 → 이행부족 해제 → 주문 SHORTAGE → 부분 환불 원단가×수량, 봉투·배달팁 제외), 전체 취소 선택·선택 없음·주문 전체 결품은 상신(수동) | 승인 (전체 취소 부분은 0046) |
| [0046](0046-cancel-after-pick-return.md) | 집품 뒤 취소(IV-08) — 피킹 시작된 주문의 전체 중지는 작업 종료·포장 무효·출고 취소 → inventory cancel-returns(집품·포장분 CANCEL_RETURN_PENDING, 예약 해제) → RESOLVED, RETURN 작업(MOVE)으로 선반 복귀 때 판매 가능. claim 시스템 취소 PREPARING·OUT_OF_STOCK·앞선 환불 뺀 잔액, 결품 사가 CANCEL_ALL·주문 전체 결품 → 시스템 전체 취소 | 승인 |
| [0047](0047-package-handover-fence.md) | 라이더 포장 인계(6c) — fence 는 mfc-fulfillment(주문 잠금으로 중지와 한 줄), 배정은 배송 사건 투영, 유효시간 센터 설정 기본 없음, 확정 요청 뒤 fence 는 결과까지 유지, 재고 인계 전기 H−q·E−q·R−q, 인계된 주문의 전체 중지 거절. 계약 정본 flows/package-handover-contract.md | 승인 |
| [0048](0048-mfc-overnight-quality-count-restrictions.md) | MFC 품질(격리·해제 승인·폐기)·실사·운영 제한·재포장·위치 폐지·공급 요청 — 결정 없이 구현한 기본값(Q-11·Q-13 미결 범위 안) | 승인 |
| [0049](0049-shortage-always-partial-cancel.md) | 결품은 고객 사전 선택과 관계없이 무조건 부분 취소·환불(남은 상품이 없을 때만 전체 취소) — ADR-0045 사전 선택별 처리 대체 | 승인 |

형식: 배경 → 결정 → 대안 → 결과. 상태는 `제안 → 승인 → 대체됨(by ADR-xxxx)`.
| [0050](0050-or-tools-assignment-routing-no-jgrapht.md) | 라이더 배정·배송 경로·피킹 순서는 기존 OR-Tools 로(delivery-dispatch 추가), JGraphT 도입 안 함, 도면은 MFC 마다 | 승인 |
| [0051](0051-rag-customer-expansion-split-services-ollama.md) | RAG 고객앱 확대 — 운영자용·고객용 서비스와 pgvector DB 분리(전용 컨테이너 하나), LAN pc2, 모델은 Ollama — ADR-0036 결정 11·결정 필요 1·2 대체 | 승인 |
| [0052](0052-customer-cancel-deadline-and-misdelivery-parallel-resend.md) | 고객 자율 취소는 상품준비 전까지(뒤는 고객센터 접수), 확인된 오배송은 새 발송과 회수를 동시에(재고 부족 품목은 부분 환불) | 승인 |
| [0053](0053-or-tools-services-stay-on-jvm.md) | OR-Tools 를 쓰는 서비스(mfc-capacity, 앞으로 delivery-dispatch·transport-routing)는 GraalVM Native 로 만들지 않고 JVM 으로 — ADR-0028 운영 Native 계획의 예외 | 승인 |
| [0054](0054-auth-split-keycloak-staff-rider-auth-service.md) | 인증을 셋으로 — 직원 Keycloak, 고객 지금 auth-service, 라이더 Google 로그인 라이더 auth 서비스(새로), 게이트웨이 1개·발급자 3개 — ADR-0011·0018 의 Keycloak 미도입 대체 | 승인 |
| [0055](0055-google-login-id-token-only-customer-rider.md) | Google 소셜 로그인은 ID 토큰 검증만 — 고객·라이더 각자 웹 클라이언트 ID(aud), 백엔드 OAuth2 Client·client secret·인가 코드 교환 없음(ADR-0025 대체) | 승인 |
| [0056](0056-staff-login-employee-auth-service-and-hr-service.md) | 직원 로그인 — Keycloak 은 신원만, PMart 직원 JWT 는 employee-auth-service(Keycloak 기밀 클라이언트), 직원 권한 정본도 이곳, 계정 발급은 hr-service + 사내 메일(@pmart.com), 모두 PC 5 — ADR-0054 결정 L·M 과 직원 토큰 발급자 대체 | 승인(구현 전) |
| [0057](0057-lan-dev-https-private-ca.md) | LAN 개발 HTTPS 는 사설 루트 CA 로 — 입구만 HTTPS(게이트웨이·백오피스·Keycloak·Roundcube·메일 내부), 루트 공개 인증서 커밋, 안드로이드는 lan/debug 빌드만 신뢰 | 승인 |
| [0058](0058-delivery-ai-dispatch-python-optimizer-server.md) | AI 추천 배차는 Python AI 최적화 서버(GreedRL·RouteFinder)에 Spring 이 계산 요청, 거리·시간은 카카오모빌리티 길찾기(직선거리 대체 없음), 알뜰배달은 묶음 배정(OR-Tools 또는 AI) — tech-stack "Python 런타임 없음"·ADR-0050 일부 대체 | 승인 |
| [0059](0059-web-staff-refresh-cookie-signed-double-submit.md) | 웹 백오피스 직원 refresh token 은 세션별 HttpOnly 쿠키(Path=갱신 주소/{sessionId}), CSRF 는 Signed Double-Submit(세션에 묶은 HMAC) — ADR-0018 결정 6 의 웹 직원 부분 대체 | 승인 |
| [0060](0060-no-customer-center-cancel-after-preparing.md) | 상품준비(PREPARING) 뒤 ~ 인계 전 고객 요청 취소는 고객센터로도 받지 않는다(시스템 결품 취소만, 인계 뒤 회수 D-7 그대로) — ADR-0052 결정 2 대체 | 승인 |
| [0061](0061-staff-logout-propagation-same-browser-and-keycloak-backchannel.md) | 직원 로그아웃 전파 — 같은 브라우저(같은 Keycloak 세션)의 SSO 앱 세션을 함께 끝내고, Keycloak 백채널 로그아웃을 받는다. 다른 탭은 다시 보일 때 확인 | 승인 |
| [0062](0062-staff-pii-reveal-recorded-in-audit-service.md) | 직원의 고객 개인정보 원문 열람은 사유를 받고 audit-service(`pii_access_log`, 추가 전용)에 동기 기록한 뒤에만 — 기본은 가린 값, 기록 못 하면 원문도 없음 | 승인 |
| [0063](0063-review-service-first-implementation.md) | 리뷰 1차 구현 — DeliveryCompleted 로 품목별 자격, 사진 5장·10MB, 받은 수량 전부 반품이면 새 작성 막음(쓴 리뷰는 그대로), 운영자 숨김은 뒤로 | 승인 |
| [0064](0064-notification-in-app-inbox-first.md) | 알림 1차 — 고객 앱 알림함만(푸시는 나중), 거래 안내 8가지(끌 수 없음), 결과 모르는 일은 완료라 쓰지 않음, 환불 결과는 환불 의도로 주문과 이음 | 승인 |
| [0065](0065-misdelivery-handling-by-fault.md) | 확인된 오배송은 귀책에 따라 — 고객 잘못은 다시 배송(200m 안 원래 기사·무료, 넘으면 앱 9,000원 결제 뒤 근처 라이더, 30분 안 미결제·고객 선택이면 회수 후 상품값 − 9,000원 환불), 라이더·시스템 잘못은 고객이 환불·재발송 중 선택(라이더 잘못이면 배달료 미지급) — ADR-0052 결정 4 대체 | 승인 (2026-11-16) |
| [0066](0066-rider-notification-inbox.md) | 라이더 알림함 — 지원센터 답장·도움요청 처리 끝·라이더 문의 답변·계정 제한·해제를 앱 알림함에(휴대폰 푸시는 여전히 나중) — ADR-0064 결정 2 대체 | 승인 (2026-11-16) |
| [0067](0067-mobile-push-fcm-rider-all-customer-chosen.md) | 휴대폰 푸시(FCM) — 알림함이 정본, 라이더 알림은 전부·고객은 정한 종류(출발·완료·결품·취소·환불 결과)만, 키는 앱의 Firebase 프로젝트마다 — ADR-0064·0066 의 "푸시는 나중" 대체 | 승인 (2026-11-17) |
| [0068](0068-rider-ops-screens-move-to-rider-backoffice.md) | 라이더 운영 화면은 라이더 백오피스로(같은 화면이 올라간 뒤 기본 백오피스 메뉴를 내림, 상담원 조회는 기본에 남김), 관제는 라이더 운영·관리자만·운행 중 라이더의 현재 위치만(이력 없음)·10초 다시 불러오기 — 2026-11-15 "라이더 운영 메뉴는 기본 백오피스에" 대체 | 승인 (2026-11-17) |
| [0069](0069-delivery-incident-reshipment-and-distance-fee.md) | 배송 중 사고·파손은 새 상품 재발송(오배송 재발송 사가 재사용, `DeliveryFailed` 로 시작), 사고 수행료 = 수락 금액 × 간 거리 비율 | 승인 2026-11-18 |
| [0070](0070-display-numbers.md) | 화면 번호 규칙 — 시스템 ID 는 그대로, 화면에는 주문 8자리·배송 "주문번호-순번"·라이더 6자리 + 가린 이름, 상세에 시스템 번호 | 승인 2026-11-18 |
| [0071](0071-kakao-naver-login-id-token-only.md) | 카카오·네이버 소셜 로그인도 ID 토큰 검증만 — 구글과 같은 흐름(제공자 JWKS·iss·aud, secret·access token 조회 없음), 계정은 제공자별 | 승인 (2026-11-19) |
| [0072](0072-product-ranking-signals.md) | 상품 순위 신호 — storefront 가 별점(베이지안)·최근 30일 판매를 세어 `ProductRankingSignalsChanged`, 검색 색인 products-v6 가 인기순·별점순·리뷰 많은 순에 씀 | 승인 (2026-11-19) |
| [0073](0073-search-synonyms-and-typo-correction.md) | 검색 동의어(백오피스 관리·승인·ES 동의어 집합으로 즉시 반영)와 오타 교정(자모 기준 — 0건 자동 교정·적음 제안·자동완성) | 승인 (2026-11-20) |
| [0076](0076-operator-decision-cancel.md) | 관리자 판단 취소 `OPERATOR_DECISION` — 배차 불가와 같은 역할·범위(상품준비 뒤라도 인계 전), 사유 메모 필수. 고객 취소는 상품준비 전만 그대로 | 승인 (2026-11-20) |
| [0079](0079-supervisor-task-control.md) | 센터 관리자 작업 조작 — 작업 중지(BLOCKED, 배정·재고 그대로)·해제(실물 확인 필수, 담당 살아 있으면 ASSIGNED 아니면 READY)·부분 종료(PICK 잔량만, 결품 사가로 부분 환불·예약 해제, CLOSED_PARTIAL). 다시 열기 없음 | 승인 (2026-11-20) |
| [0080](0080-pack-assigned-to-picker-on-pick-complete.md) | 피킹이 끝나면 그 출고 PACK 을 피커에게 바로 SYSTEM 배정(capacity 가 TaskProgressed 구독) — 활성 배정 있음·맡을 수 없음·휴게·근무 밖이면 READY 그대로 | 승인 (2026-11-20) |
| [0082](0082-coupons-and-period-promotions.md) | 쿠폰·기간 할인 도입 — promotion-service 활성. 기간 할인(SKU·카테고리, 가장 큰 1개)+장바구니 쿠폰(내려받기·지정·가입 축하), 승인 PROMOTION(MARKETING), 전체 취소만 복원. 원문 "1차 제외"를 대체 | 승인 (2026-11-20) |
| [0083](0083-rag-product-search-answers.md) | 운영자·고객 RAG 의 상품 질문(가격·판매 여부)은 search-service 상품 검색 결과를 정해진 문장으로(PRODUCT_INFO) — 운영자는 화면 센터 취급 여부까지, 재고 수량·주문 상태는 그대로 거절, 결과 없거나 실패하면 문서로. ADR-0036 결정 1 일부 대체 | 승인 (2026-11-20) |
| [0084](0084-operator-rag-chat-sessions.md) | 운영자 RAG 채팅 세션 — 한 세션에 여러 질문(sessionId), 앞 대화 반영(이어지는 질문은 직전 질문을 붙여 검색·생성에 참고로, 근거는 문서만), 본인 삭제(204 멱등), 마지막 질문 뒤 14일 보존(세션째 삭제), 기존 기록은 답변마다 세션 하나. 고객 RAG 는 그대로 | 승인 (2026-11-21) |
| [0085](0085-short-rider-delivery-numbers.md) | 짧은 화면 번호 — 라이더 `R4785`(R + 4자리, 기존 6자리를 바꿈, 목록엔 번호만), 배송 `D74939255`(D + 주문번호, 두 번째 배송부터 `-순번`). ADR-0070 의 배송·라이더 줄을 대체 | 승인 (2026-11-22) |
| [0086](0086-customer-misdelivery-inquiry-choice-and-rider-deduction.md) | 고객 오배송 문의 → 문의 채팅에서 다시 보내기·환불 선택(5시간 뒤 자동 환불) → 주문·배송 종결, 회수 24시간 포기 시 라이더 정산 차감(결제액 − 배달팁, 이의 5일, 이월) | 승인 (2026-11-24) |
| [0087](0087-customer-app-exchange.md) | 고객 앱 교환 접수(반품은 고객센터만) — 재발송 흐름, 상온 즉시·냉장냉동 상담원 승인, 변심 교환은 교환비(정책값), 재고 없으면 주문 때 품절 처리(부분·전체 취소) | 승인 (2026-11-25) |
| [0088](0088-semantic-product-search.md) | 상품 검색에 의미 검색 — 벡터(qwen3-embedding) + LLM 꼬리표(qwen3:4b, 검색 전용·고객 비표시)를 키워드 검색과 늘 섞되 키워드 일치를 앞에, search-service 뒤 작업·products-v11 | 승인 (2026-11-25) |
| [0089](0089-pda-session-idle-shift-length.md) | PDA 앱 무활동 한도 30분 → 근무 길이 12시간(ADR-0056 결정 14 ③ 대체) | 승인 (2026-11-25) |
| [0091](0091-auto-supply-and-cdc-transfer.md) | 자동 발주 초안(매일, 승인은 사람)과 센터 발주 승인 → 보급 DC 이관(TRF, 온도대별) — SCM 전 임시, DC 시뮬레이터 R-27 | 승인 (2026-11-27) |
| [0092](0092-rag-documents-managed-in-backoffice.md) | 운영자 RAG 문서를 백오피스에서 관리 — 정본 ai-assistant DB, 초안 → RAG_DOCUMENT 승인 → 반영 → 1분 재색인 (결정 2 는 0094 가 대체) | 승인 (2026-11-28) |
| [0093](0093-cs-consult-assistant-panel.md) | 상담 도우미 — 상담 접수 화면 옆에서 본인 확인 절·정책 근거(LLM 없음, 저장 안 함) | 승인 (2026-11-28) |
| [0094](0094-customer-rag-documents-in-db.md) | 고객 RAG 문서도 DB 에서 관리 — 기본 Git, 같은 문서(customer_app)는 원본 하나(고객 RAG DB)로 같이 관리, 운영자 RAG 는 1분마다 맞춤 | 승인 (2026-11-28) |
| [0095](0095-integration-service-docs-webhook.md) | integration-service — 문서 저장소(pmart-all-docs) push·delete 웹훅 → 파일 내용 추출 → 청킹 서비스로 전달(Spring Integration, 로컬 사본 없음) | 승인 (2026-11-28) |
| [0096](0096-chunking-service-es-rag-index.md) | chunking-service — 추출 문서를 Spring AI 리더로 청킹해 es-search RAG 색인(rag-operator·rag-customer, nori)에 문서 단위 반영(삭제는 RETIRED) | 승인 (2026-11-28) |
| [0097](0097-new-rag-system-excludes-assistant-services.md) | 새 RAG 시스템은 새 서비스로(integration → chunking → 임베딩 …) — ai-assistant-service·customer-assistant-service 제외, 삭제 안 함 | 승인 (2026-11-28) |
| [0098](0098-embedding-service-pgvector.md) | embedding-service — ES 청크를 Ollama qwen3-embedding(Spring AI)으로 1024차원 벡터로 바꿔 자기 pgvector(embedding_service)에 같은 chunkId 로(캐시·맞춰 보기) | 승인 (2026-11-28) |
| [0099](0099-rag-query-service-search-confidence.md) | rag-query-service — ES-First 질문 검색: ES BM25 → SearchConfidenceEvaluator(핵심어 비율·상대 점수 차) → 끝(상위 3 카드) 또는 embedding-service 벡터·RRF·답변 불가 | 승인 (2026-11-28) |
