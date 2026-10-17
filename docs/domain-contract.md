# Domain Contract — xrnhabit (Android, formerly Habit First)

Trạng thái: **v1 draft**, chốt ở Phase 0. Đây là nguồn sự thật cho hành vi máy trạng thái.
Mọi thay đổi hành vi phải sửa file này *trước*, rồi mới sửa code.

Module hiện thực: `:domain` (Kotlin thuần, JVM, **không** phụ thuộc Android). Tất cả bất biến
dưới đây phải có unit test tương ứng trong `:domain/src/test`.

---

## 1. Từ vựng

| Thuật ngữ | Nghĩa |
|---|---|
| **Block** | Một luật: "các app đích này bị chặn cho tới khi điều kiện habit đạt". `targetPackages` là một **Set** — một Block có thể phủ nhiều app cùng lúc (block nhiều app bằng một luật); mọi test thành viên (shield, accrual) đều là OR trên tập này. |
| **Target** | (Các) app bị chặn — nhận diện qua `packageName` hoặc `category` — và/hoặc (các) website trong `targetSites`. |
| **Website target** | Một tên miền trong `block.targetSites`, đã chuẩn hóa (chữ thường, bỏ scheme/path/port, bỏ `www.`). Host `h` khớp target `t` ⟺ `h == t` hoặc `h` kết thúc bằng `"." + t` (chặn `youtube.com` thì chặn luôn `m.youtube.com`). Host đang mở được lớp Android đọc từ thanh địa chỉ của trình duyệt foreground. |
| **BlockMode** | Cách một Block quyết định có đang chặn hay không: `HABIT` (máy trạng thái §2 — mặc định), `ALLOWANCE` (hạn mức dùng mỗi ngày), `SCHEDULE` (chặn theo lịch), `LOCKDOWN` (chặn một khoảng thời gian tính từ lúc bắt đầu). Xem §2.2. |
| **Lockdown** | Một lần chặn `LOCKDOWN` đang chạy: `{startedAtMonotonic, startedAtWall, durationMs}`, lưu ở `World.lockdowns`. |
| **Trigger** | Điều kiện để ARMED → EARNED. Một trong: `TIMER`, `CHECKLIST`, `COMBINED` (TIMER + CHECKLIST cùng lúc). (`COUNT` cũ đã bỏ: không có gì đếm được nó.) |
| **CombineMode** | Chỉ dùng khi `trigger.type == COMBINED`: `AND` (cần cả hai) hoặc `OR` (cần một trong hai). |
| **Habit app** | `trigger.habitPackages` — app(s) nơi đồng hồ TIMER đếm giờ. Rỗng (mặc định) nghĩa là "đếm giờ trong chính app Target"; nếu khác rỗng, đếm giờ trong (các) app này thay vì Target — cho phép habit "mở app khác N phút để unlock app bị chặn". |
| **ChecklistItem** | Một mục to-do thuộc một Block (chỉ dùng khi `trigger.type` là `CHECKLIST` hoặc `COMBINED`). |
| **External habit** | Một habit được theo dõi ở app khác — hiện là xrnloops, bản fork Loop Habit Tracker của xrnhabit (project riêng `../xrnloops`). `:domain` chỉ biết nó qua một id chuỗi ổn định (`uuid` của Loop) và hai tập "đã xong hôm nay" / "đã xong hôm qua" mà lớp Android đọc rồi gửi vào bằng `ExternalHabitsSynced`. |
| **Linked item** | `ChecklistItem` có `externalHabitId != null`. Chỉ `ExternalHabitsSynced` mới tick/untick nó, user không bấm được (I23). `linkedDay` chọn tick của ngày nào được tính: `TODAY` (mặc định) hoặc `YESTERDAY` (cho habit thường tick vào cuối ngày). `countsGoal`: tính ngày mà app ngoài báo **đạt mục tiêu** (gồm cả ngày nó tự điền cho habit kiểu "3 lần/tuần"), thay vì chỉ ngày có tick. Một lần tick ngoài chỉ trả cho một lần mở khóa mỗi ngày (I24, qua `spentEpochDay`). |
| **Credit** | Quyền mở khóa đã kiếm được, có hạn dùng (TTL). |
| **Pause** | Giờ nghỉ ngắn (mặc định 15 phút, tối đa một lần mỗi ngày local) gỡ chặn mọi block `SOFT` không phải `LOCKDOWN` (I25). |
| **Unlock session** | Khoảng thời gian shield tắt sau khi redeem credit. |
| **Shield** | Cơ chế chặn thực tế (overlay). "Shield ON" = target bị chặn. |
| **Monotonic clock** | `SystemClock.elapsedRealtime()` — không nhảy khi user chỉnh giờ. |
| **Wall clock** | `System.currentTimeMillis()` — giờ hệ thống, user chỉnh được. |
| **Desktop app target** | `block.targetDesktopApps`: id app trên laptop (tên file `.desktop` mà KWin báo, vd `steam`). Laptop coi chúng như `targetPackages` (I29); điện thoại không bao giờ thấy chúng ở foreground. |
| **Shared state** | Phần `World` mà các thiết bị (điện thoại, laptop) dùng chung qua server đồng bộ: `SharedState`, xem §2.4. |

