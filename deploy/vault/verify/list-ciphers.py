"""pmart-config 의 *-lan.yml 에서 {cipher} 값을 뽑는다.
사용: python -I list-ciphers.py <pmart-config 폴더> [--with-list-siblings]
  기본 출력:  "서비스|속성 TAB 암호문"                       (verify-cipher.sh)
  --with-list-siblings: "서비스|속성 TAB cipher|plain TAB 값"  (migrate-cipher.sh)
    {cipher} 가 든 목록 항목(예: signing.keys[0])은 같은 항목의 일반 값(kid 등)도 plain 으로 낸다.
    Spring 은 목록을 우선순위가 높은 설정 출처 하나에서 통째로 받는다 — Vault 에 private-key 만 두면 Git 의 kid 가 사라진다.
속성 이름은 Spring 형식(목록은 [번호]). 여러 줄 따옴표 문자열·블록 문자열(| >)은 건너뛴다.
값은 화면에 내지 말고 파이프로만 넘긴다(plain 값은 비밀이 아닌 kid 등)."""
import glob
import os
import re
import sys

root = sys.argv[1]
siblings = "--with-list-siblings" in sys.argv[2:]
KEY = re.compile(r"^(\s*)(- )?([A-Za-z0-9_.\-]+):(?:\s+(.*))?$")


def unquote(v):
    v = re.sub(r"\s+#.*$", "", v).strip()
    if len(v) >= 2 and v[0] == v[-1] and v[0] in "'\"":
        return v[1:-1]
    return v


for f in sorted(glob.glob(os.path.join(root, "*-lan.yml"))):
    svc = os.path.basename(f)[: -len("-lan.yml")]
    stack = []  # (들여쓰기, 이름) — 목록 항목은 이름이 "[n]"
    counters = {}  # 목록 부모 경로 → 다음 번호
    items = {}  # 목록 항목 경로 → [(속성, 값, 암호문 여부)]
    skip_quote = None  # 여러 줄 따옴표 문자열을 닫을 따옴표
    block_ind = None  # 블록 문자열(| >) 의 기준 들여쓰기
    with open(f, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.rstrip("\n").rstrip("\r")
            if skip_quote:
                if line.rstrip().endswith(skip_quote):
                    skip_quote = None
                continue
            if block_ind is not None:
                if not line.strip() or len(line) - len(line.lstrip()) > block_ind:
                    continue
                block_ind = None
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            m = KEY.match(line)
            if not m:
                continue
            ind = len(m.group(1))
            key, val = m.group(3), (m.group(4) or "")
            if m.group(2):  # 목록 항목 시작
                while stack and stack[-1][0] >= ind:
                    stack.pop()
                parent = "".join(n if n.startswith("[") else ("." + n) for _, n in stack).lstrip(".")
                idx = counters.get(parent, 0)
                counters[parent] = idx + 1
                stack.append((ind, f"[{idx}]"))
                ind += 2
            while stack and stack[-1][0] >= ind:
                stack.pop()
            names = [n for _, n in stack] + [key]
            path = "".join(n if n.startswith("[") else ("." + n) for n in names).lstrip(".")
            v = val.strip()
            if v and v[0] in "'\"" and v[0] not in v[1:]:  # 따옴표를 열고 같은 줄에서 닫지 않음 → 다음 줄들로 이어짐
                skip_quote = v[0]
            elif v and v[0] in "|>":
                block_ind = ind
            c = re.search(r"\{cipher\}([A-Za-z0-9+/=]+)", val)
            elem = next((i for i, (_, n) in reversed(list(enumerate(stack))) if n.startswith("[")), None)
            if elem is not None and v and not v.endswith(":"):
                ep = "".join(n if n.startswith("[") else ("." + n) for _, n in stack[: elem + 1]).lstrip(".")
                items.setdefault(ep, []).append((path, c.group(1) if c else unquote(v), bool(c)))
            if c:
                print(f"{svc}|{path}\t{'cipher' + chr(9) if siblings else ''}{c.group(1)}")
            stack.append((ind, key))
    if siblings:
        for ep, props in items.items():
            if any(isc for _, _, isc in props):
                for p, v, isc in props:
                    if not isc:
                        print(f"{svc}|{p}\tplain\t{v}")
