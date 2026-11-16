"""PMart RAG UML 다이어그램 5종 — 배치·컴포넌트·시퀀스·상태 머신·활동. 값은 코드·설정·PC 2 /api/ps·/api/show(2026-11-16) 기준."""
import math
import os
import sys
from xml.sax.saxutils import escape

OUT = sys.argv[1]
FONT = "'Segoe UI','Malgun Gothic','Apple SD Gothic Neo',sans-serif"
MONO = "Consolas,'D2Coding',monospace"
INK, SUB, EDGE = "#1f2937", "#6b7280", "#374151"
FILL = {"node": "#f8fafc", "env": "#ffffff", "comp": "#eef4ff", "art": "#fffdf3", "dev": "#f1f5f9",
        "state": "#eef4ff", "act": "#eef4ff", "note": "#fff9db", "frag": "#ffffff"}


class SVG:
    def __init__(self, w, h, title):
        self.w, self.h, self.title, self.o = w, h, title, []

    def add(self, s):
        self.o.append(s)

    def text(self, x, y, s, size=13, w="normal", anchor="start", fill=INK, italic=False, mono=False):
        st = ' font-style="italic"' if italic else ""
        fam = f' font-family="{MONO}"' if mono else ""
        self.add(f'<text x="{x:.1f}" y="{y:.1f}" font-size="{size}" font-weight="{w}" fill="{fill}" text-anchor="{anchor}"{st}{fam}>{escape(s)}</text>')

    def rect(self, x, y, w, h, fill="#fff", stroke=EDGE, rx=0, sw=1.4, dash=None):
        d = f' stroke-dasharray="{dash}"' if dash else ""
        self.add(f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h:.1f}" rx="{rx}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}"{d}/>')

    def line(self, pts, dash=None, head=None, sw=1.4, color=EDGE):
        d = f' stroke-dasharray="{dash}"' if dash else ""
        m = f' marker-end="url(#{head})"' if head else ""
        p = " ".join(f"{a:.1f},{b:.1f}" for a, b in pts)
        self.add(f'<polyline points="{p}" fill="none" stroke="{color}" stroke-width="{sw}"{d}{m}/>')

    def save(self, name):
        defs = (
            '<marker id="filled" viewBox="0 0 10 10" refX="10" refY="5" markerWidth="9" markerHeight="9" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="#374151"/></marker>'
            '<marker id="open" viewBox="0 0 10 10" refX="10" refY="5" markerWidth="9" markerHeight="9" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10" fill="none" stroke="#374151" stroke-width="1.5"/></marker>'
        )
        svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.w}" height="{self.h}" viewBox="0 0 {self.w} {self.h}" font-family="{FONT}">'
               f'<title>{escape(self.title)}</title><defs>{defs}</defs><rect width="{self.w}" height="{self.h}" fill="#fff"/>' + "".join(self.o) + "</svg>")
        path = os.path.join(OUT, name)
        open(path, "w", encoding="utf-8", newline="\n").write(svg)
        print(path, self.w, self.h)


# ---------- shared UML shapes ----------
def header(s, kind, name, sub):
    s.text(30, 40, f"{kind} — {name}", 22, "bold")
    s.text(30, 64, sub, 12.5, fill=SUB)


def frame(s, x, y, w, h, tag):
    # UML diagram frame with pentagon tag
    s.rect(x, y, w, h, "none", "#9ca3af", sw=1.2)
    tw = len(tag) * 7.4 + 24
    s.add(f'<path d="M{x},{y} h{tw} v14 l-10,10 h{-(tw - 10)} z" fill="#f3f4f6" stroke="#9ca3af" stroke-width="1.2"/>')
    s.text(x + 8, y + 17, tag, 12, "bold")


def node(s, x, y, w, h, stereo, name, fill=FILL["node"], depth=12):
    s.add(f'<polygon points="{x},{y} {x + depth},{y - depth} {x + w + depth},{y - depth} {x + w},{y}" fill="#e5e7eb" stroke="{EDGE}" stroke-width="1.3"/>')
    s.add(f'<polygon points="{x + w},{y} {x + w + depth},{y - depth} {x + w + depth},{y + h - depth} {x + w},{y + h}" fill="#d1d5db" stroke="{EDGE}" stroke-width="1.3"/>')
    s.rect(x, y, w, h, fill, EDGE, sw=1.3)
    s.text(x + 10, y + 17, stereo, 11.5, italic=True, fill=SUB)
    s.text(x + 10, y + 35, name, 14, "bold")


def artifact(s, x, y, w, h, name, sub=None):
    s.rect(x, y, w, h, FILL["art"], EDGE, sw=1.2)
    ix, iy = x + w - 24, y + 7
    s.add(f'<path d="M{ix},{iy} h9 l5,5 v13 h-14 z M{ix + 9},{iy} v5 h5" fill="#fff" stroke="{EDGE}" stroke-width="1"/>')
    s.text(x + 8, y + 16, "«artifact»", 10.5, italic=True, fill=SUB)
    s.text(x + 8, y + 33, name, 12.5, "bold", mono=True)
    if sub:
        s.text(x + 8, y + 50, sub, 11.5, fill=SUB)


