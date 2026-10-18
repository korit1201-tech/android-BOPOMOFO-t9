# 開發與測試教學（給下一位開發者 / agent）

> 重點：**怎麼不用 build APK 就快速量化演算法改動（Python 模擬器 `tools/t9sim`）**、**怎麼用手機自動化驗證（`tools/phone/t9.sh`）**、以及**我們踩過的坑**。
> 改完請務必照第 8 節檢查表驗證。改善規劃見 `docs/ROADMAP_TODO.md`。

## 0. 接手狀態（2026-10-12，v2.5.0）⚠️ 先讀這段

- 程式碼與文件已全部寫進專案工作目錄，**但還沒 commit、沒 build release APK、沒發 GitHub release**：
  開發中途 shell 工具因工作目錄（網路共用路徑，主機名稱無法解析）失效，改用有效路徑後檔案工具可用、shell 仍起不來。
- 未提交的變更：`app/src/main/java/.../ZhuyinInputMethodService.kt`、`engine/TrieDictionary.kt`、`engine/ZhuyinT9Engine.kt`、`engine/SyllableManager.kt`、
  `app/build.gradle.kts`（versionCode 80 / versionName 2.5.0）、`CHANGELOG.md`、`docs/*`、`tools/*`。
- **待辦（照順序）**：
  1. 在新的 session 用有效的工作目錄開啟（建議直接用本機路徑或把專案複製到本機）。
  2. 編 release APK（第 3 節）→ 真機裝一次，跑第 8 節檢查表，**特別補驗：九鍵連打 `ㄅㄤㄨㄛ`，往下捲左側組合找到 `ㄅㄤㄨㄛ`，點選後候選應出現「幫我」**（CHANGELOG 已標明此項未在真機完整驗證）。
  3. **簽章比對（第 9 節）**：`gh release download v2.4.1 -p '*.apk'`，用 `apksigner verify --print-certs` 比對新舊 APK 的 SHA-256。**不同就不要發布為 latest**（會讓使用者無法 App 內升級）。
  4. `.gitignore` 補上 `__pycache__/` 與 `tools/phone/.ui.xml`（`tools/t9sim/__pycache__` 目前存在）。CHANGELOG 的 v2.4.2 / v2.4.3 條目沒有日期，請用 `git log` 補。
  5. `git add` 上述檔案 → `git commit -m "feat: 九鍵斷詞與注音組合重構 ... (v2.5.0)"` → `git tag v2.5.0` → `git push origin main --tags` →
     `gh release create v2.5.0 <apk> --title ... --notes-file ...`（release notes 取自 CHANGELOG v2.5.0 段落；資產命名沿用 `bopomofo-v2.5.0.apk`）。
- 本地工作區在 v2.4.3 之後沒有其他人的未提交變更（開工時 `git status` 為乾淨）。

## 1. 專案結構：三種輸入模式的邏輯是**各自獨立**的

| 模式 | 進入點 | 核心查詢 |
|---|---|---|
| **九鍵 (T9)** | `ZhuyinT9Engine.pressKey / recalculate` | `trie.searchExact`、`trie.searchPrefixBest`、`t9*` 斷詞函式 |
| **全鍵盤 (41 鍵大千)** | `ZhuyinT9Engine.searchFullZhuyin(inputZhuyin)` | `fullZhuyinWordMap`、`soundToCharMap`、`initialMap`、`findBestSentence` |
| **實體鍵盤** | `ZhuyinInputMethodService.handlePhysical*` / `onKeyDown` | `searchFullZhuyin`、`findBestSentenceFromSyllables`、`getHomophonesForChar` |

> 🔴 **改任何一個模式前，先確認你要動的函式有沒有被別的模式呼叫。** **共用元件**（改了會同時影響多個模式）：
> `TrieDictionary`（`insert`/`searchExact`/`searchPrefix`/`searchNode`；全鍵盤也用 `searchPrefix`）、
> `ZhuyinT9Engine.findBestSentence`/`findBestSentenceSegments`（全鍵盤用）、`SyllableManager`、`UserDictionaryManager`、`learnWord`、`DictEntry`、
> `ZhuyinInputMethodService.selectCandidate`（三種模式都經過；v2.5.0 的部分上屏分支只在 `entry.consumedKeys > 0` 且九鍵模式觸發）。
> **做法**：要改九鍵就「新增九鍵專用函式」，不要改共用函式的行為（v2.5.0 新增 `searchPrefixBest`、`t9ForwardPaths`… 而沒有動 `searchPrefix`、`findBestSentence`）；
> 新增欄位一律給預設值（如 `DictEntry.consumedKeys = -1`）。九鍵鍵位定義見 `KeyMapping.KEY_DEFINITIONS`（K11 是聲調鍵，一鍵循環 ˇ ˋ ˊ ˙）。

