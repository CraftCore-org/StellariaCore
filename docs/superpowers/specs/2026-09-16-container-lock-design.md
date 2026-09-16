# 個別コンテナロック機能（`/lock`）設計

- 日付: 2026-09-16
- ステータス: 承認済み

## 目的

`/land` とは独立して、未保護地のチェスト、トラップチェスト、樽、全色のシュルカーボックスを個別に保護する。土地保護がある場所では土地保護とロックの両方の許可が必要であり、ロックは土地保護を回避しない。

## 操作と権限

| 操作 | 所有者 | 共同利用者 | `stellaria.lock.admin` |
|---|---:|---:|---:|
| 開閉・出し入れ | 可 | 可 | 可 |
| `/lock trust <player>` / `untrust` | 可 | 不可 | 可 |
| `/unlock` | 可 | 不可 | 可 |
| ロック中の破壊 | 不可 | 不可 | 可 |

`/lock` は視線先（5ブロック以内）をロックする。`/unlock` は視線先ロックの全対象座標と共同利用者を一括削除する。`/lock trust <player>` と `/lock untrust <player>` は視線先ロックの共同利用者を管理する。一般利用権限は `stellaria.lock`（default true）、管理権限は `stellaria.lock.admin`（default op）である。

## 永続化と二連チェスト

```sql
CREATE TABLE container_locks (
  lock_id TEXT PRIMARY KEY, owner_uuid TEXT NOT NULL, created_at INTEGER NOT NULL
);
CREATE TABLE container_lock_blocks (
  world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
  lock_id TEXT NOT NULL, PRIMARY KEY (world, x, y, z)
);
CREATE TABLE container_lock_members (
  lock_id TEXT NOT NULL, member_uuid TEXT NOT NULL,
  PRIMARY KEY (lock_id, member_uuid)
);
```

マネージャーは起動時に全行をキャッシュし、イベント時にSQLiteを読まない。二連チェストは左右の座標を同じ `lock_id` に紐づける。ロック済み単体チェストを二連化する時は、所有者または管理者が置いた新しい半分を既存 `lock_id` に自動追加する。無権限者の二連化、別 `lock_id` 同士の二連化はキャンセルする。

所有者は先に `/unlock` を行ってから壊す。管理者がロックを外さず片側を壊す時は、その座標だけを削除し、残る片側には元のロックと共同利用者を維持する。最後の座標を失った時だけロック本体と共同利用者も削除する。

## 保護

- 右クリックは所有者・共同利用者・管理者だけ許可する。
- 通常のブロック破壊は管理者以外を拒否する。
- `InventoryMoveItemEvent` でロック済みブロックを搬入元または搬入先とする移動を止める。ホッパー、ホッパー付きトロッコ、上・横・下の経路を含む。ホッパー自体はロック対象にしない。
- TNT等の爆発対象リストからロック済みコンテナを除外し、ピストンがロック済みコンテナを動かすイベントをキャンセルする。
- 保護拒否は `ActionBarManager#flash` で `lock.protected` を表示する。

## 統合と検証

`ContainerLockManager`、`LockCommand`、`ContainerLockListener` を新設する。`StellariaCore` でテーブル作成、manager生成、listenerと`lock`/`unlock`コマンドの登録を行う。`messages.yml` に `lock.*`、`plugin.yml` にコマンドと権限を追加する。

副作用のないロックモデルと二連チェスト座標のキャッシュ操作はJUnit 5でテスト先行とし、Paperイベントは`./gradlew build`後に`./gradlew runServer`で2人以上の実機確認を行う。確認には共同利用、二連化、二連一括解除、管理者による片側/最後の破壊、ホッパー/ホッパー付きトロッコ、TNT、ピストン、土地保護併用を含める。
