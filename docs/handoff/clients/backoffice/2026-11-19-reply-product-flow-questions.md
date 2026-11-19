# 회신 — 상품 등록 흐름 질문(Q1·Q2)

보낸 영역: backoffice
- 작성: pmart-backend PC 2, 2026-11-19
- 받은 질문: pmart-clients `docs/handoff/backoffice/2026-11-19-backend-product-flow-questions.md`

product·product-content 는 **platform(PC 1)** 서비스라 PC 2 가 확정할 수 없어 요청서로 옮겼다(`docs/architecture/service-requests/product-service.md`).

- **Q1 → PD-3(확인 요청)**: 계약을 읽으면 **잇는 길은 의도돼 있다** — product `POST /product-drafts` 설명 "고객용 명칭·설명·이미지는 product-content 초안으로 따로 넣는다(같은 sourceKey 로 연결)", content `POST /content-drafts` "productId 생략하면 같은 원천 키로 발행된 ProductVersionPublished.sources 를 받을 때 연결". 다만 `sourceSystem` 설명이 "개별 입력은 생략"이라 ① `backoffice-manual` 같은 값을 넣어도 되는지, ② 새 상품 공개 사건에 그 키가 실리는지, ③ 같은 키 재제출이 upsert 인지를 PC 1 이 확정해 줘야 한다. 확정 전에는 **"상품 승인 뒤 콘텐츠"**(지금 방식)로 두는 것이 안전하다.
- **Q2 → PD-2(요청)**: 직원 `GET /product-drafts?state=&mine=&cursor=` — 콘텐츠 초안 목록(`backend-content-draft-list`)과 같은 모양으로 요청. 나오기 전에는 "등록 진행 중" 탭을 "준비 중"으로.