---

## 2. Máy trạng thái

Ba trạng thái: `ARMED`, `EARNED`, `UNLOCKED`. Một Block luôn ở đúng một trạng thái.

```
ARMED    ──[triggerMet]──────────────▶ EARNED
EARNED   ──[creditExpired|dayRollover|userReset]──▶ ARMED
EARNED   ──[redeem]────────────────────▶ UNLOCKED
UNLOCKED ──[timeUp|tamper]─────────────▶ ARMED
```

Không có transition nào khác. Cụ thể: **không** có UNLOCKED → EARNED, **không** có
EARNED → EARNED, **không** có ARMED → UNLOCKED. (Ngoại lệ duy nhất là *nhận* trạng thái từ
thiết bị khác, §2.4: máy này thấy block mở vì máy kia đã redeem — I27.)

### 2.1 Điều kiện transition

| Transition | Guard | Side effect (theo thứ tự) |
|---|---|---|
| `ARMED → EARNED` | `triggerMet(progress, trigger)` == true | 1. persist state=EARNED + tạo `EarnedCredit`  2. shield vẫn ON (no-op) |
| `EARNED → ARMED` | `now_monotonic ≥ credit.expiresAtMonotonic` **hoặc** ngày local đổi so với `progress.windowStart` **hoặc** user bấm "hủy" | 1. persist state=ARMED  2. **reset `HabitProgress` về 0**  3. **reset mọi `ChecklistItem.done = false`**  4. xóa `EarnedCredit`  5. shield vẫn ON |
| `EARNED → UNLOCKED` | user bấm "Redeem" **và** `dailyUnlockUsedMs + credit.amountMs ≤ dailyCapMs` (I9) | 1. persist state=UNLOCKED + `UnlockSession{startedAtMonotonic=now_mono, startedAtWall=now_wall, durationMs=credit.amountMs}` + ghi `spentEpochDay = hôm nay` lên mọi linked item đang done (I24)  2. xóa `EarnedCredit`  3. **gỡ shield** (OFF) |
| `UNLOCKED → ARMED` | `elapsedMonotonic ≥ session.durationMs` (timeUp) **hoặc** `tamperDetected` (I3) | 1. persist state=ARMED  2. cộng thời gian đã dùng vào `dailyUnlockUsedMs`  3. reset `HabitProgress` + `ChecklistItem.done` (như EARNED→ARMED)  4. **áp lại shield** (ON) |

`triggerMet`:

```
fun triggerMet(progress, trigger, checklist): Boolean = when (trigger.type) {
    TIMER     -> progress.accumulatedMs >= trigger.requiredAmount
    CHECKLIST -> {
        val need = if (trigger.requiredAmount == -1L) checklist.size.toLong()
                   else trigger.requiredAmount
        checklist.count { it.done } >= need && checklist.isNotEmpty()
    }
    COMBINED  -> {
        val timerMet     = progress.accumulatedMs >= trigger.requiredAmount
        val checklistMet = checklist.isNotEmpty() && checklist.all { it.done } // luôn "hết sạch", không có ngưỡng riêng
        when (trigger.combineMode) {
            AND -> timerMet && checklistMet
            OR  -> timerMet || checklistMet
        }
    }
}
```

`COMBINED` dùng chung `requiredAmount`/`unit` của trigger làm ngưỡng cho **nửa TIMER**; nửa
CHECKLIST của nó luôn nghĩa là "mọi mục đã tick" (không có ngưỡng từng phần riêng — UI hiện tại
cũng chưa bao giờ tạo CHECKLIST với ngưỡng từng phần, nên đây không phải một giới hạn thực tế).

Đồng hồ TIMER (cho cả `TIMER` lẫn `COMBINED`) đếm giờ theo `trigger.habitPackages` nếu khác
rỗng, nếu không thì đếm theo target của block (`targetPackages` + `targetSites`) — xem I16, I18.

### 2.2 Chế độ chặn (`BlockMode`)

Máy trạng thái ở trên chỉ áp dụng cho `mode == HABIT`. Ba chế độ còn lại **không có đường
mở khóa**: block luôn ở `ARMED`, trigger/checklist/credit bị bỏ qua, và engine không bao giờ
chuyển trạng thái cho chúng. "Đang chặn" của từng chế độ:

