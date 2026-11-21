"""
產生五種機身配色的 App 圖示（res/drawable/ic_launcher_<主題>.xml 等）。
以透紫（purple）的 ic_launcher_fg.xml / ic_launcher_bg.xml 為範本，把顏色換成各主題的機身配色。
改了範本後重新執行：  python tools/gen_icons.py
"""
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "drawable")

# 範本裡的顏色（不含透明度的 6 碼） -> 各主題的顏色
THEMES = {
    "purple": {},
    "black": {
        "E3D8FF": "4A4A52", "B9A2EE": "2A2A30", "8C6BD6": "141417",
        "DCCFFF": "44444C", "B39AEC": "26262B", "805FCE": "111113",
        "C7A8FF": "9A9AA2", "A983E8": "8A8A98", "6B58A0": "050506",
        "4A3478": "3C3C46", "110C1C": "0A0A0C", "F4EEFF": "F4F4F8", "D6C4FF": "C8C8D2",
        "4E3D78": "0E0E10",
        "3A2D55": "3A3A42", "1F1830": "24242A", "120E1A": "141418", "C3B9DE": "C8C8D0", "C9B8F2": "D0D0DA",
    },
    "bit16": {
        "E3D8FF": "F2EFEA", "B9A2EE": "D8D2C8", "8C6BD6": "B3AA9C",
        "DCCFFF": "EEEAE4", "B39AEC": "D2CBC0", "805FCE": "ADA496",
        "C7A8FF": "E8E2D8", "A983E8": "C9C0B2", "6B58A0": "8E867A",
        "4A3478": "7A3A2A", "110C1C": "120E0C", "F4EEFF": "FFF2E6", "D6C4FF": "FFC9A8",
        "4E3D78": "6F675C",
        "3A2D55": "3A3530", "1F1830": "221F1B", "120E1A": "141210", "C3B9DE": "D6D0C6", "C9B8F2": "E2DCD2",
    },
    "gc": {
        "E3D8FF": "9A94FF", "B9A2EE": "6F68E8", "8C6BD6": "4A42C8",
        "DCCFFF": "948EFA", "B39AEC": "6A62E2", "805FCE": "453CC0",
        "C7A8FF": "B4AEFF", "A983E8": "7C76E6", "6B58A0": "2A2470",
        "4A3478": "5A2898", "110C1C": "0E0B24", "F4EEFF": "F1EEFF", "D6C4FF": "D2C2FF",
        "4E3D78": "F2C230",
        "3A2D55": "2C2870", "1F1830": "1A1844", "120E1A": "100E2A", "C3B9DE": "9C96F0", "C9B8F2": "ACA6FF",
    },
    "ice": {
        "E3D8FF": "D8F4F0", "B9A2EE": "8FCFC6", "8C6BD6": "4FA59C",
        "DCCFFF": "D2F0EC", "B39AEC": "88C9C0", "805FCE": "4A9E95",
        "C7A8FF": "A8E8DF", "A983E8": "52B5AA", "6B58A0": "2C5E58",
        "4A3478": "2E5E4C", "110C1C": "0A1412", "F4EEFF": "EEFFF9", "D6C4FF": "B6F0E2",
        "4E3D78": "2E6A62",
        "3A2D55": "21433F", "1F1830": "152825", "120E1A": "0D1817", "C3B9DE": "A5D8D2", "C9B8F2": "A8E2DA",
    },
}

ADAPTIVE = """<?xml version="1.0" encoding="utf-8"?>
<!-- 自動產生（tools/gen_icons.py）：{name} 配色的 App 圖示 -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_bg_{name}" />
    <foreground android:drawable="@drawable/ic_launcher_fg_{name}" />
    <monochrome android:drawable="@drawable/ic_launcher_mono" />
</adaptive-icon>
"""


def recolor(text, table):
    for src, dst in table.items():
        text = text.replace(src, dst).replace(src.lower(), dst)
    return text


def main():
    fg = open(os.path.join(ROOT, "ic_launcher_fg.xml"), encoding="utf-8").read()
    bg = open(os.path.join(ROOT, "ic_launcher_bg.xml"), encoding="utf-8").read()
    for name, table in THEMES.items():
        note = "<!-- 自動產生（tools/gen_icons.py），請改範本 ic_launcher_fg.xml / ic_launcher_bg.xml -->\n"
        for kind, src in (("fg", fg), ("bg", bg)):
            out = recolor(src, table).replace("<vector", note + "<vector", 1)
            with open(os.path.join(ROOT, f"ic_launcher_{kind}_{name}.xml"), "w", encoding="utf-8", newline="\n") as f:
                f.write(out)
        with open(os.path.join(ROOT, f"ic_launcher_{name}.xml"), "w", encoding="utf-8", newline="\n") as f:
            f.write(ADAPTIVE.format(name=name))
        print("ok", name)


if __name__ == "__main__":
    main()
