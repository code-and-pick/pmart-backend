# 요청 — 문의 사진 첨부 (case-service 0.14.0)

보낸 영역: backoffice
받는 곳: pmart-clients 고객앱(고객센터 문의)·라이더 앱(라이더 문의)·backoffice 웹(문의 상세)
상태: case PR 대기(병합·배포는 PC 1 — LAN 버킷·설정도 PC 1). **배포 전 실서버에서는 사진 API 가 503 일 수 있다** — 문의 글은 그대로 남길 수 있다.

## 정한 것 (사용자 2026-11-17 "리뷰 방식 그대로" — 업무 원문 Commerce §14.1)
- 사진은 **먼저 한 장씩 올리고**, 돌려받은 `photoId` 를 문의에 붙인다. 문의 하나에 **5장**, 장당 **10MB**, JPEG·PNG·WEBP(파일 앞부분으로 판정).
- 붙이는 때: 문의를 남길 때(`photoIds`) 또는 **남긴 뒤 보완**(원문 "접수는 됐지만 첨부가 실패하면 원접수에 보완 — 새 접수를 만들지 않는다"). 닫힌 문의에는 못 붙인다.
- 보기: 남긴 본인, 그리고 그 문의를 볼 수 있는 직원(고객 문의 = 상담원·관리자, 라이더 문의 = 지원센터·관리자). 24시간 안에 붙지 않은 사진은 지워진다.
- 첨부 성공과 문의 접수 성공은 다르다 — 사진 올리기가 실패해도 문의는 남길 수 있게(원문 "첨부만 성공한 상태를 접수 완료로 표시하지 않는다").

## API
- 올리기 `POST /api/v1/inquiry-photos` — `multipart/form-data` 의 `file`, Idempotency-Key·X-Source-System. 고객·라이더 토큰만.
  → 201 `{ photoId, url, contentType, sizeBytes, uploadedAt }`. 422 `PHOTO_INVALID`(형식·크기), 503(저장소 없음·연결 불가).
- 문의 남기기 `POST /api/v1/inquiries` 에 `photoIds: [..]`(선택, 5개까지). 남의·이미 붙은 사진이면 422 `PHOTO_NOT_FOUND` — **문의도 남지 않는다**(사진을 빼고 다시 보내거나 새로 올린다).
- 보완 `POST /api/v1/inquiries/{inquiryId}/photos` `{ photoIds: [..] }` → 200 문의(상세). 합쳐 5장 넘으면 400, 닫힌 문의 409 `INQUIRY_CLOSED`, 남의 문의 404. 상태·version 은 바뀌지 않는다.
- 문의 상세(`GET /inquiries/{id}` 와 위 응답)에 `photos: [{ photoId, url, contentType, sizeBytes, uploadedAt }]`(붙인 순서).
- 본문 `GET /api/v1/inquiry-photos/{photoId}` — **토큰 필요**(리뷰 사진과 달리 공개가 아님). `<img src>` 로 바로 못 쓰니 토큰을 붙여 받아 blob URL 로 보여 준다. 못 보는 사진은 404, 문의를 볼 역할이 없는 직원은 403.

## 화면 제안
- 고객앱 문의 쓰기: 사진 추가(최대 5장, 미리보기) — 올리기 실패한 사진은 그 칸에 "다시 올리기", 문의 보내기는 막지 않는다. 보낸 뒤 문의 상세에서 "사진 추가"(닫힌 문의는 숨김).
- 라이더 앱 문의(정산·계정·앱 오류): 같은 방식(앱 오류 화면 캡처).
- 백오피스 문의 상세(상담원·지원센터·관리자): 사진 썸네일·크게 보기. 상담원이 남긴 문의(STAFF_INTAKE)에는 사진이 없다.