| Mode | Đang chặn khi | Tham số |
|---|---|---|
| `HABIT` | `enabled && state != UNLOCKED && scheduleActive` (như cũ, I5) | trigger, reward, schedule |
| `ALLOWANCE` | `enabled && scheduleActive && usedToday ≥ dailyAllowanceMs` (I19) | `dailyAllowanceMs` |
| `SCHEDULE` | `enabled && scheduleActive` — lịch (ngày + khung giờ, có thể vắt qua nửa đêm) chính là khoảng chặn (I20) | `schedule` |
| `LOCKDOWN` | `enabled && lockdownRemaining > 0` (I21) | `lockdownMs` |

Gộp nhiều block cùng phủ một target: xem I22.

### 2.3 Sửa block: nới lỏng cần "guard"

Ngoài sự kiện, user còn sửa cấu hình block (lớp app gọi `WorldStore.edit`). Sửa nào làm block
**dễ vượt qua hơn** là *nới lỏng* và phải qua guard trước khi áp dụng: chờ đếm ngược (mặc định
60 giây, rời màn hình là hủy) với block `SOFT`, hoặc nhập mật khẩu hardcore với block
`HARDCORE`. Sửa chỉ làm chặt hơn thì áp dụng ngay. `BlockEdits.loosens(old, new)` là định nghĩa
chuẩn (bỏ target, giảm timer, `AND → OR`, đổi habit app, tăng thời gian mở khóa / TTL / hạn mức,
giảm độ dài lockdown, đổi lịch, `HARDCORE → SOFT`, đổi mode hay loại trigger). Ngoài ra cũng
là nới lỏng: xóa block, xóa một mục checklist, đổi `linkedDay`/`countsGoal` của linked item,
tăng `dailyCapMs`, và bắt đầu Pause (I25). Ngoại lệ duy nhất: lockdown **không đang chạy** thì
sửa/xóa tự do (`BlockEdits.guardNeeded`). Test: `BlockEditsTest`.

### 2.4 Nhiều thiết bị: trạng thái chung

Điện thoại và laptop dùng chung một trạng thái qua server đồng bộ (`xrnhabit-sync`, chỉ là kho
tài liệu JSON có phiên bản, không có logic). Một lần redeem mở block trên **mọi** thiết bị tới
cùng một thời điểm; hạn mức mở khóa mỗi ngày, Pause và lockdown cũng là chung.

**Phần chung** (`SharedState.of(world)`): cấu hình block (bỏ `state`), mọi `ChecklistItem`,
credit, session, lockdown, `pause`, `lastPauseEpochDay`, `settings`, nhật ký mở khóa hôm nay
(`unlockLog`, I28) và lượng ALLOWANCE đã dùng hôm nay của từng thiết bị (I19). Chỉ thời gian
**wall** đi qua máy khác: mọi mốc monotonic bị đặt 0 khi chiếu và được neo lại khi nhận (như I8).
**Không** chung: `done` của linked item (mỗi máy tự suy từ Loop, để máy có dữ liệu cũ không lật
qua lật lại), tiến độ timer HABIT, foreground, `appliedShield`.

**Một vòng đồng bộ:** đọc bản của server → `SharedStateMerged(base, remote)` → engine trộn **ba
chiều** bản chiếu hiện tại với `remote`, so với `base` (bản hai bên đã thống nhất lần trước; null ở
lần đầu) → đẩy kết quả lên kèm `If-Match`; bị 412 thì làm lại. Khóa chỉ một bên đổi so với `base`
lấy bên đó (kể cả xóa — nên credit đã dùng ở máy kia không sống lại, I26). Hai bên đổi khác nhau:

| Khóa | Bên thắng |
|---|---|
| session | kết thúc muộn hơn; một bên xóa, bên kia thay bằng cái mới → cái mới |
| credit | kiếm sớm hơn; một bên xóa (đã dùng / hết hạn) → xóa |
| lockdown | kết thúc muộn hơn; một bên xóa → bên còn |
| pause | bắt đầu sớm hơn; một bên null → bên còn; `lastPauseEpochDay` lấy lớn hơn |
| block | `editedAtWall` muộn hơn; sửa vs xóa → giữ bản sửa (chặt hơn) |
| checklist item | cấu hình theo bên đổi (remote nếu cả hai); `done` = OR; `spentEpochDay` = max; sửa vs xóa → giữ |
| settings | từng trường: `dailyCapMs`, `skewToleranceMs` lấy nhỏ hơn; `unlockWarningMs` lấy lớn hơn |
| `unlockLog` | ngày lớn hơn thắng cả; cùng ngày → hợp, mỗi khóa lấy max |
| allowance | theo (block, thiết bị): ngày mới hơn; cùng ngày → max |

