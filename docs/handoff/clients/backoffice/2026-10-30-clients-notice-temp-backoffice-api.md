# 임시 백오피스 프론트 구현 참고 — 상품·콘텐츠·가격·승인·센터 취급 API

> **2026-11-13 갱신 — 로그인·계정 부분(0장 1·4행, 1.1절, 계정 관리 행)은 더 이상 맞지 않습니다.** auth-service 운영자 로그인(`/auth/operator-logins`·`/auth/password-changes`·`/operator-accounts/**`)은 없어졌고, 직원 로그인·토큰은 employee-auth-service 하나입니다(ADR-0056 — Keycloak 로그인 → 티켓 교환 `POST /employee-auth/tokens`, 갱신·로그아웃·내 세션도 `/employee-auth/*`). 근거: `archive/2026-11-13-clients-notice-auth-operator-login-removed.md`, 계약 `contracts/openapi/employee-auth-service.yaml`. refresh token 쿠키 전환(ADR-0059)은 `web-common/2026-11-13-web-common-request-refresh-cookie.md`. 토큰 모양(`typ=OPERATOR`·`roles`·`centers`)과 상품·콘텐츠·가격·승인·센터 취급 API 부분은 그대로입니다.

- 작성: pmart-backend, 2026-10-30
- 대상: pmart-clients 임시 백오피스(React)
- 기준 커밋: pmart-backend `main` (운영자 로그인 반영 커밋, 2026-10-30)
- 계약 정본: `pmart-backend/contracts/openapi/*.yaml`. 이 문서는 화면 흐름과 호출 순서를 안내한다. 요청·응답 필드는 계약 파일을 따른다.

## 0. 먼저 알아 둘 것 (막히는 부분)

| # | 항목 | 현재 상태 | 프론트 쪽 대응 |
|---|---|---|---|
| 1 | **운영자 로그인** | **구현됨**(ADR-0018). 아이디·비밀번호 → JWT. 1.1절 참고. | 로컬 첫 관리자 `admin` / `pmart-local` 로 로그인한 뒤 계정을 만든다. |
| 2 | **CORS** | **구현됨**. 게이트웨이가 허용 출처에만 연다. 로컬: `http://localhost:5173`, `http://localhost:3000`. LAN: **`http://d603-16`** — 임시 백오피스를 PC4(D603-16) Nginx 80 포트로 호스팅하는 것으로 등록했다(`deploy/lan/cors-origins.conf`). | 호스트·포트가 바뀌면 백엔드에 알려 준다(목록에 한 줄 추가). 브라우저는 `http://D603-16` 으로 접속한다(IP 로 접속하면 출처가 달라져 막힌다). |
| 3 | **이미지 표시** | `GET /api/v1/content-images/{imageId}` 도 인증이 필요하다. `<img src>` 는 JWT 를 보내지 못한다. | 당분간 `fetch` 에 Bearer 헤더를 붙여 받고 `URL.createObjectURL(blob)` 으로 표시한다. 백엔드 방식이 정해지면 바꾼다. |
| 4 | **LAN 배포** | **배포됨**(2026-10-30, 이미지 c48b6e7). 게이트웨이 `http://D603-16:8080/api/v1`. LAN 첫 관리자 아이디·비밀번호는 백엔드 담당에게 받는다(저장소에 없음). 데이터(카테고리 트리·상품·가격)는 아직 없다. | 첫 로그인 뒤 비밀번호를 바꾸고, 관리자 화면에서 MD·승인권자 계정을 만든다. |

## 1. 공통 규칙

- **주소**: 항상 게이트웨이 `:8080` 의 `/api/v1/...` 을 부른다. 서비스 포트(8101 등)를 직접 부르지 않는다.
  - 로컬: `http://localhost:8080`
  - LAN: api-gateway 가 PC4(`D603-16`)에 있다. IP 는 DHCP 라 바뀔 수 있다.
