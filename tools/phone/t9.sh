#!/bin/bash
# 真機自動化測試：用 adb 在手機上操作九鍵輸入法，並讀回候選列 / 左側注音組合。
# 完整教學見 docs/DEV_TESTING_GUIDE.md。
#
# 用法:  bash tools/phone/t9.sh <子命令> [參數]
#   setup  <apk>        安裝 APK、啟用並設為預設輸入法
#   open                喚醒螢幕、開啟 App 的測試輸入框並叫出鍵盤
#   type   "z:ㄅㄤㄨㄛ"  九鍵連打（z: 後面接注音，聲調忽略）；也可直接給鍵號 "1 9 9 4"
#   clear               清空九鍵組字（按「清空」）
#   pick   <候選文字>    點選候選列中的某個候選（例如部分上屏候選）
#   tap    <按鈕文字>    點選鍵盤區的任一按鈕（例如左側注音組合、"全鍵·繁"）
#   read                只讀回目前畫面的 輸入框 / 候選列 / 左側組合
#   sentences <檔案>     批次連打整句；檔案每行「目標句|注音」
#   full   "ㄐㄧㄣㄊㄧㄢ" 全鍵盤模式逐鍵輸入並讀回候選（需先 tap "全鍵·繁" 切到全鍵盤）
#   fullclear           全鍵盤清空（連點 ⌫）
#   restore             把預設輸入法還原成 Gboard（測完請務必執行）
#
# 座標以 1080x2400 的手機為準（OnePlus 9R）。其他機型請先 `read` / 截圖確認，必要時調整下方座標表。
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export MSYS_NO_PATHCONV=1                      # Git Bash 不要把 /sdcard 轉成 Windows 路徑
IME_ID="com.bopomofo.t9ime/.ZhuyinInputMethodService"
GBOARD_ID="com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
UI_XML="$HERE/.ui.xml"

if [ -z "$ADB" ]; then
  for c in "$(command -v adb 2>/dev/null)" \
           "$(cygpath -u "$LOCALAPPDATA" 2>/dev/null)/Android/Sdk/platform-tools/adb.exe" \
           "$ANDROID_HOME/platform-tools/adb" "$HOME/Android/Sdk/platform-tools/adb"; do
    [ -n "$c" ] && [ -x "$c" ] && ADB="$c" && break
  done
fi
[ -z "$ADB" ] && { echo "找不到 adb：請設定環境變數 ADB 或 ANDROID_HOME"; exit 2; }
export ADB
PY=python; command -v python >/dev/null 2>&1 || PY=python3
w() { command -v cygpath >/dev/null 2>&1 && cygpath -w "$1" || echo "$1"; }   # 傳給 Windows 版 python 的路徑
adb_() { "$ADB" "$@" </dev/null; }             # 一律 </dev/null：adb shell 會吃掉 while-read 迴圈的 stdin！
ui() { "$PY" "$(w "$HERE/ui.py")" "$@"; }

# 九鍵鍵位座標（1080x2400）
declare -A KX=([1]=284 [2]=539 [3]=794 [4]=284 [5]=539 [6]=794 [7]=284 [8]=539 [9]=794 [10]=284 [11]=539 [12]=794)
declare -A KY=([1]=1484 [2]=1484 [3]=1484 [4]=1666 [5]=1666 [6]=1666 [7]=1846 [8]=1846 [9]=1846 [10]=2026 [11]=2026 [12]=2026)
CLEAR_X=1000; CLEAR_Y=1756          # 九鍵「清空」
FULL_BACK_X=1020; FULL_BACK_Y=1485  # 全鍵盤 ⌫

require_ime() {   # 保護：預設輸入法不是我們的就不要亂點（否則會點到別人的鍵盤/設定頁）
  local def; def=$(adb_ shell settings get secure default_input_method | tr -d '\r')
  if [ "$def" != "$IME_ID" ]; then echo "中止：預設輸入法不是 t9ime（目前 $def）。請先 setup 或 adb shell ime set $IME_ID"; exit 9; fi
}
dump() { adb_ shell uiautomator dump --windows /sdcard/ui.xml >/dev/null 2>&1; adb_ shell cat /sdcard/ui.xml > "$UI_XML"; }
tap_xy() { adb_ shell input tap "$1" "$2"; }

