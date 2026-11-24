# 라이더백엔드(PC 5) 세션 인수인계 — 2026-11-21

다음 Claude 세션이 이 파일 하나만 읽고 바로 이어서 일할 수 있게 정리했다. 영역 전체 안내는 `docs/delivery/HANDOVER.md`, 서비스별 상태는 `services/<서비스>/CLAUDE.md`, 날짜별 기록은 `docs/worklog/delivery/` 가 정본이다.

---

## 1. 이 세션의 역할과 규칙

- 이름: **라이더백엔드**(PC 5, delivery 영역). 맡는 서비스는 rider-service, rider-auth-service, delivery-dispatch, delivery-execution, delivery-tracking, serviceability, capacity.
- **늘 한국어로 답한다.**
- 사용자 지시: **"고칠 때 필요한 건 그 세션에 바로 보내"**. 다른 세션 일이면 직접 메시지를 보낸다. PR 처리 세션에 보낼 때는 "사용자 지시"와 사용자 원문을 함께 적는다.
- 금지 사항(사용자 원문): "금지: 명시 요청 없는 원격 배포·운영 DB 변경·Slack, `.secrets` 값 읽기/전송, 로그에 개인정보·토큰."
  - PC 5 는 main 에 push 하지 않고, 배포하지 않고, pmart-config 를 고치지 않는다. 이 일은 PC 1 이 한다.
  - 반납 인증번호와 인계 코드는 로그에 남기지 않는다.
  - 실행하지 않은 시험이나 배포를 끝났다고 보고하지 않는다.
  - 브라우저에서 직원 비밀번호를 입력하지 않는다. 로그인은 사용자가 직접 한다.
  - 설정 파일의 `{cipher}` 값은 출력하지 않는다. rider-service-lan.yml 을 grep 할 때 특히 조심한다.

## 2. 일하는 방법

### 백엔드 (이 저장소)
1. `git pull --ff-only origin main` 를 한 뒤 `delivery/<주제>` 브랜치를 만든다.
2. 계약(`contracts/openapi/*.yaml`, `contracts/events/*.json`)을 먼저 고치고 코드를 맞춘다.
3. `./gradlew.bat :services:<svc>:spotlessApply` 를 돌린 다음 `:services:<svc>:check` 를 돌린다. 오래 걸리면 백그라운드로 돌린다.
4. `git push fork <브랜치>` 로 올린다. fork 는 `piaa1004/pmart-backend-pc5` 이다.
5. `"/c/Program Files/GitHub CLI/gh.exe" pr create -R code-and-pick/pmart-backend --head piaa1004:<브랜치>` 로 PR 을 만든다.
   - 본문에는 바꾼 것, 실행한 시험, 영향 영역, 공용 표 갱신 필요 여부를 적는다.
6. **`PR 처리용 백엔드((((((((((((`** 세션에 SendMessage 로 병합과 배포를 요청한다.
7. 병합되면 `git checkout main && git pull` 을 하고, 요청한 세션에 배포됐다고 알린다.
- 일지는 `docs/worklog/delivery/YYYY-MM-DD.md` 에 쓴다. 서비스 상태가 바뀌면 그 서비스의 `CLAUDE.md` `## 상태` 끝에 한 줄을 더한다.

### 클라이언트 (pmart-clients — 라이더 백오피스 웹이 PC 5 몫)
- 작업은 worktree 에서 한다.
  - `git worktree add ../pmart-clients-wr` 로 만든다.
  - node_modules 는 PowerShell 에서 `cmd /c mklink /J` 로 정션을 만든다. bash 에서 하면 경로가 깨진다.
  - 끝나면 `cmd /c rmdir` 로 정션을 끊고 worktree 를 지운다.
