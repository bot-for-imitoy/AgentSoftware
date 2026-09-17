"""极简 UML 时序图渲染器（Pillow）。

为什么要自己画：graphviz 不擅长时序图，而时序图恰好最能说明“逻辑”。
排版约定：每条消息先占“标签区”再画线，避免相邻标签互相压。
"""

from __future__ import annotations

import pathlib
from PIL import Image, ImageDraw, ImageFont

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE / "out"

REG = "/usr/share/fonts/adobe-source-han-sans/SourceHanSansCN-Regular.otf"
BOLD = "/usr/share/fonts/adobe-source-han-sans/SourceHanSansCN-Bold.otf"

BG = "#ffffff"
INK = "#1f2937"
MUTED = "#6b7280"
LIFE = "#b8bfc9"
CALL = "#3f74b5"
RET = "#4f8b63"
SELF = "#7551b0"
NOTE_BG = "#fdf6e7"
NOTE_LINE = "#b8862c"
FRAME = "#8a929c"

LH = 21          # 行高
F = {"title": (BOLD, 26), "actor": (BOLD, 16), "sub": (REG, 12),
     "msg": (REG, 15), "note": (REG, 14), "small": (REG, 13),
     "frame": (BOLD, 14), "selftxt": (REG, 14)}

_cache: dict = {}


def _font(kind: str):
    if kind not in _cache:
        path, size = F[kind]
        _cache[kind] = ImageFont.truetype(path, size)
    return _cache[kind]


