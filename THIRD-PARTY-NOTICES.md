# Third-Party Notices and Licenses (第三方開源授權聲明)

本專案「All's good注音九宮格輸入法」引用並致謝以下開放原始碼專案之資料庫與元件：

---

## 1. chichi77Collection (29 類專業與現代生活分類詞庫)
- **專案來源**：[琦琦輸入法 (chichi77 KeyKey)](https://github.com/polobread/KeyKey)
- **作者與版權**：Copyright (c) 2026 Chui-Ping Cheng
- **授權條款**：MIT License

```text
MIT License

Copyright (c) 2026 Chui-Ping Cheng

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 2. McBopomofo (小麥注音) 字音與詞頻資料
- **專案來源**：[McBopomofo](https://github.com/openvanilla/McBopomofo)
- **作者與版權**：Copyright (c) 2011-2026 Mengjuei Hsieh et al.
- **授權條款**：MIT License

```text
MIT License

Copyright (c) 2011-2026 Mengjuei Hsieh et al.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 3. PIME 輸入法平台 (PIME Windows IME Architecture)
- **專案來源**：[PIME (easyprog/pime)](https://github.com/easyprog/pime)
- **作者與致謝**：PIME 開發團隊 (easyprog et al.)
- **技術致敬**：PIME 整合新酷音等優異架構，其跨音節動態規劃（Viterbi）斷詞、游標回退選字編輯、實體鍵盤改字與 1~9 分頁互動邏輯啟發了本專案外接實體鍵盤核心之實現。
- **授權條款**：GPL-2.0+ / MIT

---

## 4. libchewing-data (新酷音繁體中文核心詞庫)
- **專案來源**：[新酷音 (chewing/libchewing-data)](https://github.com/chewing/libchewing-data)
- **版權說明**：新酷音團隊與全體社群貢獻者維護之真實頻率繁體中文高頻詞庫與注音資料。
- **授權條款**：LGPL-2.1+ / MIT



---

## 5. 琦琦輸入法的 AI 合成文章語料（詞對 bigram 統計的來源與驗證集）
- **專案來源**：[琦琦輸入法 (chichi77 KeyKey)](https://github.com/polobread/KeyKey) 的 `DataSource/AISyntheticArticles/`
- **用途**：本專案內建的詞對（bigram）關聯強度檔 `app/src/main/assets/bigram_core.bin`，是用上述專案以 AI（OpenAI Codex）產生的
  2,300 篇繁體中文文章（`typing-articles-v2.jsonl`、`v3.jsonl`、`v4.jsonl`），以本專案詞庫重新切詞後自行統計而成；
  測試用的評測子句 `app/src/test/resources/bigram_eval.tsv` 取自該專案另外保留的 130 篇驗證文章（`typing-articles-v5-seed/`），
  與訓練文章完全分開。本專案只使用統計結果與少量子句，不隨附原文語料。
- **作者與版權**：Copyright (c) 2026 Chui-Ping Cheng
- **授權條款**：MIT License。依該專案 `DataSource/AISyntheticArticles/LICENSE.txt` 與 `LICENSES/AI-DATA-NOTICE.txt`
  （2026-10-15 提交 `845ae6e80656593fbf861be04e2e57b2a4b918a9`「License project-original AI corpora and database content under MIT」起生效），
  涵蓋 AI 合成語料、文章語料快照與衍生的 bigram 資料、計數及權重，允許使用、重製、修改、散布、再授權與商業利用（含模型訓練與評測），
  條件為保留著作權與授權聲明。該授權聲明另載明：僅在專案權利人有權授權的範圍內，且不主張 AI 輸出必然享有著作權。
- **備註**：該資料夾內 `AISyntheticBigram/README.md`（最後更新於 2026-09-29，早於上述授權提交）仍寫「暫不提供使用授權」，
  與較新的授權檔矛盾，判斷為未同步更新的舊文字；本專案以較新的授權檔為準。
- **來源版本**：倉庫提交 `b3c96970e459d88151e5730b429a834d947af89a`（取用日 2026-10-24）。訓練檔 SHA-256：
  `typing-articles-v2.jsonl` = `ae70cc5274c5d131510a010aa07adf9eaca4be3f37345711e4c5516049cafe5d`、
  `v3` = `8e783676d4a22cd92ae832e1a3be913d2e07252c8e2c4add76b55f6c725877de`、
  `v4` = `ff625fc0b7be600347d7e61c9ddc3b4c4b8ada17f2b76950425f203505af6dc8`
  （與該專案 `smart-mandarin-model-manifest.json` 記載一致）。

```text
MIT License

Copyright (c) 2026 Chui-Ping Cheng

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
