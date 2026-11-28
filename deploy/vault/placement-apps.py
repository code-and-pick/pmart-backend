"""deploy/lan/placement.conf 에서 Spring 앱과 그 PC IP 를 뽑는다 — 표준출력 "앱 IP" (service-roles.sh).
사용: python -I placement-apps.py <placement.conf> <저장소 루트>
Spring 앱 = services/·platform-services/ 아래 src 가 있는 폴더 이름. 줄 끝 '\' 는 다음 줄과 잇는다."""
import os
import re
import sys

conf, root = sys.argv[1], sys.argv[2]
apps = {
    d
    for base in ("services", "platform-services")
    for d in os.listdir(os.path.join(root, base))
    if os.path.isdir(os.path.join(root, base, d, "src"))
}
lines, buf = [], ""
for raw in open(conf, encoding="utf-8").read().replace("\r", "").split("\n"):
    if raw.rstrip().endswith(chr(92)):
        buf += raw.rstrip()[:-1] + " "
        continue
    lines.append(buf + raw)
    buf = ""
for line in lines:
    s = line.split("#", 1)[0].split()
    if len(s) < 4 or not re.match(r"^\d+\.\d+\.\d+\.\d+$", s[1]):
        continue
    for c in s[3:]:
        if c in apps:
            print(c, s[1])