- **인증**: `Authorization: Bearer <운영자 JWT>`. 토큰 클레임은 `typ=OPERATOR`, `roles=[...]`, `centers=[...]`다.
- **변경 요청(POST/PUT) 헤더**
  - `Idempotency-Key`: 요청마다 새 UUID. 같은 키로 다시 보내면 같은 결과가 온다. 네트워크 오류로 재시도할 때는 **같은 키**를 쓴다.
  - `X-Source-System`: `backoffice-web` 같은 고정 값.
  - `X-Expected-Version`: 판매 상태 변경처럼 계약에 있는 경우만. 화면에서 읽은 버전을 넣는다.
- **오류**: RFC 9457 Problem Details(`application/problem+json`). 화면 분기는 `code` 로 한다. 예: `APPROVAL_REQUIRED`, `VERSION_CONFLICT`, `IDEMPOTENCY_CONFLICT`. `details[]` 에 필드별 사유가 온다.
- **명령 응답 `status`**: `APPLIED`(반영됨), `ACCEPTED`(접수만 됨), `REJECTED`, `UNKNOWN_RESULT`(결과 불명, 조회 API 로 확인). 접수를 성공으로 표시하지 않는다.
- **승인은 비동기다**: 승인 결정 뒤 정본 서비스 반영까지 수 초 걸린다. 결과 화면은 조회 API 를 짧게 폴링한다(예: 1초 간격, 최대 30초).

## 1.1 로그인·토큰 (auth-service, ADR-0018) — **없어짐(2026-11-13), 맨 위 갱신 참고**

| 할 일 | API |
|---|---|
| 로그인 | `POST /auth/operator-logins` `{loginId, password}` + `X-Source-System`, `X-Device-Id`(선택) → `{tokens: {accessToken, accessTokenExpiresAt, refreshToken, refreshTokenExpiresAt, sessionId}, operator: {...}}`. **Idempotency-Key 를 보내지 않아도 된다** |
| 토큰 갱신 | `POST /auth/tokens` `{refreshToken}` + `Idempotency-Key`, `X-Source-System`, `X-Device-Id`(필수. 로그인 때 보낸 값과 같아야 한다) → 새 `TokenPair` |
| 로그아웃 | `POST /auth/logouts` `{allDevices?}` → 204 |
| 내 정보·역할 | `GET /auth/sessions/current` → `{principalId, roles, centerScope, ...}` |
| 내 비밀번호 변경 | `POST /auth/password-changes` `{currentPassword, newPassword}` → 204 |
| 계정 목록·생성 (`OPERATOR_ADMIN`) | `GET` / `POST /operator-accounts` `{loginId, displayName, initialPassword, roles[], centers[]}` |
| 계정 조회·수정 | `GET` / `PUT /operator-accounts/{operatorId}` `{displayName, roles, centers, state}` + `X-Expected-Version` |
| 비밀번호 초기화 | `POST /operator-accounts/{operatorId}/password-resets` `{temporaryPassword}` → 204 |

토큰 규칙:
- access token 수명은 15분이다. 만료 전에(또는 401 을 받으면) refresh 로 새로 받는다.
- **refresh token 은 한 번만 쓴다.** 갱신할 때마다 새 refresh token 이 오므로 저장된 값을 바꿔 둔다. 이미 쓴 refresh token 을 다시 보내면 세션이 끊긴다(`TOKEN_REUSED`). 이때와 `TOKEN_INVALID` 일 때는 로그인 화면으로 보낸다.
- 탭 여러 개가 동시에 갱신하면 하나만 성공하고 나머지는 세션을 끊는다. 갱신은 한 곳에서만 하고(예: 단일 Promise 공유) 결과를 나눠 쓴다.
- 세션은 로그인부터 최대 12시간이다. 지나면 다시 로그인한다.
- `X-Device-Id` 는 브라우저마다 한 번 만든 UUID 를 저장해 두고 계속 쓴다.

로그인 실패 코드:
- `LOGIN_FAILED`(401): 아이디·비밀번호 불일치. 둘 중 무엇이 틀렸는지는 알려 주지 않는다.
- `ACCOUNT_LOCKED`(423): 연속 5회 실패하면 15분 잠긴다. 관리자가 비밀번호를 초기화하면 풀린다.
- 응답의 `operator.passwordChangeRequired` 가 true 면 비밀번호 변경 화면으로 안내한다(서버가 다른 API 를 막지는 않는다).
- 비밀번호는 10자 이상이고 아이디와 같으면 안 된다(`PASSWORD_POLICY`).

