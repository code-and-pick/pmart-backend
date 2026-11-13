# handoff — 저장소 간 요청·회신

pmart-backend 와 pmart-clients 는 서로의 저장소에 쓰지 않는다. 각자 자기 `docs/handoff/` 에만 쓰고 상대 것은 읽기만 한다.

| 방향 | 위치 |
|---|---|
| 클라이언트 → 백엔드 (요청·재질의) | `pmart-clients/docs/handoff/<앱 영역>/` |
| 백엔드 → 클라이언트 (회신·공지) | `pmart-backend/docs/handoff/clients/<앱 영역>/` (이 폴더, 2026-11-09 전에는 `docs/handoff/` 바로 아래) |

## 폴더 — 받는 앱 영역 (2026-11-13 사용자 결정, 클라이언트 제안과 같은 구조)

```
docs/handoff/clients/
  customer-app/  mfc-app/  rider-app/  backoffice/  hr-backoffice/  web-common/   # 그 앱 영역이 받는 문서
  all/           # 여러 앱에 걸친 문서
  archive/       # 처리가 끝난 문서(이름 그대로) — 기준·목록 archive/README.md
```

- 각 앱 담당 PC 는 **자기 앱 폴더와 `all/` 만** 본다. 앱 영역·담당 PC 는 `docs/operations/pc-split.md` §2.
- 새 파일 이름: `<앱 영역 폴더>/YYYY-MM-DD-<종류>-<주제>.md`(종류 request·reply·notice — 폴더가 앱 영역이라 이름에 다시 넣지 않는다). 첫머리에 `보낸 영역: platform|mfc|delivery` 와(회신이면) 원 요청서 경로.
- 옛 파일은 이름 그대로 폴더만 옮겼다(2026-11-13: `…-clients-…`·`…-<앱 영역>-…` 이름이 섞여 있다).
- **처리 끝난 파일은 `archive/`**(2026-11-13 사용자 결정): 요청·결정·할 일이 양쪽 모두 끝났거나 뒤 파일이 대체했을 때 옮긴다(파일 이름 그대로, 링크는 같은 커밋에서 고친다). 앱 폴더·`all/` = 아직 할 일이 남았거나 지금도 참조하는 문서.
- 클라이언트 쪽도 같은 모양: `pmart-clients/docs/handoff/<앱 영역>/YYYY-MM-DD-backend-<주제>.md`, `all/`, `archive/`.
- 항목 번호(BR-xx)는 양쪽이 그대로 쓴다. 항목마다 수용 / 대안 / 보류와 계약 반영 커밋 SHA를 적는다.
- 계약 커밋을 push한 뒤 회신 파일을 올린다. 상대는 pull 후 읽으므로 계약과 회신이 어긋나지 않는다.
