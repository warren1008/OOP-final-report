# 多人連線貪吃蛇

## 版本與維護方式

本分支是 2026/11/05 多人連線操作原型的歸檔，與期末正式成果分開保存；日期表示原型整理日期，尚未確認是否與期中當天展示版本完全相同。

| 版本 | 用途 |
|---|---|
| `prototype/multiplayer-20261105` 分支 | 保存本次原型的完整原始碼、啟動腳本、測試、文件與截圖 |
| `prototype-20261105` 標籤 | 固定本次歸檔提交，供下載與重現 |
| `main` 分支 | 期末正式成果的後續整合位置；歸檔時仍保留組員原先的小測試 |

唯一維護來源為 `warren1008/OOP-final-report` 儲存庫。`output/prototypes/多人連線貪吃蛇_操作原型_20261105` 是儲存庫外的下載試玩副本，不另外維護，也不將整包重複放進本儲存庫。

`A1143349_previous/` 雖沿用舊名稱，內容是本原型使用的 4 個 Java 檔案。它是專案的一部分，請連同根目錄腳本、`tests/` 與 `docs/` 一起下載。初次操作請參閱 [開始使用](開始使用.txt)，整理依據請參閱 [原型歸檔紀錄](docs/原型歸檔_20261105.md)。

已 clone 的組員可執行：

```powershell
git fetch origin --tags
git switch --track origin/prototype/multiplayer-20261105
```

若本地已有該分支，改用 `git switch prototype/multiplayer-20261105`。需要回到期末開發位置時執行 `git switch main`；切換前先提交或妥善保存手上的修改。

不使用 Git 時，可從 GitHub 的 `prototype-20261105` 標籤頁選擇 Code → Download ZIP，下載後完整解壓縮再執行。

## 原型功能

本次在 `A1143349_previous` 的原程式上完成斷線機制。保留多人加入、圓形地圖、跟隨視角及 Java 物件串流；本次未切換 JSON，也未新增準備、倒數或同一連線的再玩一次。

## 編譯與啟動

需要 JDK 17 以上，無額外套件。先確認 `java -version` 和 `javac -version` 可執行。

在本資料夾執行：

```powershell
.\build.cmd
.\run-server.cmd
```

另開一個終端機執行下列指令，可重複開啟數個 Client：

```powershell
.\run-client.cmd
```

連線視窗可填玩家名稱、Server IP 和 Port。預設為 `127.0.0.1:12345`。不同電腦要填 Server 所在電腦的區網 IP，並確認網路允許互連及該埠可存取。

也可預填設定：

```powershell
.\run-server.cmd 12345
.\run-client.cmd PlayerA 127.0.0.1 12345
```

方向鍵控制轉向。關閉 Client 視窗即離開，會釋放 Socket 與背景工作。

## 斷線與勝負

- 連線失敗：顯示主機未啟動、拒絕連線、無法找到主機或逾時提示，可重新連線。
- 玩家離線：Server 處理 EOF、RST、傳送失敗與心跳逾時，移除離線玩家的有效蛇身，再通知其他 Client。同一次離線只處理一次。
- 多人勝負：至少曾有兩名存活玩家參與，之後才會進行最後存活者判定。三人減為兩人仍繼續；只剩一名連線且存活的玩家時由 Server 決定勝者。單人剛加入不會直接獲勝。
- Server 中斷：Client 顯示「與伺服器中斷連線」，停止送出操作。Client 不因 Server 中斷自行宣告勝利。
- 心跳：Client 每 2 秒送 `PING`，Server 回 `PONG`。雙方以最後有效收到訊息後 10 秒為失聯門檻，監測每 100 毫秒檢查一次。加入握手另有 5 秒期限。
- 所有網路工作在背景，畫面、排行榜及提示在 EDT 更新。遊戲快照先深層複製再傳送，並以狀態序號忽略過期快照。

每局結束後保留結果。所有玩家離開後 Server 自動重設下一局，之後可重新加入。Server 中斷時請先恢復 Server，再按「重新連線」。這不代表恢復舊局進度。

## 測試與實際畫面

```powershell
.\test.cmd
.\capture.cmd
```

`test.cmd` 執行真實同機 TCP 與 Swing 測試。`capture.cmd` 透過實際網路事件觸發視窗，再匯出真正的 Swing 提示及完整介面到 `docs/screenshots`，不使用手工替換的提示文字。

詳見 [斷線機制驗證紀錄](docs/斷線機制_驗證紀錄.md)。跨兩台實體電腦的 Wi-Fi、拔線及路由器中斷仍待實測。

## 本次檔案

- `A1143349_previous/GameState.java`：快照、玩家連線旗標、事件序號及 Server 勝負結果。
- `A1143349_previous/SnakeServer.java`：多人管理、心跳逾時、離線清理及最後存活者判定。
- `A1143349_previous/ClientConnection.java`：背景連線、收送、心跳及單次關閉通知。
- `A1143349_previous/SnakeClient.java`：狀態列、提示視窗、停止操作與重新連線。
- `tests/`：可重現的網路、GUI 測試及實際介面擷取。

本版保留原程式的逐蛇碰撞演算法。簡報所規劃的「同一快照同時計算所有蛇頭」、JSON、Observer／State、準備與倒數仍須另外整合，不能由本次斷線測試推論全部功能已完成。新 Client 與新 Server 請使用同次編譯版本。