## 2. 역할(JWT `roles`)

| 역할 | 할 수 있는 일 |
|---|---|
| `MD` | 상품·콘텐츠·가격 초안 작성과 제출, 판매 상태 변경 |
| `PRODUCT_APPROVER` | PRODUCT 승인: 상품·SKU·카테고리 트리·판매중지 |
| `DISPLAY_APPROVER` | DISPLAY 승인: 고객용 상품명·설명·이미지 게시 |
| `PRICE_APPROVER` | PRICE 승인: 기본 판매가격 |
| 센터 운영자(`centers` 클레임) | 자기 센터의 SKU 취급 ON/OFF |
| `OPERATOR_ADMIN` | 운영자 계정 생성·역할·센터·비활성화·비밀번호 초기화 |

- 발의자와 승인자는 **다른 사람**이어야 한다. 자기 요청을 승인하면 `FORBIDDEN` 이다.
- 승인자 역할 이름은 백엔드 설정값이라 바뀔 수 있다.

## 3. 승인 흐름 (모든 기준정보 변경이 이 길로 들어간다)

```
① 정본 서비스에서 초안 작성·검증
② 정본 서비스 "제출 준비" → 응답의 approvalRequest(변경 요청 본문)
③ approval-service  POST /change-requests          ← approvalRequest 를 그대로
④ approval-service  POST /change-requests/{id}/submissions   → approvalId 발급
⑤ (승인자)          GET  /approvals?…  →  POST /approvals/{approvalId}/decisions  {decision: APPROVE | REJECT}
⑥ 정본 서비스가 ApprovalDecided 를 받아 반영 → ①의 조회 API 로 결과 확인(폴링)
```

- 결정은 지금 `APPROVE`·`REJECT` 만 된다. `PARTIALLY_APPROVE`·`REQUEST_EVIDENCE` 는 `UNSUPPORTED_ACTION` 이다.
- 일괄(배치) 제출은 **승인 1건 + 행 N개**이고, 통째로 승인하거나 반려한다. 일부 행만 고치려면 그 행을 빼고 다시 제출한다.

approval-service 화면에 필요한 API:

| 화면 | API |
|---|---|
| 승인함(목록·필터) | `GET /approvals` (`type`, 심사 상태, 요청자 등) |
| 승인 상세 | `GET /approvals/{approvalId}` |
| 승인·반려 | `POST /approvals/{approvalId}/decisions` |
| 결정 조회 | `GET /approvals/{approvalId}/decisions/{decisionId}` |
| 변경 요청 조회 | `GET /change-requests/{changeRequestId}` |

## 4. 화면별 API

### 4.1 카테고리 트리 (product-service)

| 할 일 | API |
|---|---|
| 현재 트리 조회(4단계: 상위·하위·칩1·칩2) | `GET /categories/tree` (발효된 트리가 없으면 404 `CATEGORY_TREE_NOT_EFFECTIVE`) |
| 노드 필터 패널 | `GET /categories/{categoryId}/filters` |
| 트리 초안 등록(JSON) | `POST /category-tree-versions` → 응답의 `approvalRequest` 로 PRODUCT 승인 |
| 트리 버전 상태 | `GET /category-tree-versions/{version}` (`DRAFT` → `EFFECTIVE`) |

상품을 등록하려면 발효된 트리가 먼저 있어야 한다.

### 4.2 상품 등록 (product-service)

| 할 일 | API |
|---|---|
| 상품 1건 초안 | `POST /product-drafts` → 응답의 `validationState`(VALID/INVALID)와 `problems[]` 표시 |
| 초안 조회·수정(제출 전) | `GET` / `PUT /product-drafts/{draftId}` |
| 제출 준비 | `POST /product-drafts/{draftId}/submissions` → `approvalRequest` 로 PRODUCT 승인(3장) |
| 일괄 업로드(seed-v1 zip, multipart `file`) | `POST /product-import-batches` → 상품별 검증 결과 |
| 배치 조회 | `GET /product-import-batches/{batchId}` |
| 배치 제출 준비(유효 행만) | `POST /product-import-batches/{batchId}/submissions` |
| 발급 결과(seedProductId → productId·sku) | `GET /product-import-batches/{batchId}/id-map` |
| 결과 확인 | 초안 `state` 가 `APPLIED` 가 되면 `resultProductId`·`resultSkus` 가 채워진다 |

