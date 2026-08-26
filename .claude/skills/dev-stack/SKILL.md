---
name: dev-stack
description: 啟動並驗證本機開發用的基礎設施（Redis / PostgreSQL / Qdrant / 三個假 LLM Provider / Prometheus / Grafana），會先檢查連接埠衝突、指出是哪個容器佔用，再等到各服務「真的可用」才回報就緒。當要啟動環境、跑整合測試前需要下游服務、遇到「服務起來了卻連不上」，或要確認基礎設施狀態時使用。
---

# 開發基礎設施啟動

## 執行方式

於**專案根目錄**執行：

```bash
node .claude/skills/dev-stack/up.js
```

```bash
node .claude/skills/dev-stack/up.js --status
```

```bash
node .claude/skills/dev-stack/up.js --down
```

連接埠可用環境變數覆寫：`REDIS_PORT`、`POSTGRES_PORT`、`QDRANT_PORT`、
`FAKE_FAST_PORT`、`FAKE_SLOW_PORT`、`FAKE_FLAKY_PORT`、`PROMETHEUS_PORT`、`GRAFANA_PORT`。

## 起了哪些東西

| 服務 | 預設埠 | 用途 |
| --- | --- | --- |
| Redis | 6380 | 語意快取 |
| PostgreSQL | 5433 | 使用量與成本稽核 |
| Qdrant | 6333 | RAG 向量庫 |
| fake-fast | 9101 | 正常的假 Provider（TTFT 80ms）|
| fake-slow | 9102 | 慢的假 Provider（TTFT 1500ms）|
| fake-flaky | 9103 | 30% 機率回 429 |
| Prometheus | 9091 | 指標 |
| Grafana | 3003 | 儀表板 |

預設埠刻意避開這台機器上其他專案已占用的 6379／9090／3000~3002。

## 這個腳本處理掉的兩個坑

**容器 `Up` 不等於服務可用。** PostgreSQL 要跑初始化（實測約 7 秒）、
Grafana 要載入 provisioning（實測約 10 秒），這段期間打過去是連線被拒，
很容易被誤判成設定寫錯而開始亂改。腳本對每個服務做真正的就緒探測，
全部通過才回傳 0。

**連接埠衝突的錯誤訊息沒有用。** `docker compose` 只會說 bind 失敗，不會說是誰佔的。
腳本在啟動前先探測，並比對 `docker ps` 找出佔用的容器名稱直接告訴你。
只靠 bind 測試會漏掉別的容器佔用的情況——Docker Desktop（WSL2）發布的連接埠，
從宿主機 bind 同一個埠有時仍會成功。

## 判讀結果

- **全部 ✓**：可以開始開發，位址清單會一併印出。
- **某個服務逾時**：先看 `docker compose -f deploy/docker-compose.yml logs <service>`。
- **連接埠衝突**：停掉佔用者，或用環境變數換一個埠。不要直接改 compose 檔的預設值，
  那會讓其他人的環境跟著變。

## 後續

基礎設施就緒後，先跑 `node tools/fake-provider/smoke.js` 確認假 Provider 行為正常
（見 `fake-provider` skill）。它是後續路由、對沖、串流驗證的基礎，
它不可信的話後面的綠燈都沒有意義。