cmd_setup() {
  adb_ install -r "$1" | tail -1
  adb_ shell ime enable "$IME_ID" >/dev/null
  adb_ shell ime set "$IME_ID"                       # 重新安裝後系統常把預設輸入法切回 Gboard，一定要再設一次
}
cmd_open() {
  require_ime
  adb_ shell input keyevent KEYCODE_WAKEUP; sleep 1
  adb_ shell input swipe 540 2000 540 600 250; sleep 1            # 滑掉鎖屏（無密碼時）
  adb_ shell am start -n com.bopomofo.t9ime/.MainActivity >/dev/null 2>&1; sleep 2   # 千萬不要 am force-stop：會讓系統把預設輸入法切回 Gboard
  for s in 1 2 3 4; do adb_ shell input swipe 540 700 540 2000 200; sleep 0.4; done # 先捲到頂
  local P=""
  for i in 1 2 3 4 5 6 7 8; do                                    # 再小步往下捲，直到測試輸入框出現
    dump; P=$(ui edit "$(w "$UI_XML")")
    [ -n "$P" ] && break
    adb_ shell input swipe 540 1500 540 1000 700; sleep 0.8
  done
  [ -z "$P" ] && { echo "找不到測試輸入框"; exit 3; }
  set -- $P; tap_xy "$1" "$2"; sleep 2.5
  echo "已點選輸入框 ($P)"
}
cmd_clear() { require_ime; tap_xy $CLEAR_X $CLEAR_Y; sleep 0.4; }
cmd_read() { dump; ui read "$(w "$UI_XML")"; }
cmd_type() {
  require_ime
  local seq="$1"
  [[ "$seq" == z:* ]] && seq=$(ui z2k "${seq#z:}")
  for k in $seq; do tap_xy "${KX[$k]}" "${KY[$k]}"; sleep 0.25; done
  sleep 0.6; cmd_read
}
cmd_pick() {
  dump; local P; P=$(ui tapcand "$(w "$UI_XML")" "$1")
  [ -z "$P" ] && { echo "找不到候選: $1（可能在畫面外，需要橫向捲動候選列或按 ▼）"; exit 1; }
  set -- $P; tap_xy "$1" "$2"; sleep 0.8; cmd_read
}
cmd_tap() {
  dump; local P; P=$(ui tapbtn "$(w "$UI_XML")" "$1")
  [ -z "$P" ] && { echo "找不到按鈕: $1"; exit 1; }
  set -- $P; tap_xy "$1" "$2"; sleep 0.8; cmd_read
}
cmd_sentences() {
  while IFS='|' read -r -u 3 target zy; do
    [ -z "$target" ] && continue
    cmd_clear; local R; R=$(cmd_type "z:$zy")
    echo "$target  =>  $(echo "$R" | grep '候選列' | sed 's/候選列: //')"
  done 3< "$1"
  cmd_clear
}
cmd_full() { dump; ui fulltype "$(w "$UI_XML")" "$1"; sleep 0.7; cmd_read; }
cmd_fullclear() { for i in $(seq 1 18); do tap_xy $FULL_BACK_X $FULL_BACK_Y; done; sleep 0.5; }
cmd_restore() { adb_ shell ime set "$GBOARD_ID"; adb_ shell input keyevent KEYCODE_HOME; }

sub="$1"; shift
case "$sub" in
  setup) cmd_setup "$@";; open) cmd_open;; type) cmd_type "$@";; clear) cmd_clear;; pick) cmd_pick "$@";;
  tap) cmd_tap "$@";; read) cmd_read;; sentences) cmd_sentences "$@";; full) cmd_full "$@";;
  fullclear) cmd_fullclear;; restore) cmd_restore;;
  *) sed -n '2,22p' "$0";;
esac