class Seq:
    def __init__(self, title, actors, gap=280, margin=70, note_gap=18, self_w=64):
        # actors: [(id, 名称, 副标题)]
        self.title = title
        self.actors = actors
        self.gap = gap
        self.margin = margin
        self.note_gap = note_gap
        self.self_w = self_w
        self.items: list[dict] = []
        self.frames: list[dict] = []
        self._open: list[dict] = []
        self._probe = ImageDraw.Draw(Image.new("RGB", (10, 10)))

    # ---------------- 内容 ----------------
    def call(self, src, dst, label, ret=False, color=None):
        self.items.append(dict(kind="msg", src=src, dst=dst, label=label, ret=ret,
                               color=color or (RET if ret else CALL)))

    def self_call(self, actor, label, color=SELF):
        self.items.append(dict(kind="self", src=actor, label=label, color=color))

    def note(self, text, over=None):
        self.items.append(dict(kind="note", text=text, over=over))

    def divider(self, text):
        self.items.append(dict(kind="divider", text=text))

    def frame_begin(self, label):
        f = dict(label=label, start=len(self.items), end=None, over=None)
        self.frames.append(f)
        self._open.append(f)

    def frame_end(self, over=None):
        f = self._open.pop()
        f["end"] = len(self.items)
        f["over"] = over

    # ---------------- 文本度量 ----------------
    def _w(self, text, font):
        return self._probe.textbbox((0, 0), text, font=font)[2]

    def _wrap(self, text, font, max_w):
        out: list[str] = []
        for para in str(text).split("\n"):
            words, cur = para.split(" "), ""
            for w in words:
                cand = w if not cur else f"{cur} {w}"
                if self._w(cand, font) <= max_w or not cur:
                    cur = cand
                else:
                    out.append(cur)
                    cur = w
            out.append(cur)
        # 单个词仍超宽 → 按字符硬切
        final = []
        for line in out:
            while self._w(line, font) > max_w and len(line) > 1:
                cut = len(line)
                while cut > 1 and self._w(line[:cut], font) > max_w:
                    cut -= 1
                final.append(line[:cut])
                line = line[cut:]
            final.append(line)
        return final or [""]

    # ---------------- 排版 ----------------
    def _x(self, actor):
        return self.margin + [a[0] for a in self.actors].index(actor) * self.gap + self.gap // 2

    def _note_span(self, it):
        over = it.get("over") or [self.actors[0][0], self.actors[-1][0]]
        xs = [self._x(a) for a in over]
        return min(xs), max(xs)

    def _layout(self):
        f_msg, f_note = _font("msg"), _font("note")
        y = 156
        for it in self.items:
            k = it["kind"]
            if k == "msg":
                lines = self._wrap(it["label"], f_msg, self.gap - 34)
                it["lines"] = lines
                it["label_y"] = y
                it["line_y"] = y + LH * len(lines) + 6
                y = it["line_y"] + 22
            elif k == "self":
                lines = self._wrap(it["label"], _font("selftxt"), 320)
                it["lines"] = lines
                it["label_y"] = y
                it["line_y"] = y + 26
                y = it["line_y"] + 40
            elif k == "note":
                x0, x1 = self._note_span(it)
                w = max(300, x1 - x0 + 160)
                lines = self._wrap(it["text"], f_note, w - 30)
                it["lines"], it["box_w"] = lines, w
                it["box_h"] = 20 + LH * len(lines)
                it["box_x"] = (x0 + x1) // 2 - w // 2
                it["y"] = y
                y += it["box_h"] + self.note_gap
            elif k == "divider":
                it["y"] = y + 10
                y += 76
        return y + 40

    def _needed_width(self):
        right = 0
        for it in self.items:
            if it["kind"] == "msg":
                cx = (self._x(it["src"]) + self._x(it["dst"])) // 2
                half = max(self._w(l, _font("msg")) for l in it["lines"]) // 2
                right = max(right, cx + half)
            elif it["kind"] == "self":
                right = max(right, self._x(it["src"]) + self.self_w + 20 +
                            max(self._w(l, _font("selftxt")) for l in it["lines"]))
            elif it["kind"] == "note":
                right = max(right, it["box_x"] + it["box_w"])
        return right + self.margin

    # ---------------- 绘制 ----------------
    def render(self, stem):
        height = self._layout()
        base = self.margin * 2 + (len(self.actors) - 1) * self.gap
        width = int(max(base, self._needed_width()))
        img = Image.new("RGB", (width, int(height)), BG)
        d = ImageDraw.Draw(img)

        d.text((self.margin // 2, 26), self.title, font=_font("title"), fill=INK)

        f_actor, f_sub = _font("actor"), _font("sub")
        head_top, head_h = 70, 44
        for aid, name, sub in self.actors:
            x = self._x(aid)
            w = max(self._w(name, f_actor), self._w(sub, f_sub)) + 36
            d.rounded_rectangle([x - w // 2, head_top, x + w // 2, head_top + head_h],
                                radius=8, fill="#eef3fa", outline="#3f74b5", width=2)
            d.text((x, head_top + head_h // 2 + 1), name, font=f_actor,
                   fill="#1f3a5f", anchor="mm")
            d.text((x, head_top + head_h + 16), sub, font=f_sub, fill=MUTED, anchor="mm")

        life_top = head_top + head_h + 30
        for aid, _, _ in self.actors:
            x, yy = self._x(aid), life_top
            while yy < height - 20:
                d.line([(x, yy), (x, min(yy + 9, height - 20))], fill=LIFE, width=2)
                yy += 16

        # 帧（loop / alt / opt）
        f_frame = _font("frame")
        for fr in self.frames:
            over = fr["over"] or [self.actors[0][0], self.actors[-1][0]]
            xs = [self._x(a) for a in over]
            x0, x1 = min(xs) - self.gap // 2 + 16, max(xs) + self.gap // 2 - 16
            y0 = self.items[fr["start"]].get("label_y", self.items[fr["start"]].get("y")) - 26
            last = self.items[fr["end"] - 1]
            y1 = last.get("line_y", last.get("y")) + 26
            d.rectangle([x0, y0, x1, y1], outline=FRAME, width=2)
            tw = self._w(fr["label"], f_frame)
            d.rectangle([x0, y0, x0 + tw + 28, y0 + 30], fill="#eef0f3", outline=FRAME, width=2)
            d.text((x0 + 14, y0 + 15), fr["label"], font=f_frame, fill="#374151", anchor="lm")

        f_msg, f_note, f_self = _font("msg"), _font("note"), _font("selftxt")
        for it in self.items:
            k = it["kind"]
            if k == "divider":
                y = it["y"]
                d.line([(self.margin // 2, y), (width - self.margin // 2, y)],
                       fill="#d7dbe1", width=2)
                d.text((self.margin // 2 + 10, y - 22), it["text"],
                       font=_font("small"), fill=MUTED)
            elif k == "note":
                x0, y0 = it["box_x"], it["y"]
                d.rectangle([x0, y0, x0 + it["box_w"], y0 + it["box_h"]],
                            fill=NOTE_BG, outline=NOTE_LINE, width=2)
                for i, ln in enumerate(it["lines"]):
                    d.text((x0 + 14, y0 + 10 + i * LH), ln, font=f_note, fill="#5b4a1f")
            elif k == "msg":
                x1, x2 = self._x(it["src"]), self._x(it["dst"])
                self._arrow(d, x1, x2, it["line_y"], filled=not it["ret"], color=it["color"])
                cx = (x1 + x2) // 2
                for i, ln in enumerate(it["lines"]):
                    yy = it["label_y"] + i * LH
                    tw = self._w(ln, f_msg)
                    d.rectangle([cx - tw // 2 - 5, yy - 2, cx + tw // 2 + 5, yy + LH - 1], fill=BG)
                    d.text((cx, yy), ln, font=f_msg, fill=it["color"], anchor="ma")
            elif k == "self":
                x, y = self._x(it["src"]), it["line_y"]
                d.line([(x, y), (x + self.self_w, y)], fill=it["color"], width=2)
                d.line([(x + self.self_w, y), (x + self.self_w, y + 30)], fill=it["color"], width=2)
                self._arrow(d, x + self.self_w, x, y + 30, filled=True, color=it["color"])
                for i, ln in enumerate(it["lines"]):
                    d.text((x + self.self_w + 14, it["label_y"] + i * LH), ln,
                           font=f_self, fill=it["color"])

        OUT.mkdir(parents=True, exist_ok=True)
        path = OUT / f"{stem}.png"
        img.save(path)
        print(f"  ✔ {stem:<24} {path.name} ({path.stat().st_size // 1024}KB)  {img.size[0]}x{img.size[1]}")
        return path

    def _arrow(self, d, x1, x2, y, filled, color):
        head = 12 if filled else 11
        if x2 >= x1:
            d.line([(x1, y), (x2 - head + 2, y)], fill=color, width=2)
            if filled:
                d.polygon([(x2, y), (x2 - head, y - 6), (x2 - head, y + 6)], fill=color)
            else:
                d.line([(x2 - head, y - 6), (x2, y), (x2 - head, y + 6)], fill=color, width=2)
        else:
            d.line([(x1, y), (x2 + head - 2, y)], fill=color, width=2)
            if filled:
                d.polygon([(x2, y), (x2 + head, y - 6), (x2 + head, y + 6)], fill=color)
            else:
                d.line([(x2 + head, y - 6), (x2, y), (x2 + head, y + 6)], fill=color, width=2)