def component(s, x, y, w, h, name, stereo="«component»", fill=FILL["comp"], bold=True, size=13.5):
    s.rect(x, y, w, h, fill, EDGE, sw=1.3)
    ix, iy = x + w - 22, y + 8
    s.rect(ix, iy, 12, 16, "#fff", EDGE, sw=1)
    s.rect(ix - 4, iy + 3, 7, 3, "#fff", EDGE, sw=1)
    s.rect(ix - 4, iy + 10, 7, 3, "#fff", EDGE, sw=1)
    s.text(x + w / 2, y + 18, stereo, 10.5, italic=True, fill=SUB, anchor="middle")
    s.text(x + w / 2, y + 36, name, size, "bold" if bold else "normal", anchor="middle")


def note(s, x, y, w, lines, size=11.5):
    h = 12 + len(lines) * 17
    s.add(f'<path d="M{x},{y} h{w - 12} l12,12 v{h - 12} h{-w} z M{x + w - 12},{y} v12 h12" fill="{FILL["note"]}" stroke="#a16207" stroke-width="1"/>')
    for i, ln in enumerate(lines):
        s.text(x + 8, y + 19 + i * 17, ln, size)
    return h


def label_bg(s, x, y, txt, size=11.5, anchor="middle", italic=False, mono=False):
    wpx = len(txt) * (6.9 if mono else 7.0) + 8
    lx = x - wpx / 2 if anchor == "middle" else x
    s.rect(lx, y - size, wpx, size + 5, "#fff", "none", sw=0)
    s.text(x if anchor == "middle" else x + 4, y, txt, size, anchor=anchor, italic=italic, mono=mono, fill=INK)


