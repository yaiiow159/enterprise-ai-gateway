---
name: fake-provider
description: 可控的假 LLM Provider（OpenAI 相容、含 SSE 串流），能指定 TTFT、每 token 間隔、錯誤碼、串流中途斷線與模擬價格，並統計取消次數。當要驗證路由、對沖、失敗切換、熔斷或串流轉發，或需要在測試中模擬 Provider 故障與延遲時使用。
---

# 假 LLM Provider

## 為什麼存在

網關的核心功能——依延遲路由、對沖取最快、失敗切換、熔斷、串流轉發——
**只有在下游行為可控時才驗證得了**。

用真的 Provider 測有三個問題：每跑一次測試付一次錢；延遲每次都不同，
「有沒有選到最快的那個」根本無從斷言；CI 環境沒有金鑰也不該有。

用 Node 而不是 Java 實作是刻意的：這是測試替身，要能秒起、零建置、改行為不用重編譯。
它不會進到正式產物裡。

## 三個實例，三種個性

`dev-stack` 會一次起三個，對應路由要面對的三種下游：

| 實例 | 埠 | 個性 |
| --- | --- | --- |
| `fake-fast` | 9101 | TTFT 80ms，正常 |
| `fake-slow` | 9102 | TTFT 1500ms —— 對沖有沒有生效，看的就是它會不會輸掉 |
| `fake-flaky` | 9103 | 30% 機率回 429 —— 驗證熔斷與失敗切換 |

## 行為的三層控制（後者覆蓋前者）

**1. 環境變數**：實例的預設個性，寫在 `deploy/docker-compose.yml`。

**2. 執行期設定**：用於「跑到一半讓 Provider 開始故障」這類情境。

```bash
curl -X PUT http://localhost:9101/_fake/config -H 'Content-Type: application/json' -d '{"errorRate":1,"errorStatus":503}'
```

**3. 請求標頭**：只影響單一請求，整合測試最常用。

| 標頭 | 效果 |
| --- | --- |
| `X-Fake-Ttft-Ms` | 首個 token 的延遲 |
| `X-Fake-Token-Delay-Ms` | 每個後續 token 的間隔 |
| `X-Fake-Tokens` | 產生幾個 token |
| `X-Fake-Error` | 有這個標頭就必定回錯誤 |
| `X-Fake-Error-Status` | 錯誤狀態碼（預設 500）|
| `X-Fake-Abort-After-Tokens` | 串流到第 N 個 token 後直接斷線，不送 `[DONE]` |

## 統計端點

```bash
curl http://localhost:9101/_fake/stats
```

```
requests / streamingRequests / errors / cancelled / completedStreams
promptTokens / completionTokens
```

**`cancelled` 是最重要的一個**：它記錄「客戶端在串流結束前斷線」的次數。
網關有沒有把取消往上游傳，就靠這個數字驗證——沒傳的話，
使用者早就關掉頁面了，我們還在為那些 token 付錢，而且沒有任何錯誤徵兆。

`POST /_fake/reset` 清空統計並還原設定，測試之間應該呼叫。

## 自我驗證

```bash
node tools/fake-provider/smoke.js
```

九項檢查，驗的是「測試替身本身可不可信」。假 Provider 若行為不符預期，
後面所有以它為基礎的驗證都會得到錯誤結論——而且是那種看起來全綠、
實際上什麼都沒驗到的錯誤結論。改過 `server.js` 一定要重跑。

開發這支時它就抓到兩個真缺陷：

1. **Node 預設把 HTTP 標頭壓到第一次 `write()` 才送出**，客戶端因此要等到第一個
   token 才收到回應標頭，量到的 TTFT 永遠是 0，也分不出「已連上正在生成」與「還沒連上」。
   真實 Provider 是立刻回標頭的，所以要明確呼叫 `flushHeaders()`。
2. **客戶端斷線沒被偵測到**。原本監聽 `IncomingMessage` 的 `close`，
   但那在請求主體讀完時就會觸發，漏掉真正的中途斷線。要監聽 `response` 的 `close`
   並比對 `writableFinished`。這個缺陷還連帶造成統計數字失準——
   取消沒被偵測到，串流就繼續跑完並繼續累加 token 數。

## 用在 Java 測試裡

整合測試可用 Testcontainers 以 `node:22-alpine` 起同一支 `server.js`，
或直接連本機 `dev-stack` 起好的實例。前者可重現性較好，後者較快；
CI 用前者，本機開發用後者。
