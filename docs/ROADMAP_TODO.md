# 輸入預測與管理改善規劃（對照新酷音 / PIME / 小麥注音）

> 撰寫日期：2026-10-12，基準版本 v2.5.0。**本文件只是建議與工作分派用，撰寫者沒有修改任何程式。**
> 每個 TODO 標明：問題證據 → 作法 → 影響檔案 → 驗收 → 工作量 → 相依 → 風險，可直接分配給不同 agent。
> 動手前先讀 `docs/DEV_TESTING_GUIDE.md`（模擬器與真機測試流程）。

> **狀態更新（2026-10-24，v2.10.0）**：A1（bigram 語言模型）已在分支 `feat/bigram-lm` 完成第一版並出貨 APK（未發 GitHub release），
> 細節見 `docs/BIGRAM_LM_PLAN.md`。附帶發現：全鍵盤「不打聲調」的最大問題是 `fullNoToneFirstBonus` 500 萬，已改 3 萬。
> 後續：擴充語料、選配大型資料包、格式 v2（5 位元組項目約 2.8 MB）。

## 0. 結論先看

1. **三種模式的候選品質卡在同一個天花板：純詞頻 (unigram) 沒有上下文。** v2.5.0 已處理九鍵「怪詞」與「無法修正」，但整句第一名正確率
   （模擬器 49 句自然句）只有約 3 成；調參數救不了（「現在機電／現在幾點」、「去／趣」九鍵無法分辨）。
   **下一個有實質效益的投資是「上下文語言模型 (bigram)」＋資料管線**，三種模式都受惠。
2. **三種模式有三條各自獨立、重複實作的斷詞管線**（九鍵 `t9*`、全鍵盤 `searchFullZhuyin`/`findBestSentence`、實體鍵盤 `findBestSentenceFromSyllables`），
   常數、聲調處理、學習行為各不相同。長期應收斂成「**一個以音節為單位、可吃聲調與固定選字的 DP 核心**」。
3. **螢幕全鍵盤是最弱的一環**：只有「單一緩衝區 → 一次查候選」，沒有組句、游標改字、固定選字；實體鍵盤已有新酷音風格管線。
   **全鍵盤可直接複用實體鍵盤管線**（高 ROI）。
4. **實體鍵盤的「學習」是壞的**：整句被當成一個詞學進詞庫，且讀音取「單字第一讀音」，會污染個人詞庫（見 P1）。
5. 九鍵有一項**被浪費的資訊**：聲調鍵（K11）同時是「音節邊界」與「聲調證據」，斷詞時被整個丟掉（見 P4）。

## 1. 對照：新酷音 (libchewing) / PIME / 小麥注音 vs 本專案