Nhận kết quả (I27): credit/session/lockdown/pause mới được neo monotonic theo wall; `state` của
block suy ra lại (có session → `UNLOCKED`, có credit → `EARNED`, không → `ARMED`; mode khác HABIT
luôn `ARMED`); rời EARNED/UNLOCKED theo cách này thì reset như I10; vào `UNLOCKED` theo cách này
thì ghi `spentEpochDay = hôm nay` lên linked item đang done ở máy này (I24). Sau đó sweep chạy như
mọi sự kiện. Test: `SharedStateTest` (bảng trên), I26–I28.

---

## 3. Bất biến (mỗi cái = 1 test)

| # | Bất biến | Test skeleton |
|---|---|---|
| **I1** | `EarnedCredit.expiresAtWall == earnedAtWall + creditTtlMs` (mặc định 2h; dùng wall clock để sống qua reboot, mono chỉ để soát tamper như I3). Ở EARNED, khi `now_wall ≥ expiresAtWall` → engine tick đưa về ARMED. | `earn → advance clock 2h → tick → assert ARMED` |
| **I2** | Countdown UNLOCKED tính bằng `now_mono - session.startedAtMonotonic`, **không** dùng wall clock. | `redeem → giữ nguyên mono, đẩy wall +10h → tick → vẫn UNLOCKED` |
| **I3** | Nếu `(now_wall - session.startedAtWall) < (now_mono - session.startedAtMonotonic) - SKEW_TOLERANCE_MS` → tamper → UNLOCKED → ARMED ngay. `SKEW_TOLERANCE_MS = 5_000`. | `redeem → wall lùi 1h, mono +1phút → tick → ARMED, reason=TAMPER` |
| **I4** | `HabitProgress.accumulatedMs` chỉ tăng khi `foregroundPackage ∈ countedPackages` (= `trigger.habitPackages` nếu khác rỗng, không thì `block.targetPackages` — xem I16) **và** timer đang chạy, block đang ARMED. Shield đang hiện vẫn được cộng: TIMER không có `habitPackages` chính là "chờ N phút trước màn chặn". Khi app đếm giờ rời foreground → ngừng cộng. | `feed foreground events: target 10s, other 10s, target 10s → accumulated == 20s` |
| **I5** | Trong lúc ARMED (kể cả khi timer đang chạy / đang tick to-do) `shieldState(block) == ON`. | `mọi trạng thái ARMED/EARNED → shieldOn == true; chỉ UNLOCKED → false` |
| **I6** | `isAppUnlocked(pkg) == blocks.any { pkg ∈ it.targets && it.state == UNLOCKED }`. | `2 block cùng phủ pkg, 1 UNLOCKED 1 ARMED → isAppUnlocked == true` |
| **I7** | Engine không bao giờ trả về "shield changed" trước khi `PersistPort.save()` được gọi cho state mới. Áp dụng cho **cả tick/untick ChecklistItem**. | test dùng fake `PersistPort` ghi lại thứ tự call; assert save trước shield-callback |
| **I8** | `rebuild(fromStorage)` tái tạo đúng trạng thái + tính lại credit/session theo mono hiện tại; `shieldPlan()` được gọi *trước* khi phát signal "UI ready". | `persist EARNED gần hết hạn → rebuild sau 2h1phút → ARMED` |
| **I9** | `redeem` bị từ chối nếu `usedToday + amount > dailyCapMs` (mặc định 60 phút), với `usedToday = dailyUnlockUsedMs + Σ unlockLog` của ngày `dailyUsageEpochDay` (0 nếu là ngày khác; `dailyUnlockUsedMs` chỉ còn cho dữ liệu cũ, xem I28). Block ở lại EARNED. | `dailyUsed=58phút, credit=5phút, cap=60 → redeem → vẫn EARNED, reason=CAP_REACHED` |
| **I10** | Bất kỳ transition nào về ARMED → `HabitProgress` reset (0 / windowStart=today) **và** mọi `ChecklistItem.done=false`, `doneAtMonotonic=null`. | `checklist 3/3 done → EARNED → creditExpired → ARMED → assert 0/3 done` |
| **I11** | CHECKLIST đủ ngưỡng ⟺ `doneCount ≥ (requiredAmount==-1 ? size : requiredAmount)` và `size > 0`. Checklist rỗng không bao giờ đủ ngưỡng. | bảng: (size,req,done)→expected; gồm ca size=0 |
| **I12** | `toggleItem(itemId, done)` khi block ở EARNED hoặc UNLOCKED là **no-op** (không đổi state, không đổi `done`). Chỉ có tác dụng ở ARMED. | `EARNED → toggleItem(false) → item vẫn done, state vẫn EARNED` |
| **I13** | `windowStart` của `HabitProgress` là **đầu ngày local** (00:00 theo `ZoneId` thiết bị). "Day rollover" so sánh `localDate(now_wall) != localDate(windowStart)`. | `windowStart = hôm qua 23:59 → tick lúc 00:01 hôm nay → ARMED + reset` |
| **I14** | Nếu `trigger.type == CHECKLIST` mà block **không có** ChecklistItem nào → block coi như **không hợp lệ để ARMED-đủ-ngưỡng**; UI phải chặn tạo block kiểu này. Engine: `triggerMet` trả false. | `checklist rỗng → triggerMet == false mãi mãi` |
| **I15** | `trigger.type == COMBINED`: `triggerMet` theo `combineMode` — `AND` cần `timerMet && checklistMet`, `OR` cần `timerMet \|\| checklistMet`, bất kể thứ tự hoàn thành (tick checklist trước hay chạy hết timer trước đều được). Cả hai đường dẫn hoàn thành (toggle checklist item, timer tick) đều phải gọi `triggerMet` theo cùng công thức này. | `i15_combinedAndRequiresBoth`: chỉ xong checklist hoặc chỉ xong timer → vẫn ARMED; xong cả hai (theo bất kỳ thứ tự nào) → EARNED. `i15_combinedOrRequiresEither`: xong một trong hai → EARNED ngay. |
| **I16** | Khi `trigger.habitPackages` khác rỗng, timer đếm giờ theo tập này thay vì `block.targetPackages` — app Target vẫn bị chặn như thường, nhưng ở trong Target **không** cộng giờ; phải ở trong một app thuộc `habitPackages` mới cộng. Khi `habitPackages` rỗng, hành vi quay về đếm giờ trong `targetPackages` (tương thích ngược). | `i16_habitPackagesOverridesTargetForAccrual`: habitPackages={"com.habit"}, ở trong target 10s → accumulated==0; chuyển sang com.habit 10s → accumulated==10s. `i16_emptyHabitPackagesFallsBackToTarget`: habitPackages rỗng → hành vi y hệt TIMER cũ. |
| **I17** | `block.targetPackages` (và `trigger.habitPackages`) có thể chứa nhiều hơn một package; mọi test thành viên (shield, accrual) là OR trên tập — ở trong *bất kỳ* app nào trong tập đều tính. | `targetPackages={"a","b"}` → cả hai đều bị chặn cùng lúc bởi một block; foreground chuyển a→b→a cộng dồn giờ ở cả hai. |
| **I18** | Website target: `ShieldPlan.blockedSites` chứa các tên miền target đang bị chặn (tính như package, theo I6/I22). Foreground "ở trong target" ⟺ `foregroundPkg ∈ targetPackages` **hoặc** `foregroundSite` khớp (theo luật hậu tố ở §1) một phần tử của `targetSites`. Mọi accrual dùng đúng phép thử này. | `i18_siteTargetBlocksAndMatchesSubdomains`: block site `youtube.com` → `blockedSites` chứa nó; `m.youtube.com` khớp, `notyoutube.com` không khớp. `i18_timerAccruesOnTargetSite`: TIMER chặn `youtube.com`, foreground là trình duyệt ở `m.youtube.com` 10s → accumulated == 10s; cùng trình duyệt ở site khác → không cộng. |
| **I19** | `ALLOWANCE`: thời gian foreground trong target được cộng vào `progress.accumulatedMs` của ngày local hiện tại, **chỉ khi target đó không đang bị chặn** (không trừ hạn mức khi đang nhìn shield), và không vượt quá `dailyAllowanceMs`. Đang chặn ⟺ đã dùng ≥ hạn mức, với "đã dùng" = phần của máy này **cộng** phần các thiết bị khác đã báo cho cùng ngày (`otherAllowance`, §2.4). Qua ngày mới (I13) → về 0, hết chặn. | `i19_allowanceBlocksOnceUsedUp`: hạn mức 10 phút, ở target 9 phút → chưa chặn; thêm 2 phút → chặn, accumulated == 10 phút. `i19_allowanceResetsAtDayRollover`. `i19_allowanceCountsOtherDevices`: máy khác đã dùng 7 phút hôm nay, máy này 4 phút → chặn; phần của hôm qua không tính. |
| **I20** | `SCHEDULE`: đang chặn ⟺ `scheduleActive` (ngày trong `activeDays` và giờ trong `activeWindow`; window có `start > end` là vắt qua nửa đêm). Window vắt qua nửa đêm **thuộc về ngày nó bắt đầu**: phần sau 00:00 xét ngày hôm trước có trong `activeDays` không. Không có cách mở khóa; chỉ thời gian trôi. | `i20_scheduleBlocksOnlyInsideWindow`: window 09:00–17:00 → 08:59 không chặn, 09:00 chặn, 17:00 hết chặn; window 22:00–06:00 chặn lúc 23:00 và 05:00. `i20_overnightWindowBelongsToItsStartDay`: chỉ Thứ Sáu 23:00–07:00 → chặn Thứ Bảy 02:00, không chặn Thứ Sáu 02:00. |
| **I21** | `LOCKDOWN`: `StartLockdown` tạo `Lockdown{startedAtMonotonic=now_mono, startedAtWall=now_wall, durationMs=block.lockdownMs}` (no-op nếu đang chạy). Còn lại = `durationMs − (now_mono − startedAtMonotonic)` — chỉnh **wall** clock không rút ngắn được. Hết giờ → sweep xóa Lockdown, hết chặn. Rebuild (I8) re-anchor `startedAtMonotonic` từ delta wall như UnlockSession. | `i21_lockdownIgnoresWallClockAndEnds`: start 1h → đẩy wall +5h, mono +1 phút → vẫn chặn; mono tới 1h → hết chặn, Lockdown bị xóa. |
| **I22** | Gộp: target bị chặn ⟺ có block `ALLOWANCE`/`SCHEDULE`/`LOCKDOWN` phủ nó **đang chặn**, **hoặc** (có block `HABIT` phủ nó, không cái nào `UNLOCKED`, và ít nhất một cái `enabled && scheduleActive`). Unlock của HABIT **không** gỡ được chặn của các mode kia. I6 vẫn đúng cho phần HABIT. | `i22_habitUnlockDoesNotLiftStrictBlock`: cùng target, HABIT ở UNLOCKED + SCHEDULE đang trong giờ → vẫn chặn; ngoài giờ → không chặn. |
| **I23** | Linked item (`externalHabitId != null`): `ToggleChecklistItem` trên nó là **no-op** — chỉ app ngoài mới xác nhận được. `ExternalHabitsSynced(epochDay, doneHabitIds, doneYesterdayHabitIds)` bị bỏ qua nếu `epochDay != epochDay(now)` (kết quả cũ chạy đua qua nửa đêm). Nếu không: với mọi block `HABIT` đang `ARMED`, mỗi linked item được đặt `done = externalHabitId ∈ S && spentEpochDay != today`, với `S` chọn theo `linkedDay` (`TODAY`/`YESTERDAY`) và `countsGoal`: `doneHabitIds` / `doneYesterdayHabitIds`, hoặc `goalMetHabitIds` / `goalMetYesterdayHabitIds` khi `countsGoal` (bỏ tick ở app ngoài thì item cũng bỏ tick), rồi kiểm `triggerMet` theo đúng công thức của toggle (I11/I15). Block `EARNED`/`UNLOCKED`: no-op, như I12. | `i23_manualToggleIgnoredOnLinkedItem`, `i23_syncTicksLinkedItemAndEarns`, `i23_syncUnticksWhileArmed`, `i23_syncForAnotherDayIsIgnored`, `i23_syncIgnoredUnlessArmed`, `i23_yesterdayItemCountsYesterdaysTick`, `i23_goalItemCountsGoalMetDays`. |
| **I24** | Một lần tick ở app ngoài trả cho **tối đa một lần mở khóa mỗi block mỗi ngày local**: `EARNED → UNLOCKED` (redeem) ghi `spentEpochDay = today` lên mọi linked item đang done; về lại ARMED (I10) xóa `done` nhưng **giữ** `spentEpochDay`, nên sync sau đó trong cùng ngày không tick lại được. Ghi lúc redeem chứ **không** lúc earn: credit hết hạn (I1) hoặc user hủy khi chưa dùng thì tick chưa bị tiêu, sync kế tiếp kiếm lại được. Sang ngày mới thì tick lại được. | `i24_externalTickEarnsOncePerDay`: earn → redeem → hết giờ → sync cùng habit → item vẫn chưa tick, block vẫn ARMED. `i24_expiredCreditDoesNotSpendTick`. `i24_nextDaySameHabitCanEarnAgain`. |
| **I25** | `StartPause(durationMs)` tạo `Pause{startedAtMonotonic, startedAtWall, durationMs}` và ghi `lastPauseEpochDay = today`; no-op nếu đang nghỉ hoặc hôm nay đã nghỉ. Trong lúc nghỉ, các block `SOFT` không phải `LOCKDOWN` coi như không chặn (bỏ khỏi phép gộp I22); block `HARDCORE` và lockdown vẫn chặn. Còn lại = `durationMs − (now_mono − startedAtMonotonic)` như I21; hết giờ → sweep xóa `Pause`; `EndPause` xóa ngay. Rebuild (I8) re-anchor như Lockdown. Accrual ALLOWANCE vẫn cộng lúc nghỉ (đó là dùng thật). | `i25_pauseLiftsSoftBlocksOnly`, `i25_pauseEndsAndIsOncePerDay`, `i25_pauseSurvivesRebootViaWallDelta`. |
| **I26** | `SharedStateMerged(base, remote)` trộn ba chiều (§2.4): khóa chỉ một bên đổi so với `base` lấy giá trị bên đó, kể cả khi bên đó **xóa** khóa; hai bên đổi khác nhau → theo bảng §2.4. Credit đã dùng hay hết hạn ở một máy không bao giờ sống lại vì máy kia chưa biết. | `i26_creditUsedElsewhereIsNotResurrected`: base có credit, remote đã redeem (bỏ credit, thêm session) → máy này UNLOCKED, không còn credit. `i26_offlineDoubleRedeemKeepsLaterSession`. `i26_sessionEndedElsewhereEndsHere` (vd máy kia phát hiện tamper). |
| **I27** | Nhận trạng thái chung: mốc monotonic mới = `now_mono − (now_wall − startedAtWall)` (không báo tamper giả); `state` suy từ session/credit; rời EARNED/UNLOCKED → reset như I10; vào UNLOCKED → `spentEpochDay = hôm nay` cho linked item đang done ở máy này (I24); `done` của linked item **không bao giờ** lấy từ máy khác. | `i27_adoptedSessionRunsOutOnTime`, `i27_adoptedUnlockSpendsLocalTick`, `i27_linkedDoneIsNeverShared`. |
| **I28** | Thời gian mở khóa đã dùng hôm nay là `unlockLog`: session kết thúc (hết giờ hay tamper) ghi `unlockLog["blockId@startedAtWall"] = consumed`, nên hai máy cùng kết thúc một session chung chỉ tính **một lần**. Qua ngày mới thì xóa. | `i28_sharedSessionCountsOnceTowardCap`: hai máy đều kết thúc session 5 phút → trộn → `usedToday == 5 phút`. |
| **I29** | `block.targetDesktopApps` là target như `targetPackages`: nằm trong `blockedPackages`, foreground khớp thì bị chặn, ALLOWANCE/timer cộng giờ, và bỏ một desktop app là nới lỏng (§2.3). | `i29_desktopAppTargetsActLikePackages`. |