# =====================================================================
# 1. Deployment
# =====================================================================
def deployment():
    s = SVG(1520, 980, "PMart RAG — UML 배치 다이어그램")
    header(s, "배치 다이어그램 (Deployment)", "PMart RAG 실행 환경", "LAN · 2026-11-16 · deploy/lan/render.sh, placement.conf, PC 2 /api/ps")
    frame(s, 20, 82, 1480, 885, "deployment PMart RAG (LAN)")

    # PC 4
    node(s, 50, 130, 330, 330, "«device»", "PC 4 · 192.168.0.40")
    node(s, 70, 190, 290, 110, "«executionEnvironment»", "Docker 컨테이너 api-gateway", FILL["env"], 8)
    artifact(s, 85, 238, 260, 48, "api-gateway (Jib 이미지)")
    node(s, 70, 330, 290, 110, "«executionEnvironment»", "개발 도구 (Python · Gradle)", FILL["env"], 8)
    artifact(s, 85, 378, 260, 48, "rag_answer_eval.py · …IT")

    # PC 1
    node(s, 50, 520, 330, 170, "«device»", "PC 1 · 빌드·운영")
    artifact(s, 70, 570, 290, 48, "deploy/lan/lan.sh")
    s.text(70, 650, "rag-pull · rag-warm · rag-ingest · deploy", 11.5, fill=SUB)

    # PC 2
    node(s, 450, 120, 1020, 830, "«device»", "PC 2 · 192.168.0.34 · i5-11400F")
    node(s, 470, 175, 980, 570, "«executionEnvironment»", "Docker Engine (네트워크 pmart-lan)", FILL["env"], 8)

    # service containers
    for i, (nm, art, port) in enumerate([("ai-assistant-service", "pmart-ai-assistant-service:<sha>", ":8508"),
                                         ("customer-assistant-service", "pmart-customer-assistant-service:<sha>", ":8509")]):
        y = 230 + i * 140
        node(s, 490, y, 330, 115, "«executionEnvironment» 컨테이너", f"{nm} {port}", FILL["env"], 6)
        artifact(s, 505, y + 52, 300, 48, art)
    node(s, 490, 510, 330, 215, "«executionEnvironment» 컨테이너", "rag-pgvector :15433 (LAN 닫힘)", FILL["env"], 6)
    artifact(s, 505, 560, 300, 48, "pgvector/pgvector:pg17")
    artifact(s, 505, 615, 300, 48, "DB ai_assistant_service")
    artifact(s, 505, 670, 300, 48, "DB customer_assistant_service")

    # ollama container
    node(s, 870, 230, 560, 495, "«executionEnvironment» 컨테이너", "ollama :11434 (cpus 4 · mem 6g · gpus all)", FILL["env"], 6)
    artifact(s, 890, 282, 520, 58, "ollama/ollama:0.40.10", "Ollama Server (Go) — HTTP API·스케줄러")
    node(s, 890, 370, 250, 175, "«executionEnvironment» 프로세스", "llama-server #1", FILL["dev"], 6)
    artifact(s, 902, 418, 226, 58, "qwen3:4b · Q4_K_M", "Qwen3-4B-Thinking-2507")
    s.text(902, 500, "ctx 8192 · GPU 3.88GB", 11.5, fill=SUB, mono=True)
    s.text(902, 520, "/api/chat", 11.5, fill=SUB, mono=True)
    node(s, 1160, 370, 250, 175, "«executionEnvironment» 프로세스", "llama-server #2", FILL["dev"], 6)
    artifact(s, 1172, 418, 226, 58, "qwen3-embedding:0.6b · Q8_0", "Qwen3-Embedding-0.6B")
    s.text(1172, 500, "ctx 2048 · CPU 1.72GB", 11.5, fill=SUB, mono=True)
    s.text(1172, 520, "/api/embed · dim 1024", 11.5, fill=SUB, mono=True)
    artifact(s, 890, 600, 520, 60, "volume ollamadata → /root/.ollama", "manifests/ · blobs/sha256-… (GGUF)")
    s.line([(1015, 545), (1015, 600)], dash="6 4", head="open")
    s.line([(1285, 545), (1285, 600)], dash="6 4", head="open")
    label_bg(s, 1015, 578, "«load»", italic=True)
    label_bg(s, 1285, 578, "«load»", italic=True)

    # hardware
    node(s, 490, 790, 450, 130, "«device»", "GPU · NVIDIA RTX 2060 6GB", FILL["dev"], 8)
    s.text(505, 860, "qwen3:4b 전용 (3.88GB) · 화면 0.86GB", 12, fill=SUB)
    s.text(505, 880, "임베딩 모델은 올리지 않음 (2026-11-09)", 12, fill=SUB)
    node(s, 980, 790, 450, 130, "«device»", "CPU · 4코어 (컨테이너 제한)", FILL["dev"], 8)
    s.text(995, 860, "임베딩 추론 · 같은 PC 의 주문·결제와 공유", 12, fill=SUB)
    s.line([(890, 530), (880, 530), (880, 765), (715, 765), (715, 778)], dash="6 4", head="open")
    s.line([(1410, 530), (1420, 530), (1420, 765), (1205, 765), (1205, 778)], dash="6 4", head="open")
    label_bg(s, 800, 765, "«use»", italic=True)
    label_bg(s, 1310, 765, "«use»", italic=True)

    # communication paths
    def path(pts, lbl, lx, ly):
        s.line(pts, sw=1.6)
        label_bg(s, lx, ly, lbl, 11.5, mono=True)
    path([(360, 245), (430, 245), (430, 288), (490, 288)], "«HTTP»", 455, 280)
    path([(430, 288), (430, 428), (490, 428)], "«HTTP»", 458, 420)
    path([(820, 300), (870, 300)], "", 0, 0)
    path([(820, 440), (850, 440), (850, 320), (870, 320)], "", 0, 0)
    label_bg(s, 846, 262, "«HTTP»", 11.5, mono=True)
    label_bg(s, 846, 278, ":11434", 11.5, mono=True)
    path([(820, 330), (836, 330), (836, 580), (820, 580)], "", 0, 0)
    path([(820, 470), (836, 470)], "", 0, 0)
    label_bg(s, 836, 520, "«JDBC»", 11.5, mono=True)
    path([(360, 385), (400, 385), (400, 108), (1150, 108), (1150, 218)], "«HTTP» LAN :11434 (개발)", 760, 112)
    path([(380, 605), (450, 605)], "«SSH»", 415, 595)
    s.save("pmart-rag-uml-1-deployment.svg")


# =====================================================================
# 2. Component
# =====================================================================
def ball(s, x, y, label=None, side="left", ly=None):
    s.add(f'<circle cx="{x}" cy="{y}" r="8" fill="#fff" stroke="{EDGE}" stroke-width="1.5"/>')
    if label:
        s.text(x, (ly or y - 14), label, 11.5, "bold", anchor="middle", mono=True)


def socket(s, x, y, facing="right"):
    # half circle opening toward facing
    r = 13
    if facing == "right":
        s.add(f'<path d="M{x},{y - r} a{r},{r} 0 0 0 0,{2 * r}" fill="none" stroke="{EDGE}" stroke-width="1.5"/>')
    elif facing == "down":
        s.add(f'<path d="M{x - r},{y} a{r},{r} 0 0 1 {2 * r},0" fill="none" stroke="{EDGE}" stroke-width="1.5"/>')
    elif facing == "up":
        s.add(f'<path d="M{x - r},{y} a{r},{r} 0 0 0 {2 * r},0" fill="none" stroke="{EDGE}" stroke-width="1.5"/>')


def port(s, x, y):
    s.rect(x - 6, y - 6, 12, 12, "#fff", EDGE, sw=1.3)