## 2. 開發環境（Windows + Git Bash）

- **JDK**：Android Studio 內建 JBR：`C:\Program Files\Android\Android Studio\jbr`（系統 PATH 沒有 java）。**SDK**：`%LOCALAPPDATA%\Android\Sdk`（platforms/android-34、build-tools/34.0.0、platform-tools）。
- **Gradle**：wrapper 8.6，離線快取在 `~/.gradle`，**請加 `--offline`**。
- ⚠️ `local.properties` 的 `sdk.dir` 可能指向特定開發環境路徑，**不要改使用者的這個檔案**；用「獨立 build 目錄」（第 3 節）。
- ⚠️ 單元測試需要 `junit:junit:4.13.2`，離線快取沒有 → 跑 `testDebugUnitTest` 需連網。
- ⚠️ 既有單元測試把引擎排序邏輯**複製一份**在測試內，測不到真引擎（引擎吃 Android `Context`）。要真正測引擎需 Robolectric 或抽出純 Kotlin（見 ROADMAP E2）。
- 專案放網路共用資料夾時：Git Bash 要 `git -c safe.directory='*'`；**共用資料夾斷線或主機名稱解析失敗，shell / Grep 工具會整個失效（工作目錄不可用）**，改用 IP 路徑或本機副本。

## 3. 建置 APK（獨立 build 目錄）

```powershell
$src = "\\<host-or-ip>\ai\ime"      # 專案根目錄（或本機路徑）
$dst = "C:\temp\t9build"             # 任意本機資料夾
if (Test-Path "$dst\app\src") { Remove-Item -Recurse -Force "$dst\app\src" }   # 先刪，避免舊的暫時 patch 殘留
robocopy $src $dst /E /XD build .gradle .git .idea /XF *.apk local.properties
"sdk.dir=C\:\\Users\\<你>\\AppData\\Local\\Android\\Sdk" | Set-Content "$dst\local.properties" -Encoding ascii
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
cd $dst
.\gradlew.bat assembleDebug --offline          # app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat assembleRelease --offline        # app\build\outputs\apk\release\app-release.apk
```

- PowerShell 腳本檔可能被執行原則擋：`powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1`。
- `release` 目前用 **debug 簽章**（`signingConfig = debug`），見第 9 節。

## 4. Python 模擬器 `tools/t9sim/`

**目的**：九鍵演算法很難靠肉眼判斷好壞，build＋安裝＋手點一輪要好幾分鐘。模擬器把詞庫載入、Trie、排序、斷詞逐行移植成 Python，**幾秒內**用固定評測集量化「舊 vs 新」。
> ⚠️ 模擬器是**近似**不是等價。v2.5.0 開發中它對真機輸出的吻合度很高（候選與左側組合逐項相同），但**最終以真機為準**；Kotlin 改了、模擬器沒跟著改就會失真，請同步維護。

| 檔案 | 內容 | 對應 Kotlin |
|---|---|---|
| `sim.py` | 核心：詞庫載入、`seq`、Trie（含跨鍵位容錯）、**舊版**`search_prefix`（DFS 前 50）、`eff_weight`、`run()` = **v2.4.3 舊邏輯** | `recalculate`（舊）、`TrieDictionary` |
| `v2.py` | **新**斷詞：`seg_cost`、`kbest_paths`、`tail_paths`、`run2()` | `t9SegmentCost`、`t9ForwardPaths`、`t9MergeSentenceCandidates` |
| `v3.py` | **新**後向 DP：`suffix_best`、`partial_cands` | `t9SuffixCosts`、`t9PartialCandidates` |
| `sylsplit.py` | **新**音節切分組合＋命中率評測 | `SyllableManager.getSyllableSplitCombinations` |
| `sents.py` | 49 句自然句評測集 `SENTS` | — |
| `bench2.py` | 單詞評測集 `setA` 常用詞、`setB` 中頻詞、`setC` 合成連打句；`PREF` 真實詞注音前綴集合 | — |
| `midstate.py` | 「打到一半」狀態評測（舊 vs 新） | — |
| `steps3.py` | 逐步選詞可行率（含部分上屏） | 部分上屏 |

```bash
python tools/t9sim/sim.py        # 載入詞庫，印出 loaded 165374 ...（確認環境 OK，約 10~20 秒）
python tools/t9sim/bench2.py     # 舊邏輯在 A/B/C 組的 top1/top5、左側組合不自然比例
python tools/t9sim/midstate.py   # 打到一半：舊 vs 新
python tools/t9sim/steps3.py     # 逐步選詞可行率
python tools/t9sim/sylsplit.py   # 音節切分組合命中率
cd tools/t9sim && python -i sim.py   # REPL：ks = seq("ㄅㄤㄨㄛ", True); cl, combos, _ = run(ks); import v2, v3; v2.run2(ks)
```
詞庫與合法音節直接從專案內讀（`app/src/main/assets/dict_tw.txt`、`SyllableManager.kt`）；專案根目錄預設為 `tools/t9sim` 往上兩層，可用 `T9_REPO` 覆寫。