資料來源：libchewing [README](https://github.com/chewing/libchewing)、[0.9~0.13 NEWS](https://codegraph.jelmer.uk/libchewing/0.13.0-1/NEWS)、[docs.rs/chewing](https://docs.rs/chewing)（可信）；
PIME [DeepWiki 摘要](https://deepwiki.com/EasyIME/PIME)（AI 產生，細節請對照 [EasyIME/PIME](https://github.com/EasyIME/PIME) 原始碼）；
小麥注音只查到鏡像專案片段（lattice + unigram、使用者詞/排除詞的 LM 外觀層），**是否用 bigram、使用者覆寫模型細節未查證，以「？」標示**。

- **libchewing**：只管選字邏輯、不管 UI；C/Rust；被 Windows TSF、**PIME**、ibus、fcitx5、HIME 等嵌入。詞庫來自 `libchewing-data`（本專案詞庫同源）。
- **PIME**：Windows 輸入法**框架**（TSF ↔ Python/Node 後端，多行程＋具名管道），新酷音是其承載的後端之一。對我們的啟示是**架構**：UI／設定／引擎分離、引擎可獨立測試。
- **小麥注音**：macOS 注音輸入法，lattice＋語言模型整句組字，另有使用者詞與排除詞層。

| 能力 | libchewing | 小麥 | **九鍵** | **全鍵盤(螢幕41鍵)** | **實體鍵盤** |
|---|---|---|---|---|---|
| 整句智慧組字 | ✓ 0.13 起 m-A*；0.11 重寫為條件機率 | ✓ lattice+unigram（bigram？） | △ unigram DP（無聲調證據） | △ 只有 `findBestSentence`（鍵序，忽略聲調） | ✓ 音節級 Viterbi（unigram，有聲調懲罰） |
| 上下文語言模型 | ✓ | ？ | ✗（僅約 20 組寫死 bigram＋使用者學習的 bigram） | ✗ | ✗ |
| 固定選字（selection pinning） | ✓（`auto_snapshot_selections` 可選） | ✓ | △ 部分上屏（v2.5.0） | ✗ | △ 只能釘「單字」 |
| 組字區游標回退改字 | ✓ | ✓ | ✗ | ✗ | ✓ |
| 候選由長詞到單字（依游標位置的**詞組**候選） | ✓ Down 鍵 | ✓ | △ | △ 單字同音優先 | ✗ 游標處只列單字/同音字 |
| 使用者學習 | ✓ 頻率估計含最近使用時間（0.11）、新詞較高頻 | ✓ | △ 計數＋三段時間加成，上限 500 | 同左 | △ 同左，但**整句當一個詞學** |
| 刪詞＋永不再學（排除詞庫） | ✓ `chewing-deleted.dat`（0.12，連內建詞也能排除） | ✓ | △ 能刪，之後會被學回來 | 同左 | 同左 |
| 手動加詞 / Tab 打斷組字 | ✓ Ctrl-2/3/4、Tab | ？ | ✗ | ✗ | ✗ |
| 模糊音/前綴/無聲調 | ✓ Fuzzy mode（0.9） | ？ | △ 跨鍵位容錯＋零韻母 | △ 聲母簡拼 | ✗ |
| 簡易模式（無智慧組字） | ✓ Simple mode | — | — | — | ✗ |
| 鍵盤排列 | ✓ 15 種（大千、許氏、倚天、IBM、精業、漢語拼音…） | ✓ | 九鍵固定 | — | ✗ 只有大千（`DAQIAN_KEY_MAP` 寫死） |
| 候選依頻率排序（可選） | ✓ 0.11 | — | — | — | ✗ |
| 詞庫格式 | 預編譯 trie，SQLite 選用 | 二進位 LM | **16.5 萬行純文字 5MB，冷啟動解析並建 6 個索引** | 同左 | 同左 |
| 引擎可獨立測試 | ✓ 函式庫＋CLI | ✓ | ✗ 引擎吃 Android `Context` | ✗ | ✗ |

**5 個關鍵啟示**：(1) 新酷音近年方向是「更準的機率模型＋更好的學習」（0.11 條件機率與時間衰減、0.13 m-A* 並限制輸出路徑為 10 條），不是更多規則；
(2) 「刪除＋永不再學」是詞庫管理的必備功能（0.12）；(3) 固定選字是自動組字與手動修正之間的橋；(4) 資料驅動的鍵盤對應可支援許氏/倚天（實體鍵盤使用者常用許氏）；
(5) 引擎與 UI 分離才能測試與演進——我們的 5000 行 Service 與吃 `Context` 的引擎是最大阻力。

## 2. 現況快照（完整讀碼）

| | 內部表示 | 斷詞/排序 | 選字與提交 | 學習 |
|---|---|---|---|---|
| 九鍵 | `currentKeys: List<Int>`（K11=聲調） | `recalculate()`：精確＋前綴＋v2.5.0 `t9*` 成本模型 DP | `selectCandidate` → `commitProcessedWordWithUserDict` / `commitPartialWord` | `learnWord`（5000 萬權重塞進 Trie）＋`learnBigram` |
| 全鍵盤 | `fullZhuyinBuffer`（注音字元） | `searchFullZhuyin` 5 步啟發式，每步各有上限與加權 | 同上；**單一緩衝區、無組句** | 同上 |
| 實體鍵盤 | `composingSyllables`＋`composingSentence`＋`pinnedSentenceChars`＋`sentenceCursor` | `findBestSentenceFromSyllables`：Viterbi，`wordBonus=2.0`，聲調相符+8/不符−25，釘字+1000 | 數字鍵/方向鍵；Space=一聲並結算；Enter/Space 提交整句 | `commitProcessedWordWithUserDict(整句)` ← **整句當一個詞學** |

### 讀碼發現的問題（TODO 的來源）

| # | 問題 | 位置 |
|---|---|---|
| P1 | 實體鍵盤提交整句以 `learnWord(整句, zhuyin="")`：讀音由 `getZhuyinForChar` 取**每字第一讀音**拼成，多音字學錯；整句變成一個 Trie 詞條（5000 萬權重）並佔用 500 名額 | `handlePhysicalSpace/Enter`、`performImSendAction` → `commitProcessedWordWithUserDict`；`ZhuyinT9Engine.learnWord` |
| P2 | 學習的詞沒綁讀音：`getBoost(word)` 只看字串，多音字選一種讀音另一種也被加權 | `UserDictionaryManager.getBoost` |
| P3 | 加權常數量級混用（1e9、5e6、5e7、1e8、999999999、1.3×、0.35×、2.5×…）散在三條管線，靠大小關係維持順序 | `ZhuyinT9Engine` 全檔 |
| P4 | 九鍵 DP 丟掉聲調鍵（`cleanKeys` 過濾 K11）→ 失去音節邊界與聲調證據 | `recalculate` |
| P5 | 全鍵盤 `findBestSentence` 用**鍵序**找最佳句，雖然輸入是明確注音 → 聲調被忽略，與實體鍵盤結果不一致 | `searchFullZhuyin` §1.5 |
| P6 | 實體鍵盤游標處的候選只有單字/同音字，沒有「涵蓋游標起 k 個音節」的詞組候選 | `updateComposingDisplay` |
| P7 | 候選去重 `distinctBy { it.word }` 把同字不同讀音合併 | 多處 |
| P8 | 詞庫冷啟動逐行解析 16.5 萬行並建 6 個索引；`largeHeap=true` | `loadDictionaryInternal` |
| P9 | `defaultAssociations`/`initDefaultBigrams` 寫死；bigram 只在九鍵 DP 與使用者學習時使用，**全鍵盤與實體鍵盤的 DP 完全不用 bigram** | `ZhuyinT9Engine` |
| P10 | `ZhuyinInputMethodService.kt` ≈ 5000 行（UI、狀態機、三種模式、剪貼簿、手寫、單手/懸浮混在一起） | — |
| P11 | 單元測試複製一份舊排序邏輯在測試內，測不到真引擎；v2.5.0 無新增測試 | `app/src/test` |
| P12 | 三種模式共用一個引擎實例與 `fullZhuyinBuffer` 等狀態，靠旗標互斥；提交時要手動逐項重設 | Service |
| P13 | 個人詞庫/bigram 整檔 JSON 重寫（2.5 秒防抖），無版本/備份；剪貼簿歷史明文存 SharedPreferences | `UserDictionaryManager`、`ClipboardHistoryManager` |
| P14 | release 用 debug 簽章；`allowBackup=true`；更新下載未驗證簽章；`RECEIVER_EXPORTED` | `build.gradle.kts`、Manifest、`AppUpdateManager` |

## 3. 工作分派（6 條軌道）

| 軌道 | 主題 | 主要檔案 | 可平行？ |
|---|---|---|---|
| **A** | 資料與語言模型 | `assets/*`、新增 `tools/lm/`、`engine/LanguageModel*.kt` | 先定介面即可 |
| **B** | 九鍵 | `ZhuyinT9Engine` 的 `t9*`/`recalculate`、Service 九鍵區段 | 與 C/D 可平行 |
| **C** | 全鍵盤 | `searchFullZhuyin`、`handleZhuyinFullKey` | 依賴 E3 |
| **D** | 實體鍵盤 | `handlePhysical*`、`onKeyDown`、`findBestSentenceFromSyllables` | 與 B/C 可平行 |
| **E** | 核心與工程（共用 DP、拆檔、可測試、CI） | 新增 `engine/core/*`、Service 拆分 | **E2/E3 先做** |
| **F** | 發版、隱私、安全 | `build.gradle.kts`、Manifest、`AppUpdateManager` | 完全獨立 |

**衝突區警告**：`ZhuyinInputMethodService.kt` 是所有軌道的交會點。E3（拆檔）完成前，每個 agent 只改自己模式的函式、PR 小而專一、不要整檔重排。

階段建議：**Phase 1**（1~2 週）E3、E2、D1/D2、B1、F1~F3；**Phase 2**（2~4 週）A1~A3、E1、C1、D3；**Phase 3** B2~B5、C2/C3、D4~D6、A4。

## 4. TODO 清單（工作量：S ≤1 天、M 2~4 天、L 1~2 週）

### 軌道 E：核心與工程

- **E3 ★ 拆分 Service**（L，風險中，其他軌道的前置）。問題 P10。作法：(1) 實體鍵盤狀態機（`handlePhysical*`、`onKeyDown` 注音段、`DAQIAN_KEY_MAP`、`composing*`）→ `PhysicalKeyboardController`；(2) 候選列/格 UI → `CandidateBarView`；(3) 九鍵/全鍵盤 layout 建置 → 各自 View；(4) 單手/懸浮位置 → `KeyboardWindowManager`。驗收：行為不變、`DEV_TESTING_GUIDE` §8 檢查表全過、Service ≤1500 行。風險：`onUpdateSelection` 的 800ms 啟發式與 `isHardwareKeyboardConnected` 等跨區旗標要先盤點。
- **E2 ★ 引擎可在 JVM 單元測試執行**（M，低）。P11。抽出 `DictionarySource`/`Scheduler`/`Prefs` 介面，`t9*` DP、`searchFullZhuyin`、`findBestSentenceFromSyllables` 搬進純 Kotlin。驗收：`testDebugUnitTest` 載入真實 `dict_tw.txt` 跑 golden test；不再有「複製邏輯到測試」的檔案。（CI 需連網下載 junit。）
- **E4 Golden test 與回歸資料**（M，低）。把 `tools/t9sim/sents.py`、`bench2.py` 評測集輸出成 `app/src/test/resources/*.tsv`（鍵序 → 期望 top-N），用「不退步門檻」而非逐字相等；另為全鍵盤 6 組固定詞建快照（`ㄐㄧㄣㄊㄧㄢ`、`ㄨㄛㄒㄧㄤㄔㄈㄢ`、`ㄒㄧㄝㄒㄧㄝ`、`ㄐㄊ`、`ㄓㄉㄠˋ`、`ㄋㄧˇㄏㄠˇ`）。
- **E1 ★ 共用「音節級 DP 核心」**（L，高，Phase 2）。P3、P5、P9。新增 `engine/core/PhraseLattice.kt`：輸入 `List<SyllableObservation>`（全鍵盤/實體鍵盤＝明確音節；九鍵＝鍵序的多種切法）、`pinned: Map<span, word>`、`previousWord`；內部以 `(位置, 前一詞)` 為狀態的 Viterbi/m-A*，成本 `-ln P(word|prev)`＋每詞固定成本，**聲調為軟證據**；輸出 n-best（新酷音 0.12 上限 10 條）。三種模式各提供適配器（九鍵適配器即現有 `t9*`）。驗收：全鍵盤與實體鍵盤對同一注音序列第一名一致；九鍵 `bench2/midstate/steps3` 不退步；移除全鍵盤的鍵序版 `findBestSentence`。相依 E2（與 A1）。
- **E5 集中排序參數**（S，低）。P3。常數收進 `RankingConfig`，註明單位與意義，並在 `DEV_TESTING_GUIDE` §7 登記 Kotlin↔Python 對應。

### 軌道 A：資料與語言模型

- **A1 ★ Bigram 語言模型與離線資料管線**（L，中）。作法：(1) `tools/lm/` 以**授權明確**的繁體中文語料切詞（用現有詞庫最大匹配）→ 統計 bigram → PMI/條件機率裁剪到 5~20 萬組 → 緊湊二進位；(2) App 端 mmap/延遲載入，`LanguageModel.logProb(prev, word)`，找不到退回 unigram（backoff＋平滑）；(3) 九鍵 `t9SegmentCost` 加入 `-ln P(word|prev)`，`getBigramBoost` 留作個人化層。驗收：模擬器自然句 top1 由約 15/49 提升到 ≥25/49，A/B 組不退步；APK 增量 ≤3 MB；啟動不明顯變慢。風險：語料授權、過擬合評測句（評測句不可進訓練語料）。**請先與專案擁有者確認語料來源。**
- **A2 預編譯詞庫、降低冷啟動與記憶體**（M~L，中）。P8。build 階段把 `dict_tw.txt` 轉二進位 trie＋預排序索引，執行期 mmap。驗收：冷啟動到 `isDictionaryLoaded` 時間減半、常駐記憶體下降（`dumpsys meminfo com.bopomofo.t9ime`）、可嘗試關 `largeHeap`。相依 E2。
- **A3 資料驅動的候選資料**（S，低）。P9。`defaultAssociations`、`initDefaultBigrams` 移成 assets 檔並與 A1 合併。
- **A4 詞庫品質清理**（M，低）。檢查多音字讀音缺漏、極低權重怪詞（如「艾因喔」）、重複詞條；可參考 libchewing 0.10 `--fix` 與詞數/音節數不符偵測。輸出報表，不直接改資料。

### 軌道 B：九鍵

- **B1 ★ 聲調鍵當音節邊界與聲調證據**（M，中）。P4。DP 改用含 K11 的原始鍵序；遇 K11 強制詞邊界（或對跨過 K11 的詞加懲罰）；聲調相符加分（Trie 同時存含/不含聲調鍵序）。驗收：`tools/t9sim` 新增「每音節後都打聲調」評測集，top1 提升；不打聲調的結果不退步。可先在模擬器驗證。
- **B2 固定選字與組字區游標**（L，高）。目前選了部分上屏後無法回頭改。作法：B1 之後，引入 E1 的 `pinned`；九鍵組字預覽可點選某一段重選。驗收：真機連打整句後可點任一段換候選、其餘段重新組字。
- **B3 「詞庫沒有的詞」自動出候選**（M，中）。現況：`ㄅㄤㄨㄛ`（幫我）要靠左側組合點選；DP 因 `ㄅㄤ` 上「不」比「幫」常用而給「不我」。作法：A1 的 bigram 讓「幫+我」勝過「不+我」；並自動把音節切分組合的合成詞加入候選（沿用 `applyLockFilter` 兩音節合成）。驗收：`ㄅㄤㄨㄛ` 前 3 名含「幫我」（需 A1）。**備註：v2.5.0 只完成「左側組合出現」（模擬器命中率 91.7%），真機尚未完整驗證點選後得到「幫我」，請先補驗。**
- **B4 候選列版面**（S，低）。整句很長時部分上屏候選被擠出畫面：整句單獨一列預覽、或限制整句顯示長度（省略號）。
- **B5 軟性聲調偏好**（S，低）。`filterTone` 改成「相符排前、不符保留」（對應 libchewing Fuzzy mode 的無聲調概念）。

### 軌道 C：全鍵盤（螢幕 41 鍵）

- **C1 ★ 複用實體鍵盤的音節級組句管線**（M~L，中）。全鍵盤只有單一緩衝區。作法：螢幕注音鍵走和 `handlePhysicalZhuyinKey` 相同的音節邊界判定（`ZHUYIN_INITIALS/FINALS`）→ `composingSyllables` → `findBestSentenceFromSyllables`；聲調鍵/空白鍵結算音節；UI 提供游標左右按鈕。**保留**聲母簡拼 (`initialMap`) 與混合簡打（緩衝區不是合法音節時的退路）。驗收：連打 `ㄐㄧㄣㄊㄧㄢㄊㄧㄢㄑㄧˋ` 得「今天天氣」並可游標改字；E4 快照 6 組不退步。相依 E3。建議做成設定開關，先預設關閉。
- **C2 聲調與多音字**（S~M，低）。P5、P7：聲調不符降權而非忽略；候選去重改 `(word, 讀音)`。
- **C3 簡拼設定化**（S，低）。`initialMap`（2~6 字詞）全載入佔記憶體；改預編譯索引（A2）並加開關；簡拼與全拼結果用統一成本排序。

### 軌道 D：實體鍵盤

- **D1 ★ 修正學習：按片語學習並綁定讀音**（M，中）。P1、P2。提交整句時依 `composingSyllables` 與最終分詞切成 2~4 字片語，各自用**實際讀音**呼叫 `learnWord(word, zhuyin)`，更新相鄰片語 bigram；整句本身不當一個詞學；手動改過的字（`pinnedSentenceChars`）以修改後的字與讀音學習。驗收：提交「今天天氣很好」後詞庫新增的是片語而非 6 字整句；多音字以實際讀音學習。
- **D2 使用者詞庫模型改良**（M，中）。對照 libchewing 0.11/0.12：(1) 鍵改 `(word, 讀音)`；(2) 頻率估計加最近使用時間衰減、新詞較高初始頻率；(3) **排除詞清單**（使用者刪除的詞不再被學回來，可連內建詞也排除，設定頁可管理）；(4) 檔案版本化、原子寫入、備份/還原。影響三種模式（`UserDictionaryManager`、`learnWord`、`getBoost`），需資料遷移（舊 JSON 要能讀）。
- **D3 ★ 游標處的詞組候選**（M，中）。P6。選字模式依序列出「涵蓋游標處起 k 個音節」的詞組（k 由最長到 1，對照新酷音 Down 鍵），選了就把該 span 釘住（`pinned` 以 span 為單位）。驗收：「在哪裡」被組錯時，游標移到「在」，第一頁出現「在哪裡」「在那裡」，選取後整段替換。相依 E1 的 span 式 pinned（或先擴充 `findBestSentenceFromSyllables`）。
- **D4 Ctrl+數字記憶詞組、Tab 打斷組字**（S~M，低）。對照新酷音 Ctrl-2/3/4 與 Tab。注意 `onKeyDown` 目前把所有 Ctrl 組合鍵放行給系統（`isCtrlPressed → super.onKeyDown`），要先攔截這兩組。
- **D5 資料驅動的鍵盤排列**（M，低）。`DAQIAN_KEY_MAP` 改成 `KeyboardLayout` 資料表，新增許氏、倚天、倚天26鍵、IBM、精業（libchewing 共 15 種）；許氏/倚天需處理「同鍵依位置代表聲母或韻母」的消歧。設定頁加排列選項。
- **D6 簡易模式與 Fuzzy 模式**（S~M，低）。對照 libchewing 0.9：Simple mode（選一詞就提交）、Fuzzy mode（前綴/部分注音/無聲調，可重用 `searchPrefixBest`）。

### 軌道 F：發版、隱私、安全

- **F1 ★ 正式 release keystore 與簽章一致性**（S，**高**：做錯會讓使用者無法升級）。現況 release 用 debug 簽章，每台電腦的 debug keystore 不同，Android 只允許簽章相同的 APK 覆蓋安裝。作法：先用 `apksigner verify --print-certs` 比對舊 release APK 與新 APK 的 SHA-256 指紋；以**使用者手上版本的簽章**為準設定正式 release 簽章（密碼放 `local.properties`/環境變數，不進 git）並備份 keystore；若舊簽章已遺失需規劃一次性重新安裝遷移並在更新說明告知。驗收：新舊簽章指紋相同，真機從上一版升級成功且保留個人詞庫。
- **F2 allowBackup、剪貼簿、更新下載安全**（S~M）。`allowBackup=false` 或 `dataExtractionRules` 排除個人詞庫/bigram/剪貼簿；剪貼簿加 `EXTRA_IS_SENSITIVE` 過濾與過期；`AppUpdateManager` 廣播改 `RECEIVER_NOT_EXPORTED`、下載後比對簽章再叫起安裝。
- **F3 CI**（S）。GitHub Actions：`./gradlew testDebugUnitTest assembleDebug`（連網）；PR 跑 `tools/t9sim` 評測並在指標退步時失敗（評測腳本需改成有門檻、非 0 退出碼）。

## 5. 驗收與量測的共同規則

1. 每個影響候選品質的 TODO，先在 `tools/t9sim` 量化「舊 vs 新」（A/B/C 組＋自然句＋打到一半），再改 Kotlin，最後用 `tools/phone/t9.sh` 真機驗收。
2. **三種模式互不破壞**：改到共用元件（`TrieDictionary`、`SyllableManager`、`UserDictionaryManager`、`DictEntry`、`selectCandidate`）必須跑 `DEV_TESTING_GUIDE` §8 的全鍵盤「新舊逐字相同」回歸並手動驗證實體鍵盤。
3. 新欄位一律給預設值；新行為預設只對自己的模式生效。
4. 不要對 49 句自然句過擬合：新增錯誤類型就擴充評測集，並檢查舊評測集是否退步。

## 6. 需要專案擁有者決策

1. **語料來源與授權**（A1 前提）；APK 是否接受增加 ≤3 MB？
2. **release 簽章**（F1）：舊版本實際用哪把 keystore？是否還在？
3. 是否把 `engine` 抽成獨立 Gradle module（純 Kotlin/JVM）？長期最乾淨但改動面大。
4. 全鍵盤是否改成組句模式（C1）？會改變使用手感，建議設定開關、先預設關閉。
5. 使用者詞庫遷移（D2）：能否接受清空重來？否則需寫遷移。

## 7. v2.5.0 已完成與未完成

| 項目 | 狀態 |
|---|---|
| 九鍵怪詞（整句灌分）、尾端未完成詞 | ✅ 模擬器＋真機驗證 |
| 部分上屏 | ✅ 真機驗證 |
| 左側組合空白殘缺、停用多音節合成 | ✅ |
| 左側「音節切分組合」 | ⚠️ 模擬器命中率 91.7%；真機只確認清單出現，**未驗證點選後得到「幫我」** |
| 效能 | ✅ 11.5 ms vs 舊版 10.9 ms（每鍵） |
| 單元測試 | ❌ 未新增（需 E2） |
| 全鍵盤 / 實體鍵盤 | 未改動；全鍵盤真機比對輸出逐字相同；實體鍵盤未實機測試 |