- `npm run gen:api` 는 `../pmart-backend/contracts/openapi` 를 읽는다. 백엔드 브랜치 계약이 필요하면 그 브랜치를 잠시 체크아웃해서 생성한다.
- **`contracts.lock` 은 커밋하지 않는다.** PC 1 몫이다.
- 파일 줄 끝이 CRLF 다. 여러 줄을 perl 로 고치기 전에 `sed 's/\r$//'` 로 LF 로 바꾼다.
- PR 은 remote `ghfork`(piaa1004/pmart-clients-fork)에 올리고 code-and-pick/pmart-clients 로 연다.
  - 병합과 배포는 **`PR 처리용 클라이언트(((((((((`** 세션에 요청한다.
- 시험은 `npm run typecheck`, `lint`, `vitest` 를 돌린다. worktree 에서 `vite build` 는 pretendard CSS 정션 문제로 실패한다. 이건 환경 문제이고 PR 세션 빌드는 통과한다.

### 다른 세션 (ListAgents 로 확인)
| 이름 | 하는 일 |
|---|---|
| `PR 처리용 백엔드((((((((((((` | 백엔드 PR 병합·LAN 배포(PC 1, 원격 연결) |
| `PR 처리용 클라이언트(((((((((` | 클라이언트 PR 병합·배포 |
| `PC1 백엔드` | notification·promotion 등 플랫폼 |
| `프론트 백오피스` | PC 2 통합 백오피스 |
| `라이더앱` | PC 5 라이더 앱 및 라이더 백오피스 웹 작업 세션 |

### LAN
- 라이더 백오피스: https://192.168.0.40:8446. 직원 로그인은 사용자가 한다.
- 컨테이너 이름은 `pmart-lan-<서비스>-1` 이다(예: `pmart-lan-rider-service-1`, `pmart-lan-rider-auth-service-1`).
- 로그 예시: `docker logs --since 1h pmart-lan-rider-auth-service-1 2>&1 | grep '라이더 로그인'`. 개인정보는 출력하지 않는다.

---

## 3. 2026-11-21 에 끝낸 일 (전부 병합·배포됨)

| 무엇 | PR·커밋 | 상태 |
|---|---|---|
| dispatch CLAUDE.md 쿠폰 문구 정리(promotion 이 줌) | 백엔드 #317 fa858e70 | 병합 |
| **탈퇴 라이더 기록 보기** — rider 0.20.0 | 백엔드 #319 30bbc2fd | LAN 배포 |
| RiderFlowIT 흔들림 고침(무작위 UUID 안의 "010") | 백엔드 #321 27b853ac | 병합(배포 필요 없음) |
| 라이더 백오피스 탈퇴 필터·탈퇴 라이더 상세 | 클라이언트 #350 6a857dbb | 8446 배포 |
| 통합 백오피스 배차 불가 취소 쿠폰 안내 | 클라이언트 #349 87dd4674(PC 2) | 배포 |
| 지연·배차 불가 알림 문구 | PC1 0bee3cfd | 배포 |
| **직원 배송 목록 단계 시각** — execution 0.34.0 | 백엔드 #324 bb0f47d2 | LAN 배포 |

### 3-1. 탈퇴 라이더 기록 보기 (#319, #350)
- 사용자 요청 원문: "탈퇴한 라이더의 정보는 따로 모아볼 수 있게 필터를 만들던가 해야겠네"
- 백엔드(rider-service 0.20.0):
  - `GET /riders?withdrawn=true` 를 추가했다. `RiderStore.Search.Scope` 는 ACTIVE·WITHDRAWN·ALL 이고 기본은 ACTIVE 다.
  - `GET /riders/{id}?includeWithdrawn=true` 를 추가했다. 처리는 `StaffRiderService.detail(id, includeWithdrawn)` 와 `RiderStore.findIncludingWithdrawn` 이 한다.
  - 목록과 상세에 `withdrawnAt` 을, display-names 에 `withdrawn` 을 넣었다.
  - 제한하기와 원문 열람은 계속 탈퇴한 라이더를 찾지 못한다.