- 대표 검증 오류: `CATEGORY_NOT_MAPPED`, `BARCODE_CONFLICT`, `WEIGHT_REQUIRED`, `DRAFT_INVALID`(INVALID 초안 제출).
- 반려되면 초안이 `REJECTED` 가 되고 `problems` 에 `APPROVAL_REJECTED` 가 남는다.
- 같은 seed 묶음을 다시 올리면 같은 상품의 **새 버전**이 된다(SKU 를 다시 발급하지 않음).

### 4.3 상품 조회·판매 상태 (product-service)

| 할 일 | API |
|---|---|
| 상품 목록(카테고리·판매 상태 필터, 커서) | `GET /products` |
| 상품 상세(현재 또는 `?version=`) | `GET /products/{productId}` |
| SKU 상세 | `GET /skus/{sku}` |
| 바코드로 찾기 | `GET /barcodes/{barcode}` |
| 판매 상태 변경 | `POST /products/{productId}/sale-state-changes` `{saleState, reason, approvalId?}` + `X-Expected-Version` |
| 변경 결과 | `GET /products/{productId}/sale-state-changes/{requestId}` |

판매 상태 규칙(2026-10-30 결정):
- `MD` 만 바꿀 수 있다.
- `TEMPORARILY_SOLD_OUT`(일시품절)과 `ON_SALE`(판매 재개)은 바로 바뀐다.
- `SALE_STOPPED`(판매중지)는 **PRODUCT 승인이 먼저** 있어야 한다. 승인 없이 보내면 `APPROVAL_REQUIRED` 다.
  - 판매중지 변경 요청 본문(approval-service `POST /change-requests`):
    ```json
    { "type": "PRODUCT", "targetService": "product-service", "targetRef": "SALE-STATE:<productId>",
      "allowedActions": ["CHANGE_SALE_STATE"], "summary": "판매중지: <사유>",
      "lines": [ { "lineKey": "<productId>", "attributes": { "saleState": "SALE_STOPPED" } } ] }
    ```
  - 승인된 뒤 판매 상태 변경을 `approvalId` 와 함께 보낸다. 승인 하나는 한 번만 쓸 수 있다(다시 쓰면 409).

### 4.4 상품 콘텐츠·이미지 (product-content-service)

| 할 일 | API |
|---|---|
| 콘텐츠 초안(고객용 상품명·설명·안내) | `POST /content-drafts` |
| 조회·수정(제출 전) | `GET` / `PUT /content-drafts/{draftId}` |
| 이미지 업로드(multipart, role `MAIN`·`DESCRIPTION`·`DETAIL`) | `POST /content-drafts/{draftId}/images` |
| 제출 준비 | `POST /content-drafts/{draftId}/submissions` → `approvalRequest` 로 **DISPLAY** 승인 |
| 일괄(seed-v1 zip) | `POST /content-import-batches` → `GET /content-import-batches/{batchId}` → `POST /content-import-batches/{batchId}/submissions` |
| 게시된 콘텐츠 | `GET /product-contents/{productId}`, 카드 일괄 `GET /product-contents?...` |
| 이미지 본문 | `GET /content-images/{imageId}` (0장 3번 참고) |

- 콘텐츠는 상품 승인으로 `productId` 가 생긴 뒤에야 제출할 수 있다. 그 전에 제출하면 `PRODUCT_NOT_LINKED` 다.
- 상품 초안과 같은 원천 키(seed 의 `seedProductId`)로 연결된다.

### 4.5 기본 판매가격 (pricing-service)

가격은 화면에서 **approval-service 에 PRICE 변경 요청을 직접 만든다**. pricing-service 에는 초안 API 가 없다.