def component_diagram():
    s = SVG(1520, 940, "PMart RAG — UML 컴포넌트 다이어그램")
    header(s, "컴포넌트 다이어그램 (Component)", "RAG 서비스 ↔ Ollama ↔ pgvector", "제공 인터페이스(●)·요구 인터페이스(◐)·포트(□) · 코드: OllamaModels·Retriever·GenerativeAnswerGenerator·ChunkStore")
    frame(s, 20, 82, 1480, 845, "cmp PMart RAG")

    # ai-assistant-service
    component(s, 60, 130, 420, 330, "ai-assistant-service", size=15)
    for i, (nm, st) in enumerate([("PolicyQuestionService", "«component»"), ("Retriever · ChunkStore", "«component»"),
                                  ("GenerativeAnswerGenerator", "«component»"), ("OllamaModels", "«adapter» Spring AI")]):
        component(s, 85, 180 + i * 66, 300, 54, nm, st, "#fff", size=12.5)
    # customer
    component(s, 60, 500, 420, 150, "customer-assistant-service", size=15)
    s.text(270, 572, "운영자용 코드 복사본 + 요청 제한 · 주문 상태", 12, fill=SUB, anchor="middle")
    component(s, 85, 590, 300, 50, "OllamaModels", "«adapter» Spring AI", "#fff", size=12.5)

    # pgvector
    component(s, 60, 720, 420, 120, "rag-pgvector", "«component» PostgreSQL 17 + pgvector + pg_trgm", size=15)
    s.text(270, 800, "DB 둘 · 계정 둘 · vector <=> · word_similarity", 12, fill=SUB, anchor="middle")

    # Ollama
    OX, OY, OW, OH = 700, 120, 760, 700
    component(s, OX, OY, OW, OH, "Ollama", "«subsystem» ollama/ollama:0.40.10", "#f8fafc", size=16)
    component(s, OX + 50, OY + 60, 280, 66, "REST API", "«component» Go", "#fff")
    component(s, OX + 50, OY + 170, 280, 110, "Scheduler", "«component» Go", "#fff")
    s.text(OX + 190, OY + 232, "MAX_LOADED_MODELS=2 · NUM_PARALLEL=1", 10.5, fill=SUB, mono=True, anchor="middle")
    s.text(OX + 190, OY + 250, "KEEP_ALIVE=24h · CONTEXT_LENGTH=8192", 10.5, fill=SUB, mono=True, anchor="middle")
    component(s, OX + 50, OY + 330, 280, 66, "Model Manager", "«component» Go", "#fff")
    component(s, OX + 430, OY + 60, 290, 110, "llama-server #1", "«component» llama.cpp", "#fff")
    s.text(OX + 575, OY + 126, "qwen3:4b · GPU · 생성", 12, anchor="middle", fill=SUB)
    s.text(OX + 575, OY + 146, "ctx 8192 · json · think off", 11, anchor="middle", fill=SUB, mono=True)
    component(s, OX + 430, OY + 250, 290, 110, "llama-server #2", "«component» llama.cpp", "#fff")
    s.text(OX + 575, OY + 316, "qwen3-embedding:0.6b · CPU", 12, anchor="middle", fill=SUB)
    s.text(OX + 575, OY + 336, "ctx 2048 · 1024차원 · L2", 11, anchor="middle", fill=SUB, mono=True)
    component(s, OX + 50, OY + 450, 670, 90, "Model Store", "«component» volume ollamadata", "#fff")
    s.text(OX + 385, OY + 516, "manifest(이름:태그) → blob(sha256 GGUF) · qwen3:4b 359d7dd4bcda · qwen3-embedding:0.6b ac6da0dfba84", 10.5, anchor="middle", fill=SUB, mono=True)

    # internal connectors
    s.line([(OX + 190, OY + 126), (OX + 190, OY + 170)], head="open")
    s.line([(OX + 330, OY + 225), (OX + 380, OY + 225), (OX + 380, OY + 115), (OX + 430, OY + 115)], head="open")
    s.line([(OX + 380, OY + 225), (OX + 380, OY + 305), (OX + 430, OY + 305)], head="open")
    label_bg(s, OX + 380, OY + 190, "spawn", 11, mono=True)
    label_bg(s, OX + 380, OY + 262, "RunnerAPI", 11, mono=True)
    s.line([(OX + 190, OY + 280), (OX + 190, OY + 330)], head="open")
    s.line([(OX + 190, OY + 396), (OX + 190, OY + 450)], head="open")
    label_bg(s, OX + 190, OY + 428, "resolve blob", 11, mono=True)
    s.line([(OX + 575, OY + 360), (OX + 575, OY + 450)], dash="6 4", head="open")
    s.line([(OX + 720, OY + 115), (OX + 742, OY + 115), (OX + 742, OY + 495), (OX + 720, OY + 495)], dash="6 4", head="open")
    label_bg(s, OX + 640, OY + 410, "«load» GGUF", 11, italic=True)

    # Embed/Chat: ports on Ollama boundary → REST API, assembly with service ports
    py1, py2 = 210, 290
    for py in (py1, py2):
        port(s, OX, py)
        s.line([(OX + 6, py), (OX + 25, py), (OX + 25, OY + 93), (OX + 50, OY + 93)])
    for py, nm in [(py1, "EmbedAPI · POST /api/embed"), (py2, "ChatAPI · POST /api/chat")]:
        bx = OX - 60
        s.line([(OX - 6, py), (bx + 8, py)])
        ball(s, bx, py)
        socket(s, bx - 3, py, "right")
        s.line([(486, py), (bx - 16, py)])
        s.text(bx - 30, py - 18, nm, 11.5, "bold", anchor="middle", mono=True)
    # service internal: adapter → service ports
    s.line([(385, 405), (440, 405), (440, py1), (474, py1)])
    s.line([(440, py2), (474, py2)])
    port(s, 480, py1)
    port(s, 480, py2)
    # customer service uses the same two required interfaces
    port(s, 480, 615)
    s.line([(486, 615), (570, 615), (570, py1)])
    for py in (py1, py2):
        s.add(f'<circle cx="570" cy="{py}" r="3.5" fill="{EDGE}"/>')
    s.text(562, 470, "customer-assistant-service 도", 11, fill=SUB, anchor="end")
    s.text(562, 486, "같은 두 인터페이스를 요구", 11, fill=SUB, anchor="end")
    s.line([(385, 615), (474, 615)])

    # SQL: pgvector provides, services require
    ball(s, 270, 696, None)
    socket(s, 270, 694, "up")
    s.line([(270, 704), (270, 720)])
    s.line([(270, 681), (270, 650)])
    s.text(290, 700, "SQL · JDBC :15433", 11.5, "bold", mono=True)
    s.line([(150, 460), (150, 478), (40, 478), (40, 690), (257, 690)])

    # AdminAPI on bottom boundary
    port(s, OX + 400, OY + OH)
    s.line([(OX + 400, OY + OH + 6), (OX + 400, OY + OH + 28)])
    ball(s, OX + 400, OY + OH + 36)
    s.text(OX + 420, OY + OH + 34, "AdminAPI · /api/pull · /api/ps · /api/show · /api/tags", 11.5, "bold", mono=True)
    s.text(OX + 420, OY + OH + 51, "← PC 1 lan.sh (docker exec) · PC 4 개발 (LAN :11434)", 11, fill=SUB)
    s.line([(OX + 400, OY + OH - 6), (OX + 400, OY + 600), (OX + 12, OY + 600), (OX + 12, OY + 75), (OX + 50, OY + 75)])
    s.save("pmart-rag-uml-2-component.svg")