- 클라이언트(`web/rider-backoffice/src/features/riders/RiderPages.tsx`):
  - 라이더 목록에 "가입 상태" 칩(활동 중 / 탈퇴함)을 넣었다.
  - 탈퇴한 라이더 상세에서는 경고 안내를 띄우고, 리뷰·제한 구역과 열람 폼을 숨긴다.
  - 탈퇴 신청 목록(`WithdrawalsPage.tsx`)의 줄을 누르면 라이더 상세로 간다.

### 3-2. 직원 배송 목록 단계 시각 (#324)
- 요청 원문: 라이더앱 세션이 보냈고, 사용자가 "응 넣어" 라고 했다. 요청서는 pmart-clients `docs/handoff/rider-backoffice/2026-11-21-backend-staff-delivery-times.md` 이다.
- execution `StaffDeliverySummary` 에 추가한 칸:
  - `firstAssignedAt`, `arrivedAtStoreAt`, `arrivedAtCustomerAt`: `execution_history` 의 첫 기록이다. `ExecutionStore.stageTimes` 가 목록마다 한 번 묶어서 읽는다.
  - `promisedAt`: V26 `promised_at` 열의 값이다.
  - `completionMethod`: COMPLETED 일 때만 `method` 값이 들어간다.
- dispatch 는 `DeliveryAssigned` 에 `promisedAt` 을 싣는다. 필드 추가만이라 호환된다. **이번 배포 뒤 새로 배정된 배송부터만 값이 있다.**
- 시험: execution 은 단위 14·통합 36, dispatch 는 단위 18·통합 53 이 통과했다.
- 라이더앱 세션이 지금 라이더 백오피스 대시보드에 붙이고 있다. 다섯 구간 평균, 늦은 배송 표시, CUSTOMER_CONFIRMED·AUTO_COMPLETED 평균 제외가 들어간다. 실서버 확인이 끝나면 그 세션이 알려 온다.

### 3-3. 쿠폰·알림 정책 (사용자 정정 뒤 확정)
- 사용자 원문: "고객앱에선 조금 지연되면 그냥 지연되서 죄송하다고 취소시 전액환불 가능하다는 알람을 줘야지 그리고 배차불가나 너무 지연될 것 같으면 자동취소하고 2000원 쿠폰 지급했단 알림을 띄워야지"
- 조금 늦어질 때: 사과와 "취소하면 전액 환불" 안내만 보낸다. 쿠폰은 없다.
- 배차 불가 자동 취소(약속 + 30분, dispatch `autoCancelNoRider`): 취소 안내와 함께 쿠폰 2,000원을 준다.
  - promotion 이 `OrderCancelled`(cancelReason DISPATCH_UNAVAILABLE)를 받아 주문마다 한 번 준다. 유효 30일, 최소 금액 없음.
  - **LAN 보상 이벤트 승인이 아직이다.** PC1 이 사용자에게 물어봤다.
- 라이더 앱은 고칠 것이 없다. 쿠폰은 고객 쪽 기능이다.

### 3-4. 탈퇴 처리 — 사용자에게 설명한 내용
- rider-service: 이름·전화·계좌·주소·면허를 지우고 `withdrawn_at` 을 남긴다. 행과 `rider_number` 는 남는다.
  - c2bcc641 V13 `rider_withdrawal_hold`: 재가입을 막는 기간 동안 이름·전화를 보관하고, 기한이 지나면 `WithdrawalHoldPurger` 가 지운다.
- rider-auth: `rider_identity` 를 지우고 옛 riderId 만 막는다. 같은 카카오·구글로 다시 로그인하면 새 subject 가 된다.
- 재가입 막는 기간: LAN 5분(`rejoin-block: PT5M`), 운영 기본 30일.
- 수행료(execution)와 상담 기록(case-service)은 riderId 기준으로 남는다. 지우지 않는다.
- 탈퇴를 막는 것: ACTIVE_DELIVERY, UNCONFIRMED_EARNING, EARNING_ADJUSTMENT_PENDING, OPEN_HELP_REQUEST, RESTRICTION_REVIEW.
- 5분 뒤 재가입 검토 결과는 "문제없음" 이다. 새 subject 가 되고, 유일 제약도 충돌하지 않으며, 인증 하루 10회 제한은 subject 마다 센다. 다만 지원서 제출과 승인을 다시 받아야 하고, 옛 라이더 번호와 수행료는 이어지지 않는다.