```json
{ "type": "PRICE", "targetService": "pricing-service", "targetRef": "PRICE-<임의 ID>",
  "allowedActions": ["APPLY_PRICE_VERSION"], "summary": "기본가 변경 N건",
  "requestedEffectiveFrom": "2026-11-01T00:00:00+09:00",
  "lines": [
    { "lineKey": "E-00000001", "requestedAmount": 4500,
      "attributes": { "sku": "E-00000001", "basePrice": 4500, "effectiveFrom": "2026-11-01T00:00:00+09:00" } } ] }
```
- `lines[].attributes.effectiveFrom` 은 선택이다. 없으면 `requestedEffectiveFrom`, 그것도 없으면 승인 반영 시각부터 적용한다.
- `requestedAmount` 와 `attributes.basePrice` 는 같아야 한다.

| 할 일 | API |
|---|---|
| 현재 가격(+예약된 다음 가격 `nextChange`) | `GET /prices/{sku}` (없으면 404 `PRICE_NOT_SET` — 0원이 아니라 판매 불가) |
| 여러 SKU 가격(최대 100) | `GET /prices?skus=E-…,B-…` (`notSet[]` 에 가격 없는 SKU) |
| 가격 이력(최신 먼저, 커서) | `GET /price-versions/{sku}` (`SCHEDULED`/`EFFECTIVE`/`SUPERSEDED`) |
| 승인 반영 확인 | `POST /price-version-applications` `{approvalId, items[{sku, basePrice, effectiveFrom}]}` → `GET /price-version-applications/{requestId}` |

행별로 거절될 수 있다(그 행만). 화면에 표시할 사유:
- `SKU_NOT_FOUND`: 상품 승인 전인 SKU
- `ZERO_PRICE_UNDECIDED`: 0원은 아직 허용 여부가 정해지지 않았다
- `PRICE_SCHEDULE_CONFLICT`: 이미 예약된 가격보다 이르거나 같은 시각
- `APPROVAL_SCOPE_MISMATCH`: 금액 불일치

지난 시각으로 거슬러 적용하지 않는다(효력 시각이 지났으면 반영 시각부터).

### 4.6 센터 SKU 취급 ON/OFF (mfc-inventory / cdc-inventory)

| 할 일 | API |
|---|---|
| MFC 취급 목록·변경 | `GET` / `POST /mfcs/{mfcId}/sku-handlings` |
| MFC 일괄 변경 | `POST /mfcs/{mfcId}/sku-handling-batches` (전부 반영 아니면 전무) |
| CDC 취급 목록·변경 | `GET` / `POST /cdcs/{cdcId}/sku-handlings` |
| CDC 일괄 변경 | `POST /cdcs/{cdcId}/sku-handling-batches` |

- 새 SKU 의 기본값은 **OFF** 다(레코드 없음 = OFF).
- 센터 운영자는 토큰 `centers` 에 있는 **자기 센터만** 켤 수 있다.
- 고객에게 팔리려면 전사 판매 상태가 `ON_SALE` 이고, MFC 취급이 ON 이고, 현물 재고가 있어야 한다. 재고는 아직 미구현이다.

## 5. 백오피스 메뉴 제안 (구현 순서)

1. 로그인·토큰 갱신(1.1절) / 공통 레이아웃·오류 표시·멱등 키 처리
2. 승인함·승인 상세(3장) — 모든 흐름이 이걸 거친다
3. 카테고리 트리 조회·등록
4. 상품 등록: 개별 입력, seed-v1 일괄 업로드, 검증 결과, 제출, id-map 내려받기
5. 상품 목록·상세·판매 상태 변경
6. 콘텐츠·이미지 등록과 게시
7. 가격 변경 요청·가격 조회·이력
8. 센터 취급 ON/OFF

## 6. 아직 없는 것

- 이미지 인증 방식(0장)
- 승인 부분 승인·증빙 요청·재심사·철회 등 확장 결정
- 구매 상한 예외 승인, 프로모션·쿠폰(1차 제외)
- 센터 마스터(MFC 목록·달력)는 다음 작업(center-master-service). 그 전까지 센터 ID 는 고정값으로 입력한다.
