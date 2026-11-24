# CDC 시뮬레이터 (백엔드 쪽 문서)

- 시뮬레이터: 저장소 `pmart-cdc-simulation`(세션 "cdc 시뮬레이션", 2026-11-24 설계 — MFC 시뮬레이터 UI·UX 를 그대로 따름, 사용자 지시). 요청서는 그 저장소 `docs/backend-requests.md`(R-01~), 회신은 `docs/handoff/simulator/cdc/`.
- 센터: **칠곡 DC**(`CDC01`) — 고정값·현장 근거·결정 CDC-1~ 은 [chilgok-dc.md](chilgok-dc.md).
- 범위(`../scope.md` S-3·S-6): **출고·피킹·상차만**. 입고·재고 조정·품질·수요예측은 넣지 않는다. CDC 재고는 시작 재고(시드).
- 근거 업무 문서: `docs/business/WMS-business-logic.md`(정본) + `docs/business/WMS-dc-wave-business-logic.md`(Wave·지역 피킹·분배·라인·보충 MOV — 2026-11-24 사용자 "근거로 삼고 넣어"). MFC 문서(`MFC-business-logic.md`)와 섞지 않는다.
- 백엔드 현황: CDC 쪽은 `cdc-inventory-service` 만 있다. 출고·피킹·상차를 맡을 `cdc-outbound`·`cdc-location` 은 없다(service-map). 이어지는 운송은 TMS(scope S-4·S-7).
- 도면: CDC 도 센터마다 다르다(ADR-0050 과 같은 원칙). 시뮬레이터 층별 도면(R-05)을 받으면 이 폴더 `layout/` 에 둔다. 위치 목록 사본 `layout/chilgok-locations.json`(2026-11-25, 32,436칸 — chilgok-dc.md CDC-7). 참고 문서(사용자 제공 구축 명세서)는 `reference/`.