Hằng số mặc định (cấu hình qua Settings, nhưng đây là default hợp đồng):

```
creditTtlMs        = 2 * 60 * 60 * 1000      // I1
dailyCapMs         = 60 * 60 * 1000          // I9
skewToleranceMs    = 5_000                   // I3
unlockWarningMs    = 30_000                  // cảnh báo trước khi cắt (G3)
```

---

## 4. Cổng (ports) — `:domain` định nghĩa interface, Android hiện thực

```
interface Clock {
    fun nowMonotonicMs(): Long     // SystemClock.elapsedRealtime()
    fun nowWallMs(): Long          // System.currentTimeMillis()
    fun zone(): ZoneId
}

interface PersistPort {
    fun loadWorld(): World                       // toàn bộ blocks + progress + credits + sessions + lockdowns
    fun save(world: World)                       // atomic, cả World; phải xong trước khi engine đụng shield
}

interface ShieldPort {
    fun apply(plan: ShieldPlan)                  // ShieldPlan(blockedPackages, blockedSites) — trạng thái mong muốn đầy đủ
}

interface Telemetry {                            // cho Insights; khi bật sync, còn gửi lên server của người dùng (trang Review)
    fun onBlockedImpression(pkg: String, atWallMs: Long)
    fun onHabitCompleted(blockId: String, atWallMs: Long)
    fun onUnlockConsumed(blockId: String, ms: Long, atWallMs: Long, startedAtWall: Long)  // startedAtWall: tên phiên (I28), các thiết bị cùng phiên ghi cùng một tên
}
```

