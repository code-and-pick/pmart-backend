"""LAN 확인 — 백오피스 bff 화면(/backoffice/views/*·/commands 결과 조회)을 **읽기만** 해서 상태 코드·권한·응답 모양을 본다(2026-11-18, PC 2).

쓰기 없음: GET 만 부른다. 계정 역할·비밀번호를 바꾸지 않는다(staff_auth.staff_token 에 roles 를 주지 않음).
토큰·비밀번호·주소·이름 같은 값은 출력하지 않는다 — 상태 코드와 건수만.
배포 전 기능(404·501)은 "배포 전"이라 적고 넘어간다(실패로 세지 않는다). 계약상 501 인 화면(보충 현황·운행 관제·CASE_SLA 밖 지표)은 501 이 정상.

  A. 관리자(claude-dev, OPERATOR_ADMIN)   처리 대기·사건함·통합 조회·실시간 배송·포장 인계·승인함 200, 조건 오류 400·422
  B. 지표(bff 0.13.0·0.21.0)              CASE_SLA 200(분모 0 → ratio null, unjudged 칸), 93일 넘는 기간 400, 정시 배송률 200(배포 전이면 501 — 배포 전으로 셈), 간선 정시율 501
  C. 명령 결과 조회(bff 0.12.0)           targetRef 없음 400, handover-retry 422
  D. 아직 없는 화면                       보충 현황·운행 관제 501
  E. (--cs) 상담원 계정                  토큰의 역할을 읽어 CS_AGENT 이고 관리자가 아니면 처리 대기·지표 403, 사건함·통합 조회 200
  F. 2026-11-23 기능(관리자)              센터 거르기(bff 0.24.0·0.25.0 — claims·pending-work·work-counts·rider-complaints centerId, centerUnfilteredSources),
                                         case 0.33.0 rider-complaints/counts·obligations centerId, case 0.36.0 문의 lastAnsweredBy,
                                         고객 상세(bff 0.25.0~0.28.0 — deliveries·claimCounts·piiAccessLogs·customerNotes·문의 담당 이름·restriction)
                                         고객은 클레임 → 주문 상세의 고객 ID 로 하나 고른다(값은 출력하지 않음). 그 단계 원천이 비면 건너뜀.

실행: python -X utf8 -I deploy/lan/checks/backoffice_views_check.py [--cs]   (Windows 콘솔은 -X utf8 가 없으면 cp949 로 한글·— 출력이 깨진다)
  2026-11-18 첫 실행(PC 2, 관리자만): 15/15 통과 — CASE_SLA 200(LAN 배포 확인), 포장 인계·처리 대기·사건함 0건
  2026-11-23 F 절 더함(PC 2) — 같은 날 수동 확인으로 모두 200(센터 거르기·고객 상세 여덟 칸·restriction NONE·담당 이름).
  --cs: 상담원 시험 계정(test-cs-rider@pmart.com)도 — PC 1 에서 받은 최신 비밀번호 파일이 있을 때만(옛 값으로 여러 번 실패하면 계정이 잠긴다).
필요: deploy/lan/.secrets/keycloak-claude-dev-password(·--cs 면 keycloak-test-cs-rider@pmart.com-password).
환경 변수: PMART_GATEWAY(기본 http://192.168.0.40:8080/api/v1), PMART_MFC(기본 MFC-SEED-01).
"""
import base64
import datetime
import json
import os
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from staff_auth import staff_token  # noqa: E402

GW = os.environ.get("PMART_GATEWAY", "http://192.168.0.40:8080/api/v1")
MFC = os.environ.get("PMART_MFC", "MFC-SEED-01")
BO = "/backoffice"
CS_USER = "test-cs-rider@pmart.com"
results = []


