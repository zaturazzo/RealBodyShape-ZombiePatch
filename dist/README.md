# Real Body Shape – patch v6.7 (Project Zomboid Build 42, Windows)

Unofficial companion for the Steam Workshop item **Real Body Shape** (id 3830730496, version 0.3.4 or newer; 0.3.6 for stiff bags and armour), which
contains four mods: *Real Body Shape*, *Real Body Shape - Zombie Bodies*, *Real Body Shape - Zombie Dismemberment*
and *Real Body Shape - zombie patch through ZombieBuddy*.
- Head look: your character turns their head toward the nearest zombie in sight; zombies chasing you look at you;
  left standing still for two minutes, your character looks up at the camera.
- Soft body physics: bellies, bust, buttocks and backpacks bounce, characters breathe, bodies shake when hit; skirts
  lie over the lap when sitting. Bags, packs, canteens, chest rigs and armour stay stiff (v6.5): they sway but do not
  bounce like the body, and chest armour and chest rigs hold the bust and the belly.
- Zombie Bodies: zombies and their corpses get their own body shapes (thin / normal / fat / obese) and heights,
  fixed per zombie, instead of copying the player's shape; their clothes follow that body.
- Zombie Dismemberment (optional): a few percent of zombies are missing a hand, forearm, arm or leg (legless ones
  crawl), clothes included, corpses too.
- Other mods' clothes (v6.7, needs Real Body Shape 0.3.8 and its optional mod Real Body Shape - Other Mods' Clothes): 3D clothes
  the mod has no data for grow and shrink with your character too, roughly.

**Using ZombieBuddy?** You do not need this zip: enable *Real Body Shape - zombie patch through ZombieBuddy* in the
mod list instead. With both installed, the ZombieBuddy one is used.

Full manual, troubleshooting and source code: https://github.com/zaturazzo/RealBodyShape-ZombiePatch

## Quick install
1. Subscribe to **Real Body Shape** on the Workshop; it brings all four mods.
2. **Close the game.** Run `install.bat` – it builds `rbs_patch.jar` from **your** `projectzomboid.jar` using the game's own
   Java runtime (no Java installation needed) and adds one entry to `<game>\ProjectZomboid64.json`
   (backup: `ProjectZomboid64.json.rbs_backup`). If the game folder is not found:
   `install.bat "D:\path\to\steamapps\common\ProjectZomboid"`. Updating from an older version: just run it, it
   replaces the old entry.
3. Start the game, enable *Real Body Shape* and *Real Body Shape - Zombie Bodies* (plus *Zombie Dismemberment* if you
   want), load your save. `%USERPROFILE%\Zomboid\console.txt` should contain `[RBS hook] ready (classpath) v6.7 ...` and
   `[RealBodyShape] zombie bodies: patch active (classpath), Zombie Bodies mod on`.

- `status.bat` – what is installed, and whether the patch still matches the game version.
- `uninstall.bat` – removes the patch again (the game starts exactly as before).
- After a game update, run `install.bat` again. If the game closes right at the start, install the newest patch (v6.6 or later is needed for game 42.22.2), or delete the folder `Zomboid\RealBodyShape` in your user folder to play without the patch.
- Settings are in the mod's `RealBodyShape.lua`: `RealBodyShape.ZOMBIES` (zombie mix, default 15/55/22/8),
  `HEIGHT`, `JIGGLE`, `LOOK`; limb rates in `RealBodyShape.LIMBS` of `RealBodyShapeLimbs.lua`.

## (TH) ติดตั้งแบบย่อ
Subscribe Real Body Shape (0.3.4+) → ปิดเกม → รัน `install.bat` → เปิดเกม เปิด mod Real Body Shape + Zombie Bodies (และ Zombie Dismemberment ถ้าต้องการ) → โหลดเซฟ
ใช้ ZombieBuddy อยู่แล้วไม่ต้องใช้ zip นี้ เปิด mod "zombie patch through ZombieBuddy" แทน
ถอดด้วย `uninstall.bat`, ดูสถานะด้วย `status.bat`, เกมอัปเดตแล้วรัน `install.bat` ซ้ำ ไม่ต้องลง Java เพิ่ม คู่มือละเอียดอยู่ในหน้า GitHub