`BlockEngine` là hàm thuần trên `World` + sự kiện + `Clock`; nó **không** tự gọi `ShieldPort`.
Nó trả về `Outcome(newWorld, shieldPlan?, effects[])`. `shieldPlan != null` ⟺ phép chiếu chặn
hiện tại khác `World.appliedShield` (plan đã phát lần trước, được ghi lại trong World) hoặc là
`BootOrColdStart`. So với plan đã phát — không so với phép chiếu của World cũ — vì
`SCHEDULE`/`LOCKDOWN`/ngày mới đổi kết quả chặn chỉ bằng thời gian trôi. Lớp Android:
1. `PersistPort.save(outcome.world)`
2. nếu `outcome.shieldPlan != null` → `ShieldPort.apply(...)`
3. phát `outcome.effects` (notification cảnh báo 30s, telemetry, ...).

Thứ tự 1→2 là hiện thực của **I7**.

---

## 5. Sự kiện đầu vào của engine

```
sealed interface BlockEvent {
    data class ForegroundChanged(val pkg: String?, val site: String? = null) : BlockEvent  // app + host trình duyệt đang mở
    data object Tick                                         : BlockEvent   // ~1s khi có timer/session/mode ≠ HABIT
    data class ToggleChecklistItem(val itemId: String, val done: Boolean) : BlockEvent
    data class Redeem(val blockId: String)                  : BlockEvent
    data class UserResetEarned(val blockId: String)         : BlockEvent
    data class TimerControl(val blockId: String, val running: Boolean)     : BlockEvent  // nút "Bắt đầu"
    data class StartLockdown(val blockId: String)           : BlockEvent   // I21
    data class ExternalHabitsSynced(val epochDay: Long, val doneHabitIds: Set<String>, val doneYesterdayHabitIds: Set<String> = emptySet(),
                                    val goalMetHabitIds: Set<String> = emptySet(), val goalMetYesterdayHabitIds: Set<String> = emptySet()) : BlockEvent  // I23
    data class StartPause(val durationMs: Long = 15 * 60 * 1000) : BlockEvent  // I25
    data object EndPause                                     : BlockEvent   // I25
    data class SharedStateMerged(val base: SharedState?, val remote: SharedState) : BlockEvent  // §2.4, I26/I27
    data object BootOrColdStart                              : BlockEvent
}
```

