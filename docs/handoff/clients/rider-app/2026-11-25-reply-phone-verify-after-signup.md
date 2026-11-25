보낸 영역: delivery(PC 5)
받는 앱: rider-app

# 회신: 가입한 라이더의 휴대폰 번호 확인 팝업 (2026-11-25)

사용자 결정 "전화번호 인증이 뒤늦게 생겨서 이미 라이더앱에 가입했는데 전화번호 인증 안 한 계정은 전화번호 인증하라고 팝업 띄워줘" — rider-service 0.28.0(PR 병합·LAN 배포 전).

## P-1. `GET /riders/me` → `RiderProfile.phoneVerified: boolean`
- false 면 팝업. 예전 서버(필드 없음)면 띄우지 않는다는 앱 규칙 그대로.
- LAN 2026-11-25: 탈퇴 안 한 라이더 14명 중 13명이 false.
- 직원 상세(`GET /riders/{id}` StaffRiderDetail)에도 같은 값.

## P-2. 번호 확인 붙이기
1. `POST /riders/me/phone-verifications {phone}` → `…/{id}/confirmations {phone}` — 가입 때와 **같은 흐름**(가입한 라이더도 그대로 쓴다). 본인 지금 번호도 된다.
2. VERIFIED 가 되면 **1시간 안에** `PATCH /riders/me { phone, phoneVerificationId }` — 둘은 **같이**(하나만 오면 400 VALIDATION_FAILED).
   - 200 → `phoneVerified: true`, 번호가 바뀌었으면 `phoneMasked` 도 새 번호.
   - 422 `PHONE_NOT_VERIFIED` — 확인 안 됨·만료·다른 번호·다른 주체·확인 뒤 1시간 지남.
   - 409 `IDENTITY_ALREADY_REGISTERED`(그 번호로 가입한 다른 라이더 — details provider·emailMasked·joinedAt, 가입과 같음) / 409 `RIDER_REJOIN_BLOCKED`(탈퇴 번호 막힘 기간, details until).
   - 운행 중이어도 된다. 다른 프로필 항목과 같이 보내도 되지만, 따로 보내는 것을 권한다(다른 항목 거절에 같이 막히지 않게).
