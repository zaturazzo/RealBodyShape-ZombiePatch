# Real Body Shape – Zombie Bodies patch

Unofficial companion for the Steam Workshop mod **[Real Body Shape](https://steamcommunity.com/sharedfiles/filedetails/?id=3830730496)**
(Project Zomboid Build 42, Windows).

With the mod alone, every zombie has the same body as your character (the engine shares one body mesh between the
player and all zombies). With this patch, **each zombie and each corpse gets its own body: thin, normal, fat or
obese**, fixed per zombie, while your character keeps following its real weight. Since v5 it can also hide
**missing limbs** (hand, forearm, arm, leg) for the optional *Zombie Dismemberment* mod.

| | Mod only | Mod + patch |
|---|---|---|
| Player body follows weight, diet, exercise | yes | yes |
| Vanilla 3D clothes, shoes, bags follow the body | yes | yes |
| Zombies | copy the player's shape | own shape per zombie (4 variants) |
| Corpses | copy the player's shape | keep the zombie's own shape |
| Dismembered zombies (optional mod) | blood + crawling only | limbs really missing, clothes included, on corpses too |

---

## 1. Requirements

- Windows, Steam version of Project Zomboid **Build 42** (developed on 42.21).
- The **Real Body Shape** Workshop item, version **0.3.3 or newer**. It contains three mods: **Real Body Shape**,
  **Real Body Shape - Zombie Bodies** (the zombie body variants) and **Real Body Shape - Zombie Dismemberment**
  (optional: missing limbs). Enable the first two; the third if you want it.
- **No Java installation needed** – the patch uses the Java runtime that comes with the game (`jre64` folder).
- Singleplayer. Not tested in multiplayer.

## 2. Install – step by step

1. Download `RealBodyShapeZombiesPatch-vN.zip` from the **Releases** page and extract it anywhere
   (for example `Desktop\RBS-patch\`). You get:
   ```
   install.bat      uninstall.bat      status.bat      rbs.bat      rbs_agent.jar      README.md
   ```
2. **Close Project Zomboid** (the launcher config is only read when the game starts).
3. Double-click **`install.bat`**. A console window shows what happens:
   ```
   game     : D:\SteamLibrary\steamapps\common\ProjectZomboid
   patched  : zombie/core/skinnedmodel/ModelManager.getBodyModel (66343 -> 66641 bytes)
   patched  : zombie/core/skinnedmodel/visual/HumanVisual.getModel (30333 -> 30567 bytes)
   patched  : zombie/core/skinnedmodel/animation/AnimationPlayer.getSkinTransforms (64133 -> 64444 bytes)
   patched  : zombie/core/skinnedmodel/advancedanimation/AnimatedModel.getAnimationPlayer (49592 -> 49877 bytes)
   verified : zombie.core.skinnedmodel.ModelManager
   verified : zombie.core.skinnedmodel.visual.HumanVisual
   verified : zombie.core.skinnedmodel.animation.AnimationPlayer
   verified : zombie.core.skinnedmodel.advancedanimation.AnimatedModel
   written  : C:\Users\<you>\Zomboid\RealBodyShape\rbs_patch.jar (129 KB)
   launcher : C:/Users/<you>/Zomboid/RealBodyShape/rbs_patch.jar added first in "classpath" of D:\...\ProjectZomboid64.json
   backup   : D:\...\ProjectZomboid64.json.rbs_backup
   DONE. Start the game with the Real Body Shape mod enabled and check console.txt for '[RBS hook] ready'.
   ```
   The script finds the game through Steam (registry + `libraryfolders.vdf`). If it prints
   `Project Zomboid folder not found`, run it with the folder as argument from a command prompt:
   ```
   install.bat "D:\SteamLibrary\steamapps\common\ProjectZomboid"
   ```
4. Start the game, in *Mods* enable *Real Body Shape* and *Real Body Shape - Zombie Bodies* (and
   *Real Body Shape - Zombie Dismemberment* if you want missing limbs), load your save.
5. Walk around: zombies now come in different builds. Kill a fat one – the corpse stays fat.

### What exactly was changed on your PC
- `%USERPROFILE%\Zomboid\RealBodyShape\rbs_patch.jar` – created. It contains four game classes patched
  **on your machine from your own `projectzomboid.jar`** plus the small hook (`rbs/*.class`). Nothing of the
  game is included in the download itself.
- `<game>\ProjectZomboid64.json` – one line added as the first entry of `"classpath"`. The original file is kept as
  `ProjectZomboid64.json.rbs_backup`. Nothing else in the game folder is touched.

## 3. Check that it is active

Open `%USERPROFILE%\Zomboid\console.txt` after loading a save and look for:
```
[RBS hook] ready (classpath) v5 - zombies and corpses use RBS_Zombie*_{Thin,Normal,Fat,Obese} model scripts; game classes match the patch source
[RBS hook] config: enabled=true thin/normal/fat/obese=15/55/22/8
[RBS hook] limbs config: enabled=true hand/forearm/arm/legs/one-leg=2.0/1.0/1.5/0.8/1.2 %
[RealBodyShape] zombie bodies: patch active, Zombie Bodies mod on, config enabled
[RealBodyShape] dismembered zombies: mod on
```
(the `limbs` lines only appear with the *Zombie Dismemberment* mod enabled.)
`status.bat` shows the same information without starting the game (launcher entry, jar, whether the patch still
matches the installed game version, mod found).

## 4. Configure the zombie mix

The default mix is **15 % thin, 55 % normal, 22 % fat, 8 % obese**. It is set in the Real Body Shape mod, file
`RealBodyShape.lua`, table:
```lua
RealBodyShape.ZOMBIES = { enabled = true, thin = 15, normal = 55, fat = 22, obese = 8 }
```
The numbers are relative weights (they do not have to add up to 100). `enabled = false` switches zombie bodies
off without uninstalling the patch. The mod writes these values to `%USERPROFILE%\Zomboid\Lua\RealBodyShape_zombies.txt`
every time a save is loaded and the patch re-reads that file within a few seconds, so changes apply without
restarting the game. For a Workshop-installed mod the file is at
`<Steam>\steamapps\workshop\content\108600\3830730496\mods\RealBodyShape\42\media\lua\client\RealBodyShape.lua`
(Workshop updates overwrite it, so keep a note of your values).

Each zombie's variant is derived from how it looks (skin, body hair, hair, beard, hair colour - the things the game
saves with a corpse), which is why a corpse keeps the body its zombie had, also after reloading the save.

## 4b. Dismembered zombies (optional, patch v5 or newer)

Enable the third mod of the Workshop item, **Real Body Shape - Zombie Dismemberment**. A small, fixed share of
zombies is then missing a hand, a forearm, a whole arm or one/both legs, with blood on the stump. Legless and
one-legged zombies crawl and never get up again; corpses keep the injuries. Who misses what is derived from the
zombie's looks, so it is the same zombie every time you meet it.

| Injury | Default share of all zombies |
|---|---|
| Hand missing | 2.0 % |
| Forearm missing (from the elbow) | 1.0 % |
| Whole arm missing | 1.5 % |
| Both legs missing (crawls) | 0.8 % |
| One leg missing (crawls) | 1.2 % |

The rates live in that mod's `RealBodyShapeLimbs.lua` (`RealBodyShape.LIMBS`); `enabled = false` switches the
feature off. The mod writes `%USERPROFILE%\Zomboid\Lua\RealBodyShape_limbs.txt` and the patch re-reads it within a
few seconds. Without the patch the mod still paints the blood and makes the legless ones crawl, but the limbs stay
visible. Zombies that look identical (same outfit, hair, skin and hair colour) share the same injuries - expected.

## 5. After a game update

Steam rewrites `ProjectZomboid64.json` when the game updates, which **removes the patch automatically** – the game
starts normally, zombies just share the player's body again. `status.bat` then says `patch NOT installed` or
`STALE` (the game's classes changed). Simply run `install.bat` again: it rebuilds `rbs_patch.jar` from the new
game version. If the game's internals changed too much, `install.bat` stops with a clear error and nothing is
installed.

## 6. Uninstall

Close the game and run **`uninstall.bat`**. It removes the classpath entry; the game starts exactly as before.
The jar in `%USERPROFILE%\Zomboid\RealBodyShape\` can stay or be deleted. If something ever goes wrong you can also
restore `ProjectZomboid64.json.rbs_backup` by hand.

## 7. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Project Zomboid folder not found` | Non-Steam install or unusual library path. Run `install.bat "<game folder>"`. |
| `ERROR: the JVM rejected the patched class ...` | Unsupported game version. Nothing was installed – report the game version. |
| `class ... not found in projectzomboid.jar` | Same: the game changed; wait for a patch update. |
| Game does not start after install | Run `uninstall.bat` (or restore the `.rbs_backup`). Then post `console.txt`. |
| No `[RBS hook]` lines in `console.txt` | The game was started before `install.bat` finished, or the launcher was reset by a game update. Run `status.bat`, then `install.bat`. |
| `[RBS hook] WARNING model script RBS_Zombie... not found` | The mod *Real Body Shape - Zombie Bodies* is not enabled (it is part of the Real Body Shape Workshop item, v0.3.1+). |
| `[RBS hook] WARNING the game's classes changed ... STALE` | Game updated while the old jar stayed in the launcher (rare). Run `install.bat`. |
| Zombies still look like the player | Check the three console lines in section 3; corpses created before the patch keep their old look until redrawn. |
| Skeleton zombies unchanged | Intended – skeletons use their own mesh. |

## 8. How it works (technical)

- The engine picks the shared mesh in two places: `ModelManager.getBodyModel(IsoGameCharacter)` for living
  characters and `HumanVisual.getModel()` for corpses drawn by `DeadBodyAtlas`. Neither can be changed from Lua.
- `install.bat` runs `rbs.MakePatch` on the game's `jre64`. It reads those two classes from **your**
  `projectzomboid.jar`, renames each method to `…$rbs` and adds a new method with the original name whose only
  body is `return (Model) rbs.RBSHook.xxx(this, …)`. The new body has no branches, so no StackMapTable is needed
  and the JVM verifier accepts it; the installer defines the patched classes in a throw-away class loader to prove it
  before writing anything.
- `rbs_patch.jar` (patched classes + hook) is listed **first** in the launcher's classpath, so the JVM loads these
  copies instead of the game's – nothing in the game folder is modified.
- `RBSHook` uses only public game methods through reflection. For a zombie it asks the script manager for
  `RBS_Zombie{Female,Male}_{Thin,Normal,Fat,Obese}` – model scripts and meshes from the *Real Body Shape - Zombie Bodies*
  mod – and otherwise calls the original method. Any failure falls back to the original behaviour.
- The jar stores SHA-256 hashes of the game classes it was built from; both `status.bat` and the hook at runtime
  compare them with the installed game and warn when they differ.
- **Missing limbs (v5):** `AnimationPlayer.getSkinTransforms(SkinningData)` returns the per-bone matrix palette
  that skins a mesh. For a zombie flagged by the *Zombie Dismemberment* mod, `rbs.Limbs` overwrites the matrices of
  the hidden bones (hand + fingers, forearm, upper arm, thigh + calf + foot) with a matrix that moves every vertex
  onto the joint the limb hangs from: those triangles become zero-area (invisible) and the few blended vertices at
  the joint pinch into a stump. The same palette skins every 3D clothing piece, so sleeves, gloves, trousers and
  shoes vanish with the limb - no extra meshes needed. `AnimatedModel.getAnimationPlayer()` is hooked only to learn
  which visual `DeadBodyAtlas` is drawing, so corpses get the same treatment. The mod's Lua and the hook compute the
  same seed from the zombie's looks (skin, body hair, hair, beard, hair colour - never the outfit, which corpses do
  not keep in the save), so blood, crawling and the hidden bones always agree, also after reloading.

## 9. FAQ

**Multiplayer?** Untested. The patch is client-side; a server would still think every zombie has the normal body.

**Linux / macOS?** The launcher scripts differ (`projectzomboid.sh`). The Java part would work; the installer is
Windows-only for now.

**Tomb's Player Body?** Works. Tomb users who built the local `RealBodyShapeTomb` data pack get Tomb-shaped zombie
variants; everyone else gets vanilla-shaped ones.

**Is this safe / allowed?** It is an unofficial modification of how the game is launched, fully reversible, with no
game code redistributed and no advantage in multiplayer. It is not endorsed by The Indie Stone. Use at your own risk.

## 10. Build from source

Requires a JDK 11 or newer (the bundled game runtime has no compiler).
```
build.bat            -> dist\rbs_agent.jar  and  RealBodyShapeZombiesPatch-vN.zip
```
Sources: `src/rbs/` – `ClassPatcher` (class-file rewriter), `RBSAgent` (patch specs, also usable as `-javaagent`
with a plain `java.exe`), `RBSHook` (runtime logic), `Limbs` (missing limbs, v5), `MakePatch` (installer), `Marker`, `PatchTest` / `CpCheck`
(offline checks). The GitHub Actions workflow builds the same zip on every push so the released jar can be
reproduced from source.

## 11. License

MIT – see `LICENSE`. Project Zomboid is a trademark of The Indie Stone; this project is not affiliated with them.

---

# (TH) คู่มือภาษาไทย

## มันทำอะไร
ปกติเกมใช้โมเดลร่างกาย "ตัวเดียว" ร่วมกันทั้งผู้เล่นและซอมบี้ทุกตัว mod Real Body Shape จึงทำให้ซอมบี้อ้วน/ผอมตามผู้เล่น
แพตช์นี้แก้จุดนั้น: ซอมบี้แต่ละตัว (และศพของมัน) ได้ร่างของตัวเอง ผอม / ปกติ / อ้วน / อ้วนมาก คงที่ต่อตัว ส่วนผู้เล่นยังเปลี่ยนตามน้ำหนักจริงเหมือนเดิม
ตั้งแต่ v5 ยังซ่อนแขน/ขาที่ขาดให้ mod ตัวเลือก Zombie Dismemberment ด้วย

## ต้องมีอะไร
- Windows + Project Zomboid Build 42 (Steam)
- Workshop item **Real Body Shape** 0.3.3 ขึ้นไป ซึ่งมี mod 3 ตัว: Real Body Shape, **Real Body Shape - Zombie Bodies** (ต้องเปิดทั้งคู่)
  และ **Real Body Shape - Zombie Dismemberment** (ตัวเลือก: แขน/ขาขาด)
- **ไม่ต้องลง Java** ใช้ Java ที่มากับเกม (`jre64`)

## ติดตั้ง
1. ดาวน์โหลด zip จากหน้า Releases แตกไว้ที่ไหนก็ได้
2. **ปิดเกม**
3. ดับเบิลคลิก `install.bat` → หน้าต่างดำจะพิมพ์ `patched` 4 บรรทัด, `verified` 4 บรรทัด, `written`, `launcher`, `DONE`
   ถ้าขึ้น `Project Zomboid folder not found` ให้เปิด cmd แล้วรัน `install.bat "D:\...\steamapps\common\ProjectZomboid"`
4. เปิดเกม → Mods → เปิดทั้ง Real Body Shape และ Real Body Shape - Zombie Bodies (จะเปิด Zombie Dismemberment ด้วยก็ได้) → โหลดเซฟ
5. เช็คใน `%USERPROFILE%\Zomboid\console.txt` ต้องมี `[RBS hook] ready (classpath) ...` และ `[RealBodyShape] zombie bodies: patch active`

สิ่งที่ถูกแก้ในเครื่อง: สร้าง `%USERPROFILE%\Zomboid\RealBodyShape\rbs_patch.jar` (สร้างจาก `projectzomboid.jar` ของคุณเอง)
และเติม 1 บรรทัดหน้า `"classpath"` ใน `ProjectZomboid64.json` ของเกม (สำรองไฟล์เดิมเป็น `.rbs_backup`) ไม่แตะไฟล์อื่นของเกมเลย

## ปรับสัดส่วนซอมบี้
ค่าเริ่มต้น ผอม 15 / ปกติ 55 / อ้วน 22 / อ้วนมาก 8 แก้ได้ที่ `RealBodyShape.ZOMBIES` ใน `RealBodyShape.lua` ของ mod
(`enabled = false` = ปิดเฉพาะส่วนซอมบี้โดยไม่ต้องถอดแพตช์) ค่าใหม่มีผลภายในไม่กี่วินาทีหลังโหลดเซฟ ไม่ต้องเปิดเกมใหม่

## ซอมบี้แขน/ขาขาด (ตัวเลือก, แพตช์ v5 ขึ้นไป)
เปิด mod ตัวที่ 3 ใน item: **Real Body Shape - Zombie Dismemberment** ซอมบี้ส่วนน้อย (รวมราว 6.5%) จะมือขาด / ขาดจากข้อศอก / แขนขาดทั้งท่อน /
ขาขาดข้างเดียวหรือสองข้าง (พวกขาขาดจะคลานและไม่ลุกอีก) มีเลือดที่ตอ ศพคงสภาพเดิม ตัวไหนขาดอะไรคำนวณจากหน้าตาของมัน จึงเป็นตัวเดิมทุกครั้ง
สัดส่วนแก้ได้ที่ `RealBodyShape.LIMBS` ใน `RealBodyShapeLimbs.lua` (`enabled = false` = ปิด) ถ้าไม่มีแพตช์จะเห็นแค่เลือดกับการคลาน แขนขายังอยู่

## เกมอัปเดต
Steam จะเขียน `ProjectZomboid64.json` ทับ แพตช์หลุดเองแบบปลอดภัย (เกมเปิดได้ปกติ) รัน `install.bat` อีกครั้ง มันจะสร้าง jar ใหม่ให้ตรงเวอร์ชัน
`status.bat` ใช้ดูว่าแพตช์ยังตรงกับเกมหรือไม่

## ถอด
ปิดเกม → `uninstall.bat` (หรือเอาไฟล์ `.rbs_backup` มาแทน json) เกมกลับเป็นปกติ

## ปัญหาที่พบบ่อย
- เกมเปิดไม่ขึ้นหลังติดตั้ง → `uninstall.bat` แล้วส่ง `console.txt` มาแจ้ง
- `WARNING model script RBS_Zombie... not found` → ยังไม่ได้เปิด mod Real Body Shape - Zombie Bodies
- ไม่มีบรรทัด `[RBS hook]` เลย → เกมเปิดก่อน install เสร็จ หรือเกมเพิ่งอัปเดต → รัน `status.bat` แล้ว `install.bat`
- ซอมบี้โครงกระดูกไม่เปลี่ยน → ตั้งใจ มันใช้โมเดลของตัวเองอยู่แล้ว

## ข้อควรรู้
แพตช์นี้ไม่เป็นทางการ แก้วิธีเปิดเกม (ไม่แก้ไฟล์เกม) ถอดได้ทั้งหมด ไม่แจกโค้ดของเกม ใช้เล่นคนเดียว ใช้ด้วยความเสี่ยงของตัวเอง
