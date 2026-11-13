# [pmart-backend → pmart-clients] 임시 백오피스 LAN 호스팅(PC4 Nginx) 안내 (2026-10-31)

- 관련: `../backoffice/2026-10-30-clients-notice-temp-backoffice-api.md` 0장 2번(CORS 출처 `http://d603-16`)
- 백엔드가 PC4(`D603-16`) 80 포트에 Nginx(`backoffice-web`)를 띄웠다. 지금은 "빌드 없음" 안내 페이지가 나온다.

## 1. 클라이언트가 할 일
1. `pmart-clients/web/backoffice` 에서 **`npm run build:lan`**
   - API 주소는 이미 `http://D603-16:8080/api/v1`(vite.config.ts lan 모드). 목(mock) 서비스 워커가 들어가지 않는 모드인지 확인한다.
   - 결과: `web/backoffice/dist/`(index.html + assets/)
2. 빌드했다고 `docs/handoff/` 로 알려 준다(빌드 커밋 SHA 포함).

## 2. 백엔드가 할 일
- `deploy/lan/lan.sh publish-backoffice ../pmart-clients/web/backoffice/dist` — dist 를 읽어 PC4 에 올리고 Nginx 만 다시 배포한다(pmart-clients 에는 쓰지 않는다).

## 3. 접속·동작
- 브라우저 주소는 **`http://D603-16`**(호스트 이름). IP 로 열면 출처가 달라져 게이트웨이 CORS 에 막힌다.
- React 라우터 경로(새로고침 포함)는 Nginx 가 `index.html` 로 돌려준다. `assets/` 는 해시 이름이라 길게 캐시하고 `index.html` 은 캐시하지 않아 새 빌드가 바로 보인다.
- 로그인: LAN 첫 관리자 계정은 백엔드 담당에게 받는다. 시험 데이터 적재용 `seed-md`·`seed-approver` 는 seed 실행마다 비밀번호가 바뀌니 쓰지 않는다.

## 4. 게시 결과 (2026-10-31)
- 클라이언트 빌드(`web/backoffice/dist`, 2026-10-30 21:15, LAN 모드 — API `http://D603-16:8080/api/v1` 포함)를 `lan.sh publish-backoffice` 로 PC4 에 올렸다.
- 확인: `http://D603-16/` 200(새 빌드 `index-D_wk4-ZR.js`), 라우터 경로(`/products`) 200, `assets/` 1년 캐시·`index.html` no-cache, 게이트웨이 CORS 사전 요청(Origin `http://d603-16`) 200 + `Access-Control-Allow-Origin: http://d603-16`.
- 다음 빌드도 같은 방법: 빌드 후 알려 주면 백엔드가 다시 게시한다.
- 2026-10-31 재게시: 21:29 빌드(`index-yzSVis8b.js`) — `http://D603-16/` 200, 라우터 경로 200.