- `ForegroundChanged`: trước hết cộng giờ cho foreground *cũ* (timer HABIT theo I4/I16/I18,
  hạn mức ALLOWANCE theo I19), rồi mới đổi sang `pkg`/`site` mới. `site` chỉ khác null khi
  `pkg` là trình duyệt và lớp Android đọc được host; màn hình tắt ⇒ lớp Android gửi
  `ForegroundChanged(null)` để không cộng giờ lúc máy khóa.
- `Tick`: kiểm I1 (credit hết hạn), I3 (tamper), UNLOCKED timeUp, phát cảnh báo 30s.
- `ToggleChecklistItem`: chỉ hiệu lực khi block ARMED (I12) và item không phải linked item (I23);
  sau khi toggle, kiểm `triggerMet`.
- `ExternalHabitsSynced`: ảnh chụp đầy đủ các external habit đã xong trong ngày `epochDay` (ngày
  local của xrnhabit) và trong ngày hôm trước (`doneYesterdayHabitIds`, cho linked item
  `YESTERDAY`). Lớp Android gửi nó khi app ngoài báo có thay đổi, khi service khởi động, khi mở
  khóa màn hình, khi qua ngày, khi mở app và khi shield hiện ra cho block có linked item. Phải
  đọc được **cả hai** ngày mới gửi; đọc thất bại thì **không** gửi gì (không được coi "đọc lỗi"
  là "chưa xong" rồi bỏ tick hàng loạt). Xem I23/I24.
