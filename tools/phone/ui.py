#!/usr/bin/env python3
"""真機測試輔助：解析 `uiautomator dump --windows` 的結果，並依文字找座標。

由 t9.sh 呼叫，也可單獨使用：
  python ui.py read      <ui.xml>              # 印出 輸入框 / 候選列 / 左側注音組合
  python ui.py edit      <ui.xml>              # 第一個 EditText 的中心座標
  python ui.py tapcand   <ui.xml> <候選文字>   # 候選列中該文字的中心座標
  python ui.py tapbtn    <ui.xml> <按鈕文字>   # 鍵盤區域中該文字的中心座標
  python ui.py z2k       <注音字串>            # 注音 -> 九鍵鍵號（聲調忽略），例如 ㄅㄤㄨㄛ -> 1 9 9 4
  python ui.py fulltype  <ui.xml> <注音字串>   # 全鍵盤：依鍵面文字逐鍵點擊（需要環境變數 ADB）

座標區間預設針對 1080x2400 螢幕，其他解析度可用環境變數覆寫：
  CAND_Y_MIN / CAND_Y_MAX   候選列 TextView 的 y 範圍 (預設 1190~1240)
  KB_Y_MIN                  鍵盤區域起點 y (預設 1150)
"""
import os, re, subprocess, sys, time
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding='utf-8')
IME_PKG = 'com.bopomofo.t9ime'
CAND_Y_MIN = int(os.environ.get('CAND_Y_MIN', 1190))
CAND_Y_MAX = int(os.environ.get('CAND_Y_MAX', 1240))
KB_Y_MIN = int(os.environ.get('KB_Y_MIN', 1150))

# 九鍵鍵位定義（與 KeyMapping.KEY_DEFINITIONS 一致）
KEYS = {1: 'ㄅㄉㄚ', 2: 'ㄍㄐㄞ', 3: 'ㄓㄗㄢㄦ', 4: 'ㄆㄊㄛ', 5: 'ㄎㄑㄟ', 6: 'ㄔㄘㄣㄧ',
        7: 'ㄇㄋㄜ', 8: 'ㄏㄒㄠㄡ', 9: 'ㄕㄙㄤㄨ', 10: 'ㄈㄌㄝ', 11: 'ˇˋˊ˙', 12: 'ㄖㄥㄩ'}
C2K = {c: k for k, cs in KEYS.items() for c in cs}


def nodes(path):
    for n in ET.parse(path).iter('node'):
        b = n.get('bounds')
        if not b:
            continue
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', b))
        yield n, x1, y1, x2, y2


def center(x1, y1, x2, y2):
    return (x1 + x2) // 2, (y1 + y2) // 2


def cmd_read(path):
    cand, combo, edit = [], [], []
    for n, x1, y1, x2, y2 in nodes(path):
        txt, cls, pkg = n.get('text'), n.get('class', '').split('.')[-1], n.get('package')
        if not txt:
            continue
        if cls == 'EditText':
            edit.append(txt)
        if pkg != IME_PKG:
            continue
        if cls == 'TextView' and CAND_Y_MIN <= y1 <= CAND_Y_MAX and txt != '›':
            cand.append((x1, txt))
        # 左側注音組合：x 很靠左的 Button，且在鍵盤區域內
        if cls == 'Button' and x1 < 20 and y1 > 1380 and y2 < 2200:
            combo.append((y1, txt))
    print('輸入框:', edit)
    print('候選列:', [t for _, t in sorted(cand)])
    print('左側組合:', [t for _, t in sorted(combo)])
    print('(注意：uiautomator 只看得到畫面內的節點，要橫向捲動才看得到的候選不會列出)')


def cmd_edit(path):
    for n, *b in nodes(path):
        if n.get('class', '').endswith('EditText'):
            print(*center(*b))
            return


def cmd_tapcand(path, want):
    for n, x1, y1, x2, y2 in nodes(path):
        if (n.get('package') == IME_PKG and n.get('class', '').endswith('TextView')
                and n.get('text') == want and CAND_Y_MIN <= y1 <= CAND_Y_MAX):
            print(*center(x1, y1, x2, y2))
            return


def cmd_tapbtn(path, want):
    for n, x1, y1, x2, y2 in nodes(path):
        if n.get('package') == IME_PKG and n.get('text') == want and y1 > KB_Y_MIN:
            print(*center(x1, y1, x2, y2))
            return


def cmd_z2k(text):
    print(' '.join(str(C2K[c]) for c in text if c in C2K and C2K[c] != 11))


def cmd_fulltype(path, text):
    adb = os.environ['ADB']
    centers = {}
    for n, x1, y1, x2, y2 in nodes(path):
        t = n.get('text')
        if n.get('package') == IME_PKG and t and len(t) == 1 and y1 > 1380 and y2 < 2120:
            centers[t] = center(x1, y1, x2, y2)
    for ch in text:
        if ch not in centers:
            print('缺鍵', ch)
            continue
        x, y = centers[ch]
        subprocess.run([adb, 'shell', 'input', 'tap', str(x), str(y)],
                       stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL)
        time.sleep(0.25)


if __name__ == '__main__':
    cmd, args = sys.argv[1], sys.argv[2:]
    {'read': cmd_read, 'edit': cmd_edit, 'tapcand': cmd_tapcand, 'tapbtn': cmd_tapbtn,
     'z2k': cmd_z2k, 'fulltype': cmd_fulltype}[cmd](*args)
