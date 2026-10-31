# Real Body Shape – Zombie Bodies patch (Project Zomboid Build 42, Windows)

Unofficial companion for the Steam Workshop item **Real Body Shape** (id 3830730496, version 0.3.1 or newer), which
contains two mods: *Real Body Shape* and *Real Body Shape - Zombie Bodies*.
Zombies and their corpses get their own body shapes (thin / normal / fat / obese, fixed per zombie) instead of
copying the player's shape.

Full manual, troubleshooting and source code: https://github.com/zaturazzo/RealBodyShape-ZombiePatch

## Quick install
1. Subscribe to **Real Body Shape** on the Workshop; it brings both mods.
2. **Close the game.** Run `install.bat` – it builds `rbs_patch.jar` from **your** `projectzomboid.jar` using the game's own
   Java runtime (no Java installation needed) and adds one line to `<game>\ProjectZomboid64.json`
   (backup: `ProjectZomboid64.json.rbs_backup`). If the game folder is not found:
   `install.bat "D:\path\to\steamapps\common\ProjectZomboid"`.
3. Start the game, enable **both** mods (*Real Body Shape*, *Real Body Shape - Zombie Bodies*), load your save. `%USERPROFILE%\Zomboid\console.txt` should contain
   `[RBS hook] ready (classpath) ...` and `[RealBodyShape] zombie bodies: patch active, Zombie Bodies mod on`.

- `status.bat` – what is installed, and whether the patch still matches the game version.
- `uninstall.bat` – removes the patch again (the game starts exactly as before).
- After a game update the patch switches itself off; run `install.bat` again.
- Zombie mix (default 15/55/22/8) is set in the mod: `RealBodyShape.ZOMBIES` in `RealBodyShape.lua`.

## (TH) ติดตั้งแบบย่อ
Subscribe Real Body Shape (0.3.1+) → ปิดเกม → รัน `install.bat` → เปิดเกม เปิด mod ทั้ง 2 ตัว (Real Body Shape, Real Body Shape - Zombie Bodies) → โหลดเซฟ
ถอดด้วย `uninstall.bat`, ดูสถานะด้วย `status.bat`, เกมอัปเดตแล้วรัน `install.bat` ซ้ำ ไม่ต้องลง Java เพิ่ม คู่มือละเอียดอยู่ในหน้า GitHub

**Disclaimer**: unofficial, not affiliated with The Indie Stone. Use at your own risk; fully reversible with `uninstall.bat`.
No game code is distributed in this package – the patched classes are generated on your PC from your own game files.