**指標怎麼看**：**打到一半**（第 3 鍵到「少一鍵」每個狀態）才是日常打字最常遇到的情況；**composite 佔第一**＝整句組合（注音含空白）排第一的比例（v2.4.3 約 20.8%，就是怪詞來源）；
**左側組合不自然**對「字典沒有的詞」（如幫我）不公平，判斷音節切分請看 `sylsplit.py` 的 top-N 命中率；`steps3.py` 刻意用**不含聲調**的鍵序，否則「消耗 k 鍵」會跟 K11 錯位。
**不要對 49 句自然句過擬合**：新增錯誤類型就擴充 `sents.py`，並看 A/B/C 是否退步。純詞頻模型上限約自然句 top1 15~16/49，想再提升需要上下文語言模型（ROADMAP A1）。

## 5. 真機自動化測試 `tools/phone/`

**前置**：手機開 USB 偵錯並在手機上**點「允許」**（`adb devices` 顯示 `unauthorized` 就是還沒授權）；找得到 adb（`t9.sh` 自動找，或 `export ADB=...`）；手機接電源、螢幕逾時調長（部分機型不能用 `svc power stayon`）。

| 子命令（`bash tools/phone/t9.sh ...`） | 作用 |
|---|---|
| `setup <apk>` | 安裝、`ime enable`、`ime set`（**重新安裝後系統會把預設輸入法切回 Gboard，一定要 set**） |
| `open` | 喚醒、滑開鎖屏、開 App、捲到「打字效果即時測試」輸入框並點選叫出鍵盤 |
| `type "z:ㄅㄤㄨㄛ"` | 九鍵連打（`z:`＋注音，聲調忽略；或直接給鍵號 `"1 9 9 4"`），打完讀回 輸入框/候選列/左側組合 |
| `clear` / `pick "今天"` / `tap "全鍵·繁"` / `read` | 清空 / 點候選（部分上屏）/ 點鍵盤區任一按鈕 / 只讀回 |
| `sentences 檔案` | 批次連打，每行 `目標句|注音` |
| `full "ㄐㄧㄣㄊㄧㄢ"` / `fullclear` | 全鍵盤輸入 / 清空（先 `tap "全鍵·繁"` 切模式） |
| `restore` | 還原預設輸入法為 Gboard（**測完務必執行**） |

讀回原理：`uiautomator dump --windows` 輸出所有視窗（含輸入法視窗）成 XML，`ui.py` 解析。**uiautomator 只看得到畫面內的節點**，要橫向捲動才看得到的候選不會列出。
座標是 **1080x2400（OnePlus 9R）**；其他機型請調整 `t9.sh` 座標表與 `ui.py` 的 `CAND_Y_*`。
**效能量測**（用完即丟、不要 commit）：在 build 目錄的 `recalculate()` 外包 `System.nanoTime()` + `Log.d("T9PERF", ...)`，`adb logcat -d -s T9PERF`；
**新舊都要加同樣計時、且看暖機後第二輪**（第一輪含 JIT，高 2~3 倍）。v2.5.0：新 11.5 ms／舊 10.9 ms（每鍵）。

## 6. 踩過的坑

| 現象 | 原因 | 解法 |
|---|---|---|
| 測到一半輸入法變回 Gboard，點擊打到 Gboard 設定頁 | `adb install -r` 或 **`am force-stop` 輸入法套件**會讓系統退回預設輸入法 | 不要 force-stop；安裝後一律 `ime set`；`t9.sh` 內建 `require_ime` 檢查 |
| `while read` 迴圈只跑第一筆 | `adb shell` 吃掉迴圈 stdin | adb 一律 `</dev/null`，迴圈用 `read -u 3` |
| 用 `Ctrl+A` 清輸入框，鍵盤多出選單 | 組字中全選會觸發既有的「選取→同音字改字」 | 用 `clear` / `fullclear` |
| 截圖全黑 | 螢幕熄了/鎖屏 | `KEYCODE_WAKEUP` ＋往上滑 |
| PowerShell 重導向弄壞 `screencap` PNG | PowerShell 轉碼 | 截圖用 Git Bash 的 `>` |
| `/sdcard/...` 變成 `C:/Program Files/Git/sdcard/...` | MSYS 路徑轉換 | `export MSYS_NO_PATHCONV=1` |
| `uiautomator dump` 看不到輸入法視窗 | 預設只 dump 前景 App | 加 `--windows` |
| Windows 版 Python 讀不到 `/c/Users/...` | 路徑格式 | `cygpath -w` |
| 重啟 App 後模式回到九鍵 | 模式不持久化 | 測全鍵盤要 `tap "全鍵·繁"` |
| `svc power stayon` / `settings put global stay_on_while_plugged_in` 被拒 | 缺 WRITE_SETTINGS 權限 | 手動在手機設定調長螢幕逾時 |
| 共用資料夾斷線 → shell/Grep 全失效 | 工作目錄不可用 | 改用 IP 路徑或本機副本，重開 session |