def get(path, token):
    req = urllib.request.Request(
        GW + BO + path,
        method="GET",
        headers={"Accept": "application/json, application/problem+json", "Authorization": "Bearer " + token},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        text = e.read().decode() or "{}"
        try:
            return e.code, json.loads(text)
        except ValueError:
            return e.code, {}


def get_api(path, token):
    """bff 밖 원천(case·claim·order)을 게이트웨이로 — F 절. 429 를 피하려고 조금 쉰다."""
    time.sleep(0.5)
    req = urllib.request.Request(
        GW + path,
        method="GET",
        headers={"Accept": "application/json, application/problem+json", "Authorization": "Bearer " + token},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        text = e.read().decode() or "{}"
        try:
            return e.code, json.loads(text)
        except ValueError:
            return e.code, {}


def check(name, ok, detail=""):
    results.append((name, ok))
    print(("  OK   " if ok else "  FAIL ") + name + (f" — {detail}" if detail else ""), flush=True)


def not_deployed(name, status):
    if status in (404, 501):
        print(f"  --   {name} — 배포 전(HTTP {status})")
        return True
    return False


def roles_of(token):
    """토큰 본문의 역할만 읽는다(서명 검증 없음 — 시험 분기용, 값은 출력하지 않음)."""
    try:
        body = token.split(".")[1]
        claims = json.loads(base64.urlsafe_b64decode(body + "=" * (-len(body) % 4)))
    except (IndexError, ValueError):
        return set()
    r = claims.get("roles") or claims.get("realm_access", {}).get("roles") or []
    return set(r)


def token_of(username):
    t = staff_token(username)
    return t[0] if isinstance(t, tuple) else t


def count(body, key):
    v = body.get(key)
    return len(v) if isinstance(v, list) else "-"


def admin_views(admin):
    s, b = get("/views/pending-work", admin)
    check("A 처리 대기 200", s == 200 and isinstance(b.get("items"), list), f"HTTP {s}, 항목 {count(b, 'items')}, 원천 장애 {b.get('unavailableSources')}")
    s, b = get("/views/case-inbox?size=5", admin)
    check("A 사건함 200", s == 200 and isinstance(b.get("items"), list), f"HTTP {s}, 항목 {count(b, 'items')}, 원천 장애 {b.get('unavailableSources')}")
    s, b = get("/views/unified-lookup?q=ORD-CLAUDE-NONE", admin)
    check("A 통합 조회(없는 주문) 200", s == 200, f"HTTP {s}, 원천 장애 {b.get('unavailableSources')}")
    s, b = get("/views/unified-lookup?q=TRIP-1", admin)
    check("A 통합 조회(운행 번호) 422 UNSUPPORTED_LOOKUP", s == 422 and b.get("code") == "UNSUPPORTED_LOOKUP", f"HTTP {s} {b.get('code')}")
    s, b = get(f"/views/live-delivery?mfcId={MFC}&size=5", admin)
    check("A 실시간 배송 200", s == 200 and isinstance(b.get("items"), list), f"HTTP {s}, 항목 {count(b, 'items')}, 원천 장애 {b.get('unavailableSources')}")
    s, b = get(f"/views/packing-handover?mfcId={MFC}&size=50", admin)
    if not not_deployed("A 포장 인계", s):
        check("A 포장 인계 200", s == 200 and isinstance(b.get("items"), list), f"HTTP {s}, 항목 {count(b, 'items')}, 원천 장애 {b.get('unavailableSources')}")
        stages = sorted({i.get("stage") or "null" for i in b.get("items", [])})
        if stages:
            print(f"  ..   포장 인계 단계: {', '.join(stages)}")
        s, _ = get("/views/packing-handover", admin)
        check("A 포장 인계 — 본사는 mfcId 필수 400", s == 400, f"HTTP {s}")
    s, b = get("/views/approval-inbox?size=5", admin)
    check("A 승인함 200", s == 200, f"HTTP {s}, 원천 장애 {b.get('unavailableSources')}")


def metrics(admin):
    today = datetime.date.today()
    week = (today - datetime.timedelta(days=6)).isoformat()
    s, b = get(f"/views/metrics?metric=CASE_SLA&from={week}&to={today.isoformat()}", admin)
    if not_deployed("B 지표 CASE_SLA", s):
        return
    points = b.get("points", [])
    shapes_ok = all(("unjudged" in p and (p.get("denominator", 0) > 0 or p.get("ratio") is None)) for p in points)
    check("B 지표 CASE_SLA 200", s == 200 and b.get("timeZone") == "Asia/Seoul" and shapes_ok,
          f"HTTP {s}, 줄 {len(points)}, 묶음 {count(b, 'totals')}, 지금 기한 지남 {'모름' if b.get('currentOverdue') is None else sum(c.get('overdue', 0) for c in b['currentOverdue'])}")
    long_from = (today - datetime.timedelta(days=100)).isoformat()
    s, _ = get(f"/views/metrics?metric=CASE_SLA&from={long_from}&to={today.isoformat()}", admin)
    check("B 지표 — 93일 넘는 기간 400", s == 400, f"HTTP {s}")
    s, b = get(f"/views/metrics?metric=ON_TIME_DELIVERY&from={week}&to={today.isoformat()}", admin)
    if not not_deployed("B 지표 — 정시 배송률", s):
        check("B 지표 — 정시 배송률 200(bff 0.21.0)", s == 200 and b.get("metric") == "ON_TIME_DELIVERY", f"HTTP {s}, 줄 {len(b.get('points', []))}")
    s, b = get(f"/views/metrics?metric=TRANSPORT_ON_TIME&from={week}&to={today.isoformat()}", admin)
    check("B 지표 — 간선 정시율 501(원천 없음)", s == 501, f"HTTP {s} {b.get('code')}")


def command_result(admin):
    s, _ = get("/commands/order-orchestration-service/saga-requery/req-claude-none", admin)
    check("C 명령 결과 — targetRef 없음 400", s == 400, f"HTTP {s}")
    s, b = get("/commands/mfc-fulfillment-service/handover-retry/req-claude-none?targetRef=HO-NONE", admin)
    check("C 명령 결과 — 인계 재시도 422", s == 422 and b.get("code") == "UNSUPPORTED_ACTION", f"HTTP {s} {b.get('code')}")


def not_built(admin):
    s, _ = get(f"/views/replenishment-status?mfcId={MFC}", admin)
    check("D 보충 현황 501(원천 서비스 없음)", s == 501, f"HTTP {s}")
    s, _ = get(f"/views/trip-control?date={datetime.date.today().isoformat()}", admin)
    check("D 운행 관제 501(원천 서비스 없음)", s == 501, f"HTTP {s}")


def features_1123(admin):
    time.sleep(0.5)
    s, b = get(f"/views/claims?centerId={MFC}&size=5", admin)
    check("F 클레임 센터 거르기 200(bff 0.24.0)", s == 200 and isinstance(b.get("items"), list), f"HTTP {s}, 항목 {count(b, 'items')}")
    for name, path in (("처리 대기", "/views/pending-work"), ("일 건수", "/views/work-counts")):
        time.sleep(0.5)
        s, b = get(f"{path}?centerId={MFC}", admin)
        check(f"F {name} 센터 거르기 200 + centerUnfilteredSources", s == 200 and isinstance(b.get("centerUnfilteredSources"), list),
              f"HTTP {s}, 못 거른 원천 {b.get('centerUnfilteredSources')}")
    time.sleep(0.5)
    s, b = get(f"/views/rider-complaints?centerId={MFC}&size=5", admin)
    check("F 라이더 컴플레인 센터 거르기 200(bff 0.25.0)", s == 200 and isinstance(b.get("items"), list), f"HTTP {s}, 항목 {count(b, 'items')}")
    s, _ = get_api(f"/rider-complaints/counts?centerId={MFC}", admin)
    check("F case 라이더 컴플레인 수 센터 200(case 0.33.0)", s == 200, f"HTTP {s}")
    s, b = get_api(f"/obligations?centerId={MFC}", admin)
    check("F case 후속 처리 센터 200(case 0.33.0)", s == 200 and isinstance(b.get("items"), list), f"HTTP {s}, 항목 {count(b, 'items')}")
    s, b = get_api("/inquiries?requesterType=CUSTOMER&size=20", admin)
    answered = [i for i in b.get("items", []) if i.get("answeredAt")]
    if answered:
        check("F case 문의 lastAnsweredBy(case 0.36.0)", all(i.get("lastAnsweredBy") for i in answered), f"답변된 {len(answered)}건")
    else:
        print("  --   F case 문의 lastAnsweredBy — 답변된 고객 문의 없음(건너뜀)")
    # 고객 하나: 클레임 → 주문 상세의 고객 ID(값 출력 없음)
    _, c = get_api("/claims?size=1", admin)
    order_id = ((c.get("items") or [{}])[0]).get("orderId")
    if not order_id:
        print("  --   F 고객 상세 — 클레임이 없어 고객을 못 고름(건너뜀)")
        return
    _, o = get_api(f"/orders/lookups/{order_id}", admin)
    cust = o.get("customerId")
    if not cust:
        print("  --   F 고객 상세 — 주문에 고객 ID 없음(건너뜀)")
        return
    time.sleep(0.5)
    s, d = get(f"/views/customer/{cust}", admin)
    cells = ("orders", "inquiries", "csIntakes", "claims", "deliveries", "claimCounts", "piiAccessLogs", "customerNotes")
    missing = [k for k in cells if d.get(k) is None]
    check("F 고객 상세 200 + 여덟 칸(관리자)", s == 200 and not missing,
          f"HTTP {s}, 빈 칸 {missing or '없음'}, 원천 장애 {d.get('unavailableSources')}")
    head = d.get("customer", {})
    check("F 고객 상세 restriction 칸(bff 0.28.0)", "restriction" in head and "restrictedUntil" in head, f"값 {head.get('restriction')}")
    inq = (d.get("inquiries") or {}).get("items", [])
    if any(i.get("lastAnsweredBy") for i in inq):
        check("F 고객 상세 문의 담당 이름(bff 0.27.0)", all(i.get("lastAnsweredByLabel") for i in inq if i.get("lastAnsweredBy")), f"문의 {len(inq)}건")
    else:
        print("  --   F 고객 상세 문의 담당 이름 — 답한 문의 없음(건너뜀)")


def cs_agent():
    secret = os.path.join(HERE, "..", ".secrets", f"keycloak-{CS_USER}-password")
    if not os.path.exists(secret):
        sys.exit(f"--cs: keycloak-{CS_USER}-password 파일이 없음 — PC 1 에서 받은 뒤 다시(파일 없이 돌리면 비밀번호가 바뀜)")
    cs = token_of(CS_USER)
    roles = roles_of(cs)
    if "CS_AGENT" not in roles or "OPERATOR_ADMIN" in roles:
        print(f"  --   E 상담원 확인 건너뜀 — 이 계정 역할이 상담원 전용이 아님({', '.join(sorted(roles)) or '읽지 못함'})")
        return
    s, _ = get("/views/pending-work", cs)
    check("E 상담원 — 처리 대기 403", s == 403, f"HTTP {s}")
    today = datetime.date.today().isoformat()
    s, _ = get(f"/views/metrics?metric=CASE_SLA&from={today}&to={today}", cs)
    if not not_deployed("E 상담원 — 지표", s):
        check("E 상담원 — 지표 403", s == 403, f"HTTP {s}")
    s, _ = get("/views/case-inbox?size=5", cs)
    check("E 상담원 — 사건함 200", s == 200, f"HTTP {s}")
    s, _ = get("/views/unified-lookup?q=ORD-CLAUDE-NONE", cs)
    check("E 상담원 — 통합 조회 200", s == 200, f"HTTP {s}")


def main():
    admin = token_of("claude-dev")
    admin_views(admin)
    metrics(admin)
    command_result(admin)
    not_built(admin)
    features_1123(admin)
    if "--cs" in sys.argv:
        cs_agent()
    else:
        print("  --   상담원 403 확인 건너뜀(--cs 로 켬 — PC 1 에서 받은 비밀번호 파일이 있을 때만)")
    bad = [n for n, ok in results if not ok]
    print(f"\n결과: {len(results) - len(bad)}/{len(results)} 통과" + (" — 실패: " + ", ".join(bad) if bad else ""))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