# =====================================================================
# 3. Sequence
# =====================================================================
def sequence():
    s = SVG(1560, 1260, "PMart RAG — UML 시퀀스 다이어그램")
    header(s, "시퀀스 다이어그램 (Sequence)", "운영자 질문 하나 (생성 답변 경로)", "PolicyQuestionService · Retriever · GenerativeAnswerGenerator · Ollama 동시 1건(NUM_PARALLEL=1)")
    frame(s, 20, 82, 1520, 1165, "sd 정책 질문")
    lanes = [("백오피스", "«actor»", 100), (":ai-assistant-service", "", 330), (":Ollama Server", "", 600),
             (":llama-server #2", "qwen3-embedding", 830), (":rag-pgvector", "", 1060), (":llama-server #1", "qwen3:4b", 1320)]
    top, bottom = 130, 1225
    for name, st, x in lanes:
        if st == "«actor»":
            s.add(f'<circle cx="{x}" cy="{top + 10}" r="10" fill="#fff" stroke="{EDGE}" stroke-width="1.5"/>')
            s.line([(x, top + 20), (x, top + 44)])
            s.line([(x - 14, top + 30), (x + 14, top + 30)])
            s.line([(x - 12, top + 62), (x, top + 44), (x + 12, top + 62)])
            s.text(x, top + 80, name, 13, "bold", anchor="middle")
            y0 = top + 88
        else:
            s.rect(x - 95, top, 190, 52, FILL["comp"], EDGE, sw=1.3)
            s.text(x, top + 23, name, 13, "bold", anchor="middle")
            if st:
                s.text(x, top + 41, st, 11.5, anchor="middle", fill=SUB, mono=True)
            y0 = top + 52
        s.line([(x, y0), (x, bottom)], dash="5 5", color="#9ca3af")
    X = {n: x for n, _, x in lanes}
    a, svc, oll, emb, db, chat = [x for *_, x in lanes]

    def act(x, y1, y2):
        s.rect(x - 6, y1, 12, y2 - y1, "#e5e7eb", EDGE, sw=1)

    def msg(x1, x2, y, txt, ret=False, self_=False, mono=True):
        if self_:
            s.line([(x1 + 6, y), (x1 + 50, y), (x1 + 50, y + 22), (x1 + 8, y + 22)], head="filled")
            s.text(x1 + 58, y + 15, txt, 12, mono=False)
            return
        s.line([(x1 + (6 if x2 > x1 else -6), y), (x2 + (-6 if x2 > x1 else 6), y)], dash="6 4" if ret else None, head="open" if ret else "filled")
        mx = (x1 + x2) / 2
        s.text(mx, y - 7, txt, 12, anchor="middle", mono=mono and not ret, fill=SUB if ret else INK)

    y = 240
    act(svc, y, 1170)
    msg(a, svc, y, "POST /assistant/policy-questions {question}")
    y += 40
    msg(svc, svc, y, "선점(lease) · 의도 분류 → POLICY · 스냅숏 고정", self_=True)
    y += 60
    # embed
    act(oll, y, y + 120)
    msg(svc, oll, y, "POST /api/embed (지시문+질문)")
    # opt load
    s.rect(560, y + 10, 330, 46, "none", EDGE, sw=1)
    s.add(f'<path d="M560,{y + 10} h52 v12 l-8,8 h-44 z" fill="#f3f4f6" stroke="{EDGE}"/>')
    s.text(565, y + 25, "opt", 11.5, "bold")
    s.text(620, y + 25, "[모델 미적재] 실행기 기동·적재", 11.5)
    y += 80
    act(emb, y, y + 40)
    msg(oll, emb, y, "forward")
    msg(emb, oll, y + 40, "vector[1024]", ret=True)
    msg(oll, svc, y + 50, "{embeddings}", ret=True)
    y += 90
    act(db, y, y + 40)
    msg(svc, db, y, "SELECT … WHERE snapshot·scope·lifecycle ORDER BY sim LIMIT 40")
    msg(db, svc, y + 40, "후보 40 (sim = 0.6·cos + 0.4·trgm)", ret=True)
    y += 60
    msg(svc, svc, y, "임계 0.40 · 지위 가중 · 상위 5 → D1..D5", self_=True)
    y += 60
    # loop for generation attempts
    y += 30
    ly0 = y - 40
    act(oll, y, y + 150)
    msg(svc, oll, y, "POST /api/chat (system 규칙 8 + <doc D1..D5> + 질문)")
    y += 40
    act(chat, y, y + 70)
    msg(oll, chat, y, "forward (format json · think off · ctx 8192)")
    s.text(chat + 12, y + 40, "토큰 생성…", 11.5, fill=SUB)
    msg(chat, oll, y + 70, "{answer, usedIds, grounded}", ret=True)
    msg(oll, svc, y + 82, "message.content", ret=True)
    y += 110
    msg(svc, svc, y, "CitationVerifier: ID 유효 · bigram · 부정어 · 숫자", self_=True)
    y += 40
    s.rect(220, ly0, 1250, y - ly0 + 10, "none", EDGE, sw=1)
    s.add(f'<path d="M220,{ly0} h56 v12 l-8,8 h-48 z" fill="#f3f4f6" stroke="{EDGE}"/>')
    s.text(225, ly0 + 15, "loop", 11.5, "bold")
    s.text(285, ly0 + 15, "[최대 2회 — 검사 실패·JSON 아님이면 실패 사유를 넣어 다시]", 11.5)
    y += 30
    # alt result
    ay = y
    s.rect(220, ay, 1250, 190, "none", EDGE, sw=1)
    s.add(f'<path d="M220,{ay} h44 v12 l-8,8 h-36 z" fill="#f3f4f6" stroke="{EDGE}"/>')
    s.text(225, ay + 15, "alt", 11.5, "bold")
    s.text(275, ay + 15, "[검사 통과]", 11.5)
    msg(svc, svc, ay + 30, "GENERATIVE (확정·채택 근거 없으면 \"확정 정책 근거 없음\" 접두)", self_=True)
    s.line([(220, ay + 75), (1470, ay + 75)], dash="6 4")
    s.text(275, ay + 92, "[2회 실패 · 1위 sim ≥ 0.45]", 11.5)
    msg(svc, svc, ay + 100, "BASELINE_EXTRACTIVE (원문 발췌)", self_=True)
    s.line([(220, ay + 140), (1470, ay + 140)], dash="6 4")
    s.text(275, ay + 157, "[그 밖]", 11.5)
    msg(svc, svc, ay + 160, "NONE (근거 없음)", self_=True)
    y = ay + 215
    msg(svc, svc, y, "조건부 UPDATE (attempt·PROCESSING) · 질문 원문 저장 안 함", self_=True)
    y += 55
    msg(svc, a, y, "200 {answerMode, answer, citations}", ret=True)
    note(s, 1150, 240, 370, ["Ollama 는 모델당 동시 1건(NUM_PARALLEL=1)", "— 다른 질문·평가 요청은 앞 요청이 끝날", "  때까지 대기. 서비스 처리 제한 60초,", "  모델 호출 제한 25초, 게이트웨이 65초"])
    s.save("pmart-rag-uml-3-sequence.svg")