## 7. Kotlin ↔ 模擬器對照（改一邊請同步另一邊）

| Kotlin | Python |
|---|---|
| `T9_WORD_PENALTY=3.5`、`T9_TOLERANT_PENALTY=8.0` | `v2.P.WORD_PEN`、`seg_cost` 的 `+8.0` |
| `T9_TAIL_MISS=2.5`、`T9_TAIL_DEPTH=1`、`T9_TAIL_PER_NODE=3` | `v2.P.TAIL_MISS`、`subtree_top` |
| `T9_BEAM=4` | `v2.P.BEAM` |
| `T9_DP_WEIGHT_CAP=600_000`（個人學習詞上限） | 無（模擬器無學習詞） |
| `TrieNode.TOP_ENTRIES_SIZE=16`、`PREFIX_PER_NODE_LIMIT=6`、前綴深度表 1→1/2→2/3→3/4+→4 | 實驗結果見 CHANGELOG v2.5.0 |
| `T9_PARTIAL_LIMIT=8`、部分上屏插入位置規則 | `v3.partial_cands(limit=8)`；插入規則以 Kotlin 為準 |
| `SPLIT_SYLLABLE_PENALTY=4.0`、`T9_SPLIT_COMBO_LIMIT=6`、`T9_WORD_COMBOS_FIRST=5` | `sylsplit.splits(pen=4)` |

## 8. 改九鍵邏輯後的驗證檢查表

1. **模擬器**：`bench2/midstate/steps3` 沒有退步（A/B/C 三組都要看）。
2. **真機九鍵**：連打 ≥5 個詞與句子；`pick` 部分上屏；聲調鍵、退格、點左側組合（鎖定）、`clear`。
3. **真機全鍵盤回歸**：`tap "全鍵·繁"` 後用 `full` 輸入 `ㄐㄧㄣㄊㄧㄢ`、`ㄨㄛㄒㄧㄤㄔㄈㄢ`、`ㄒㄧㄝㄒㄧㄝ`、`ㄐㄊ`、`ㄓㄉㄠˋ`、`ㄋㄧˇㄏㄠˇ`，
   **原始版與新版各跑一次，輸出必須逐字相同**（v2.5.0 這樣驗證過）。
4. **實體鍵盤**：有藍牙/USB 鍵盤時手動驗證注音輸入、候選選字、游標改字；沒有時至少確認沒碰到 `handlePhysical*` 與 `searchFullZhuyin`。
5. **效能**：暖機後平均每鍵 `recalculate` 不明顯高於舊版（基準 ~11 ms）。
6. 測完 `t9.sh restore`。

## 9. 發版流程與簽章（⚠️ 重要）

1. 升版號：`app/build.gradle.kts` 的 `versionCode`（+1）與 `versionName`（新功能 minor，修 bug patch）。
2. `CHANGELOG.md` 最上面新增條目：**根因、修正、效果（附量測方式與數字）、影響範圍、已知限制**；README 同步更新。
3. 編 release APK（第 3 節）。GitHub release 資產名稱沿用 `bopomofo-v<版本>.apk`（App 內更新器找 `.apk` 結尾檔）。
4. ⚠️ **簽章**：`release` 沿用 **debug 簽章**（本機 `~/.android/debug.keystore`，每台電腦不同）。Android 只允許簽章相同的 APK 覆蓋安裝；
   新電腦編出的 APK 若簽章和使用者手上版本不同，App 內更新會失敗（「與現有套件衝突」）。
   發版前比對：`java -jar <sdk>/build-tools/34.0.0/lib/apksigner.jar verify --print-certs <APK>`，看 `certificate SHA-256 digest`。
   本機 debug 簽章指紋 `ce65c06a331ba5256c3df2d7b0684b620eef3a6532bb2ff7c1f8efcdb1ff7eed`；**v2.4.1 release APK 的指紋尚未比對**（下載未完成時被中斷）。
   長期解法見 ROADMAP F1（正式 release keystore）。
5. `git commit`（慣例 `feat: ... (v2.5.0)`）→ `git tag v2.5.0` → `git push origin main --tags` → `gh release create v2.5.0 <apk> --title ... --notes-file ...`。
   App 內更新器讀 `releases/latest`（**不含 draft 與 pre-release**；簽章不確定時可先發 draft）。