- `SharedStateMerged`: lớp ngoài (điện thoại: `SharedSync`; laptop: daemon) gửi sau khi đọc server;
  engine trộn và nhận ngay trong `reduce`, dưới mutex của store, nên không chạy đua với sự kiện
  khác. Xem §2.4.
- `BootOrColdStart`: `rebuild()` — xem I8.

---

## 6. Những gì KHÔNG thuộc hợp đồng này

- Chiến lược phát hiện foreground (UsageStats vs Accessibility) — chi tiết Android, xem brainstorm §4.1.
- Cách đọc URL từ trình duyệt (AccessibilityService, chỉ ở flavor `sideload`) và thống kê thời
  gian theo website — chi tiết Android; engine chỉ nhận `site` qua `ForegroundChanged`.
- Cách đọc habit từ Loop (ContentProvider có quyền `signature` trong bản fork `../xrnloops`, cộng
  broadcast khi có thay đổi) — chi tiết Android; engine chỉ nhận `ExternalHabitsSynced`.
- Server đồng bộ (`server/xrnhabit-sync.py`), giao thức HTTP, lúc nào đồng bộ và cách laptop phát
  hiện app/website — chi tiết lớp ngoài; engine chỉ nhận `SharedStateMerged` (§2.4).
- Giao diện overlay, layout 3 màn hình — xem brainstorm §5.
- Cơ chế sống sót (foreground service, WorkManager, OEM autostart) — brainstorm §4.4.
- Device Admin / anti-bypass — module `:hardcore`, brainstorm §4.5.
- Health Connect / Location / Beacon — phase E.

---

## 7. Ghi chú test

- `:domain` dùng `FakeClock`, `InMemoryPersist`, `RecordingShield` — không JUnit-Android, không Robolectric.
- Mỗi bất biến I1–I29 có ít nhất một test đặt tên `i01_...` ... `i29_...`; quy tắc sửa block (§2.3) nằm ở `BlockEditsTest`, bảng trộn (§2.4) ở `SharedStateTest`.
- Acceptance của brainstorm §1 map thành test tích hợp cấp `:domain` trong `AcceptanceTests.kt`,
  chạy chuỗi sự kiện end-to-end trên engine.