# =====================================================================
# 4. State machine
# =====================================================================
def state_machine():
    s = SVG(1400, 700, "PMart RAG — UML 상태 머신 다이어그램")
    header(s, "상태 머신 다이어그램 (State Machine)", "Ollama 안의 모델 하나(실행기)", "OLLAMA_MAX_LOADED_MODELS=2 · NUM_PARALLEL=1 · KEEP_ALIVE=24h · rag-warm · 모델 2개(qwen3:4b, qwen3-embedding:0.6b)")
    frame(s, 20, 82, 1360, 600, "stm 모델 적재")

    def st(x, y, w, h, name, lines=()):
        s.rect(x, y, w, h, FILL["state"], EDGE, rx=16, sw=1.4)
        s.text(x + w / 2, y + 24, name, 14, "bold", anchor="middle")
        if lines:
            s.line([(x, y + 34), (x + w, y + 34)], color="#9ca3af", sw=1)
        for i, l in enumerate(lines):
            s.text(x + 12, y + 54 + i * 18, l, 11.5, mono=True)

    def tr(pts, lbl, lx, ly, anchor="middle"):
        s.line(pts, head="open", sw=1.5)
        label_bg(s, lx, ly, lbl, 11.5, anchor=anchor)

    # initial
    s.add(f'<circle cx="90" cy="300" r="10" fill="{EDGE}"/>')
    st(160, 260, 210, 80, "미적재", ["실행기 없음"])
    st(520, 140, 250, 100, "적재 중", ["entry / llama-server 기동", "GGUF 읽기 · ~30초(qwen3:4b)"])
    # composite Loaded
    s.rect(500, 330, 820, 290, "#f8fafc", EDGE, rx=18, sw=1.6)
    s.text(520, 356, "적재됨", 15, "bold")
    s.text(590, 356, "keep_alive 24h 타이머", 11.5, fill=SUB)
    s.add(f'<circle cx="560" cy="420" r="8" fill="{EDGE}"/>')
    st(610, 385, 220, 80, "유휴", ["요청 없음 · 타이머 진행"])
    st(1010, 385, 260, 110, "처리 중", ["do / 토큰화 → 추론", "다른 요청은 대기열", "(NUM_PARALLEL=1)"])
    s.line([(568, 420), (610, 420)], head="open")
    tr([(830, 405), (1010, 405)], "요청 도착 / 대기열에서 하나", 920, 398)
    tr([(1010, 470), (830, 450)], "응답 완료 / 타이머 다시 24h", 920, 478)
    st(160, 520, 230, 90, "내려가는 중", ["exit / 메모리·GPU 반환"])

    s.line([(100, 300), (160, 300)], head="open")
    tr([(370, 280), (450, 280), (450, 190), (520, 190)], "요청 도착 [실행기 없음] 또는 rag-warm", 400, 170)
    tr([(645, 240), (645, 330)], "적재 성공", 700, 290)
    tr([(520, 220), (300, 220), (300, 260)], "적재 실패 / 요청에 오류(→ 서비스 503)", 300, 212)
    tr([(500, 560), (390, 560)], "keep_alive 만료", 445, 550)
    tr([(500, 600), (440, 600), (440, 595), (390, 595)], "", 0, 0)
    label_bg(s, 430, 640, "밀어냄 [다른 모델 요청 · 이미 2개 적재]")
    tr([(240, 520), (240, 340)], "해제 완료", 290, 440)
    note(s, 840, 520, 460, ["PC 2: 적재 한도 2개 = 임베딩 + 대화 모델로 꽉 참", "→ 제3 모델(예: Gemma 비교)을 부르면 하나가 밀려나고", "  다음 질문 때 '적재 중'(~30초)을 다시 지나", "  게이트웨이 65초·모델 호출 25초 제한에 걸릴 수 있음"])
    s.save("pmart-rag-uml-4-state.svg")