---

## 4. 이어서 할 일

### 바로 할 것
1. **사용자 본인 계정 탈퇴 시험**: 사용자가 라이더앱에서 탈퇴한 시각을 알려 주면 확인한다.
   - rider-service와 rider-auth 로그로 탈퇴가 완료됐는지 본다. 개인정보는 출력하지 않는다.
   - 백오피스 "라이더 > 탈퇴" 와 "가입 상태: 탈퇴함" 에 보이는지 본다.
   - 5분 뒤 재가입이 되는지(새 로그인, 지원서) 확인한다.
2. **캡처**: 사용자가 8446 에 로그인했다고 하면 "라이더 > 탈퇴" 화면과 탈퇴한 라이더 상세를 캡처해 SendUserFile 로 보낸다.
   - Chrome 의 claude-in-chrome 탭은 세션마다 새로 만든다. 먼저 `tabs_context_mcp` 를 부른다.
3. **라이더앱 세션 대시보드 확인 알림**을 기다린다. 문제가 오면 execution 0.34.0 쪽을 본다.

### 사용자에게 물어 둔 것 (답 없음)
- 라이더 상세에 도움요청·문의 기록도 넣을지.

### 정해지지 않은 것
- 운영에서 탈퇴 뒤 수행료·상담 기록을 얼마나 보관하고 언제 지울지. 제안은 정산 5년, 상담 3년이고 법률 확인이 필요하다.
- 운영의 재가입 막는 기간과 그에 맞는 동의 문구. 운영 기본은 아직 30일이다.
- 탈퇴할 때 카카오·네이버 연결 끊기 API. 카카오는 auth 289a989a 에서 고객 쪽에 들어갔고, 라이더 쪽은 확인이 필요하다.
- 쿠폰 이벤트 LAN 승인. 사용자가 결정한다.

### 전부터 이어진 것
- 네이버·카카오 실기기 로그인 확인.
- 실제 반납 확인.
- 한집 주문 4건(12299418, 26921025, 60076499, 50800021)의 첫 배차 불가 자동 취소 로그 확인. dispatch 로그에서 "배차 불가 자동 취소 요청" 을 grep 한다.

---

## 5. 지금 상태 스냅샷
- 백엔드 저장소: `main` 이 origin 과 같다(7c76448a, #324 일지). 열린 PC 5 백엔드 PR 은 없다.
- 클라이언트 worktree `pmart-clients-wr` 는 지웠다(정션 해제함).
- 계약 버전: rider-service 0.20.0, delivery-execution 0.34.0.
- 이 파일은 커밋하지 않았다. 남기려면 `delivery/<주제>` PR 로 올린다.

## 6. 빠지기 쉬운 것
- PowerShell 과 Bash 가 둘 다 있다. 경로가 깨지면 PowerShell 을 쓴다. Python 은 없다(`python` 은 스토어 별칭).
- 긴 jsonl 을 `grep -o '.{0,1500}'` 같은 정규식으로 읽으면 멈춘다. PowerShell `ConvertFrom-Json` 을 쓴다.
- 계약의 nullable enum 은 `type: [string, 'null']` 과 `enum: [..., null]` 로 쓴다(case-service 에 선례가 있다).
- 사용자가 전에 준 MFC 지점 26곳의 이름·주소는 pmart-clients `apps/rider_app/lib/shared/mfc_directory.dart` 65~303줄에 있다. LAN 시드는 `deploy/lan/seed/seed-data.json` 이다.