# =====================================================================
# 5. Activity
# =====================================================================
def activity():
    s = SVG(1500, 1500, "PMart RAG — UML 활동 다이어그램")
    header(s, "활동 다이어그램 (Activity)", "운영자 질문 처리", "PolicyQuestionService · QuestionClassifier · Retriever · GenerativeAnswerGenerator · CitationVerifier · ExtractiveAnswerGenerator")
    frame(s, 20, 82, 1460, 1405, "act 정책 질문 처리")
    # partitions
    parts = [("ai-assistant-service", 40, 860), ("Ollama", 900, 300), ("rag-pgvector", 1200, 260)]
    for name, x, w in parts:
        s.rect(x, 110, w, 1365, "none", EDGE, sw=1.2)
        s.rect(x, 110, w, 34, "#f3f4f6", EDGE, sw=1.2)
        s.text(x + w / 2, 133, name, 14, "bold", anchor="middle")

    def actn(cx, y, txt, w=300, h=44, sub=None):
        s.rect(cx - w / 2, y, w, h, FILL["act"], EDGE, rx=14, sw=1.3)
        s.text(cx, y + (20 if sub else 27), txt, 12.5, "bold", anchor="middle")
        if sub:
            s.text(cx, y + 37, sub, 11, anchor="middle", fill=SUB, mono=True)
        return (cx, y, w, h)

    def dec(cx, y, r=18):
        s.add(f'<polygon points="{cx},{y} {cx + r},{y + r} {cx},{y + 2 * r} {cx - r},{y + r}" fill="#fff" stroke="{EDGE}" stroke-width="1.4"/>')
        return y + 2 * r

    def flow(pts, guard=None, gx=None, gy=None, anchor="start"):
        s.line(pts, head="open", sw=1.4)
        if guard:
            label_bg(s, gx, gy, guard, 11.5, anchor=anchor)

    C = 330
    s.add(f'<circle cx="{C}" cy="170" r="10" fill="{EDGE}"/>')
    actn(C, 200, "요청 받기", sub="토큰 typ=OPERATOR · Idempotency-Key")
    flow([(C, 180), (C, 200)])
    d1 = dec(C, 270)
    flow([(C, 244), (C, 270)])
    actn(700, 266, "저장 상태 그대로 응답", 250)
    flow([(C + 18, 288), (575, 288)], "[같은 키 기록 있음]", 360, 280)
    flow([(C, d1), (C, 330)], "[새 요청]", C + 8, 320)
    actn(C, 330, "선점 (lease 120s)", sub="policy_answer PROCESSING")
    flow([(C, 374), (C, 400)])
    actn(C, 400, "의도 분류 (규칙)", sub="QuestionClassifier")
    d2 = dec(C, 470)
    flow([(C, 444), (C, 470)])
    actn(700, 466, "안내 응답", 250, sub="OUT_OF_SCOPE · REALTIME")
    flow([(C + 18, 488), (575, 488)], "[실행 요청·실시간 질문]", 360, 480)
    flow([(C, d2), (C, 530)], "[POLICY]", C + 8, 520)
    actn(C, 530, "스냅숏 고정", sub="ACTIVE · 공유 잠금 · 모델 일치 검사")
    # embed in Ollama lane
    actn(1050, 600, "질문 임베딩", 260, sub="qwen3-embedding:0.6b → 1024")
    flow([(C, 574), (C, 590), (1050, 590), (1050, 600)])
    actn(1330, 680, "후보 검색", 230, sub="필터 + 0.6·cos + 0.4·trgm")
    flow([(1050, 644), (1050, 660), (1330, 660), (1330, 680)])
    actn(C, 760, "임계·가중·상위 5", sub="sim≥0.40 · 지위 가중 · top-k 5")
    flow([(1330, 724), (1330, 745), (C, 745), (C, 760)])
    d3 = dec(C, 830)
    flow([(C, 804), (C, 830)])
    flow([(C + 18, 848), (860, 848), (860, 1290), (C + 150, 1290)], "[후보 없음]", 360, 840)
    # generation loop
    actn(1050, 890, "답 생성", 260, sub="qwen3:4b · JSON · think off")
    flow([(C, d3), (C, 878), (1050, 878), (1050, 890)], "[근거 있음]", C + 8, 894)
    actn(C, 970, "인용 검사", sub="ID · bigram · 부정어 · 숫자")
    flow([(1050, 934), (1050, 955), (C, 955), (C, 970)])
    d4 = dec(C, 1040)
    flow([(C, 1014), (C, 1040)])
    actn(C, 1110, "GENERATIVE 답", sub="확정 근거 없으면 접두 문구")
    flow([(C, d4), (C, 1110)], "[통과]", C + 8, 1100)
    d5 = dec(640, 1040)
    flow([(C + 18, 1058), (622, 1058)], "[실패]", 360, 1050)
    flow([(640, 1040), (640, 900), (920, 900)], "[첫 시도] / 실패 사유를 넣어", 650, 1000)
    d6 = dec(640, 1150)
    flow([(640, 1076), (640, 1150)], "[두 번째]", 648, 1120)
    actn(C, 1190, "BASELINE_EXTRACTIVE", sub="원문 발췌 (4000자 한도)")
    flow([(622, 1168), (560, 1168), (560, 1212), (C + 150, 1212)], "[1위 sim ≥ 0.45]", 500, 1160, "start")
    actn(C, 1280, "NONE (근거 없음)", sub="NO_GROUNDING")
    flow([(640, 1186), (640, 1312), (C + 150, 1312)], "[그 밖]", 648, 1240)
    # merge
    s.add(f'<polygon points="{C - 230},1360 {C - 212},1378 {C - 230},1396 {C - 248},1378" fill="#fff" stroke="{EDGE}" stroke-width="1.4"/>')
    for yy in (1132, 1212, 1302):
        s.line([(C - 150, yy), (C - 230, yy), (C - 230, 1360)], head=None, sw=1.4)
    actn(C, 1410, "저장 · 응답", sub="조건부 UPDATE · 원문 저장 안 함")
    flow([(C - 230, 1396), (C - 230, 1432), (C - 150, 1432)])
    s.add(f'<circle cx="{C + 220}" cy="1432" r="11" fill="#fff" stroke="{EDGE}" stroke-width="1.5"/><circle cx="{C + 220}" cy="1432" r="6" fill="{EDGE}"/>')
    flow([(C + 150, 1432), (C + 209, 1432)])
    for fy in (288, 488):
        flow([(825, fy), (859, fy)])
        s.add(f'<circle cx="870" cy="{fy}" r="11" fill="#fff" stroke="{EDGE}" stroke-width="1.5"/><circle cx="870" cy="{fy}" r="6" fill="{EDGE}"/>')
    note(s, 1210, 900, 250, ["모델 호출은 모두 트랜잭션", "밖에서(CLAUDE.md 규칙)", "호출 제한 25초 · 처리 60초"])
    s.save("pmart-rag-uml-5-activity.svg")


deployment()
component_diagram()
sequence()
state_machine()
activity()
