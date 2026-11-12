# Real Body Shape – patch (zombie bodies, head look, soft body physics)

Unofficial companion for the Steam Workshop mod **[Real Body Shape](https://steamcommunity.com/sharedfiles/filedetails/?id=3830730496)**
(Project Zomboid Build 42, Windows).

The mod alone reshapes your character with its weight, diet and exercise. Some things cannot be done from Lua, and
this patch adds them:

- **Zombie bodies:** the engine shares one body mesh between the player and all zombies, so without the patch every
  zombie copies your character's shape. With it, **each zombie and each corpse gets its own body (thin, normal, fat
  or obese) and its own height**, and its clothes fit that body.
- **Head look (v6):** your character turns their head toward the nearest zombie in sight, and zombies chasing you
  look at you. Left standing still for two minutes, your character looks up at the camera.
- **Soft body physics (v6):** bellies, bust, buttocks and backpacks bounce as characters move, characters breathe,
  bodies shake when hit. Since v6.5, bags, packs, canteens, chest rigs and armour stay stiff: they sway but do not
  bounce like the body, and chest armour and chest rigs hold the bust and the belly.
- **Missing limbs (v5):** hands, forearms, arms and legs really go missing for the optional *Zombie Dismemberment*
  mod.
- **Other mods' clothes (v6.7):** with the optional mod *Real Body Shape - Other Mods' Clothes* on, 3D clothes the mod has no data
  for (most often from clothing mods) grow and shrink with your character too, roughly.
- **Other players in multiplayer (v6.7):** each player's weight is passed on through the server, and the other
  players get the body nearest to it (thin, normal, fat or obese, the zombies' bodies), clothes that follow it and a
  belly that bounces by their weight. Before, everyone of your sex wore your own shape.

> **Using ZombieBuddy?** You do not need this download. The Workshop item contains the same patch as a ZombieBuddy
> Java mod: enable **Real Body Shape - zombie patch through ZombieBuddy** in the mod list and you are done
> (ZombieBuddy asks once to allow its jar). With both installed, the ZombieBuddy one is used and this one steps aside.

| | Mod only | Mod + patch |
|---|---|---|
| Player body follows weight, diet, exercise | yes | yes |
| Vanilla 3D clothes, shoes, bags follow the body | yes | yes |
| Other mods' 3D clothes (no data in the mod) | keep their size | follow the body roughly (v6.7, mod 0.3.8+, the optional mod Real Body Shape - Other Mods' Clothes on) |
| Women's floor-sitting poses | yes | yes, and skirts lie over the lap |
| Head look | no | your character looks at zombies in sight; zombies chasing you look back |
| Soft body physics | no | bellies, bust, buttocks, backpacks, breathing, hits |
| Zombies | copy the player's shape | own shape (4 variants) and height per zombie |
| Other players (multiplayer) | the same sex as you: your shape | the variant nearest their weight (v6.7, mod 0.3.8+) |
| Corpses | copy the player's shape | keep the zombie's own shape |
| Dismembered zombies (optional mod) | blood + crawling only | limbs really missing, clothes included, on corpses too |

---

## 1. Requirements

- Windows, Steam version of Project Zomboid **Build 42** (developed on 42.21).
- The **Real Body Shape** Workshop item, version **0.3.4 or newer** (the physics needs the shader it ships). It
  contains four mods: **Real Body Shape**, **Real Body Shape - Zombie Bodies** (zombie body variants and heights),
  **Real Body Shape - Zombie Dismemberment** (optional: missing limbs) and **Real Body Shape - zombie patch through
  ZombieBuddy** (only for ZombieBuddy users, see above). With this download, enable the first two, and the third if
  you want it.
- **No Java installation needed** – the patch uses the Java runtime that comes with the game (`jre64` folder).
- Singleplayer, and multiplayer for the other players' bodies (v6.7, see the FAQ). The patch only changes what your
  own game draws; every player who wants to see it needs it on their PC.

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
   patched  : zombie/core/skinnedmodel/ModelManager (66343 -> 67170 bytes)
   patched  : zombie/core/skinnedmodel/visual/HumanVisual (30333 -> 30567 bytes)
   patched  : zombie/core/skinnedmodel/animation/AnimationPlayer (64133 -> 64870 bytes)
   patched  : zombie/core/skinnedmodel/advancedanimation/AnimatedModel (49592 -> 50163 bytes)
   patched  : zombie/core/textures/SmartTexture (23142 -> 23282 bytes)
   patched  : zombie/core/skinnedmodel/model/ModelInstanceTextureCreator (24056 -> 24383 bytes)
   verified : ... (the same six classes)
   written  : C:\Users\<you>\Zomboid\RealBodyShape\rbs_patch.jar (214 KB)
   launcher : C:/Users/<you>/Zomboid/RealBodyShape/rbs_patch.jar added first in "classpath" of D:\...\ProjectZomboid64.json
   backup   : D:\...\ProjectZomboid64.json.rbs_backup
   DONE. Start the game, enable the mods 'Real Body Shape' and 'Real Body Shape - Zombie Bodies' (+ 'Zombie Dismemberment' if you like), then check console.txt for '[RBS hook] ready'.
   ```
   The script finds the game through Steam (registry + `libraryfolders.vdf`). If it prints
   `Project Zomboid folder not found`, run it with the folder as argument from a command prompt:
   ```
   install.bat "D:\SteamLibrary\steamapps\common\ProjectZomboid"
   ```
   Updating from an older version: just run the new `install.bat`, it replaces the old entry.
4. Start the game, in *Mods* enable *Real Body Shape* and *Real Body Shape - Zombie Bodies* (and
   *Real Body Shape - Zombie Dismemberment* if you want missing limbs), load your save.
5. Walk around: zombies now come in different builds and heights, and your character looks at the ones in sight.

### What exactly was changed on your PC
- `%USERPROFILE%\Zomboid\RealBodyShape\rbs_patch.jar` – created. It contains six game classes patched
  **on your machine from your own `projectzomboid.jar`** plus the small hook (`rbs/*.class`). Nothing of the
  game is included in the download itself.
- `<game>\ProjectZomboid64.json` – one entry added first in `"classpath"`. The original file is kept as
  `ProjectZomboid64.json.rbs_backup`. Nothing else in the game folder is touched.

## 3. Check that it is active

Open `%USERPROFILE%\Zomboid\console.txt` after loading a save and look for:
```
[RBS hook] ready (classpath) v6.7 - zombies and corpses use RBS_Zombie*_{Thin,Normal,Fat,Obese} model scripts; game classes match the patch source
[RBS hook] config: enabled=true thin/normal/fat/obese=15/55/22/8
[RBS hook] jiggle config: enabled=true strength=50% players=true (from 82 kg) bust=60% bags=100% limbs=100% breathing=100% hits=100% buttocks=100% underwear only x1.30
[RBS hook] height config: enabled=true spread=+/-15.0%
[RBS hook] head look config: enabled=true range=8 tiles, zombies=true
[RBS hook] limbs config: enabled=true hand/forearm/arm/legs/one-leg=2.0/1.0/1.5/0.8/1.2 %
[RealBodyShape] zombie bodies: patch active (classpath), Zombie Bodies mod on, config enabled, heights +/-15%, soft bellies 50%, players from 82 kg
[RealBodyShape] dismembered zombies: mod on
```
(the `limbs` lines only appear with the *Zombie Dismemberment* mod enabled. With the ZombieBuddy mod instead, the
lines say `zombiebuddy` instead of `classpath`; with both, `this classpath copy steps aside` follows.)
`status.bat` shows the same information without starting the game (launcher entry, jar, whether the patch still
matches the installed game version, mod found).

## 4. Settings

Everything is set in the Real Body Shape mod, file `RealBodyShape.lua`. For a Workshop-installed mod it is at
`<Steam>\steamapps\workshop\content\108600\3830730496\mods\RealBodyShape\42\media\lua\client\RealBodyShape.lua`
(Workshop updates overwrite it, so keep a note of your values). The mod writes the values to
`%USERPROFILE%\Zomboid\Lua\RealBodyShape_*.txt` every time a save is loaded and the patch re-reads those files within
a few seconds, so changes apply without restarting the game.

```lua
-- zombie mix: relative weights (they do not have to add up to 100); enabled = false switches zombie bodies off
RealBodyShape.ZOMBIES = { enabled = true, thin = 15, normal = 55, fat = 22, obese = 8 }
-- zombie heights: spread = percent taller / shorter at the extremes (bell-shaped: most stay within a third of it)
RealBodyShape.HEIGHT = { enabled = true, spread = 15 }
-- soft body physics, in percent (0 = that part off): strength = bellies of fat zombies and of players over
-- player_from kg; limbs = buttocks / thighs / upper arms with the belly; bust; bags on the back; breathing (players);
-- hits = a shake when hit or falling; butt = women players' buttocks (bounce at any weight);
-- underwear = bust / buttocks with only underwear (or nothing) on them (130 = x1.3)
RealBodyShape.JIGGLE = { enabled = true, strength = 50, players = true, player_from = 82,
                         limbs = 100, bust = 60, bags = 100, breathing = 100, hits = 100, butt = 100, underwear = 130 }
-- head look: range in tiles; zombies = zombies chasing you look at you;
-- idle = seconds standing still before your character looks at the camera (0 = off)
RealBodyShape.LOOK = { enabled = true, range = 8, zombies = true, idle = 120 }
-- 3D clothes the mod has no data for (other mods' clothes): follow the body roughly while the optional mod
-- Real Body Shape - Other Mods' Clothes is on (enabled = false switches it off anyway); skip = models never stretched,
-- on the player or on zombies: a file name or the end of its path, as the console names it ("rough fit: <path>: ...")
RealBodyShape.ROUGH = { enabled = true, skip = {} }
```

Each zombie's variant and height are derived from how it looks (skin, body hair, hair, beard, hair colour - the
things the game saves with a corpse), which is why a corpse keeps the body its zombie had, also after reloading the
save.

### Head look
- Only zombies you can see on screen count: no hints about zombies behind walls, and zombies playing dead or
  sitting against a wall are never looked at (that would give them away).
- Looks down at crawlers. While aiming a gun your character watches the zombie in the line of fire, with a melee
  weapon raised a wider arc, and still glances at a zombie close beside them. Hair, hats and glasses turn with the
  head.
- Only the neck and head turn: the arms stay with the body, so a weapon stays in your hands (since v6.7).
- The head is left to the game in vehicles, asleep, climbing, lying down and when the game turns it itself.
- Looks only: what your character can see in the game does not change.
- Zombies that are chasing you turn their heads to you; the ones that have not noticed you don't.
- Standing still for two minutes (not walking, turning, aiming, sitting or doing an action, no zombie in sight),
  your character looks up at the camera. Any move hands the head back. Facing away from the camera, they don't turn.

### Other mods' clothes (v6.7)
- Turn on the optional mod *Real Body Shape - Other Mods' Clothes (needs the patch)* in the mod list.
- Real Body Shape reshapes the game's 3D clothes, and the ones it has data for, piece by piece. Others (most often
  from clothing mods) used to keep their original size on a fat or thin character. With the patch they follow the
  body roughly: the part of a piece on each bone is stretched or shrunk like the body around that bone.
- It is an approximation: a piece can sit a little loose or a little tight in places, and on a very heavy character
  a bit of skin can show at the crotch or the inner thighs.
- Your character only, and only on the game's body (not on another mod's body, e.g. a child's). Hair, beards and
  bags carried in the hand stay as they are. Pieces the mod has data for are left to the mod.
- Needs Real Body Shape 0.3.8 or newer: it tells the patch your shape and which pieces it has data for.
- A piece in its own space (a model file a hundred times smaller, another up axis) is converted to the game's, for
  zombies' clothes too. A piece rigged unlike the game's skeleton, or one without data that reaches more than about
  55 cm beyond the body (wings, capes, big props), is left as it is; the console says which and why.

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

Steam usually rewrites `ProjectZomboid64.json` when the game updates, which **removes the patch automatically** – the game
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
| The game closes right at the start, `console.txt` says "signer information" | The patch is older than v6.6 and the game is 42.22.2 or newer. Run `install.bat` from v6.6, or delete the folder `Zomboid\RealBodyShape` in your user folder to play without the patch. |
| Game does not start after install | Run `uninstall.bat` (or restore the `.rbs_backup`). Then post `console.txt`. |
| No `[RBS hook]` lines in `console.txt` | The game was started before `install.bat` finished, or the launcher was reset by a game update. Run `status.bat`, then `install.bat`. |
| `uninstall.bat` of v5 said `nothing to change` | ZombieBuddy's installer rewrites `ProjectZomboid64.json` with the classpath on one line, which v5 could not edit. Use the `uninstall.bat` of v6. |
| `[RBS hook] jiggle: shader RBSJiggle not found` | The Real Body Shape mod is older than 0.3.4. Update it (Workshop). |
| `[RBS hook] WARNING model script RBS_Zombie... not found` | The mod *Real Body Shape - Zombie Bodies* is not enabled. |
| `[RBS hook] WARNING the game's classes changed ... STALE` | Game updated while the old jar stayed in the launcher (rare). Run `install.bat`. |
| Zombies still look like the player | Check the console lines in section 3; corpses created before the patch keep their old look until redrawn. |
| Skeleton zombies unchanged | Intended – skeletons use their own mesh. |

## 8. How it works (technical)

- The engine picks the shared mesh in two places: `ModelManager.getBodyModel(IsoGameCharacter)` for living
  characters and `HumanVisual.getModel()` for corpses drawn by `DeadBodyAtlas`. Neither can be changed from Lua.
- `install.bat` runs `rbs.MakePatch` on the game's `jre64`. It reads the target classes from **your**
  `projectzomboid.jar`, renames each target method to `…$rbs` and adds a new method with the original name whose
  only body is a call to `rbs.RBSHook` (which calls the original). The new body has no branches, so no StackMapTable
  is needed and the JVM verifier accepts it; the installer defines the patched classes in a throw-away class loader
  to prove it before writing anything.
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
- **Zombie clothes (v5):** the main mod reshapes *your* 3D clothes by rewriting their model files, and every
  character wearing the same piece loads the same file, so zombies of your sex used to wear your shape (and a fat
  zombie's clothes were then pushed out a second time). The *Zombie Bodies* mod ships untouched copies of those
  files under `media/models_X/RBSClean/`; `ModelManager.newAdditionalModelInstance(...)` is hooked so a zombie's
  clothing model loads the copy instead (`rbs.Clean`; only when the copy is the same file type the game would load).
  Corpses build their clothes in `AnimatedModel.addClothingItem(...)` without a character, so that method is hooked
  too, to tell `Clean` the clothes belong to a zombie. On top of the copies, `rbs.Fit` multiplies a per-bone
  bind-space map (Normal body -> the zombie's variant) into the skin matrices of every non-body mesh, so the
  clothes follow thin / fat / obese bodies.
- **Other mods' clothes on the player (v6.7):** `rbs.PlayerFit` does for the player what `Fit` does for zombies,
  from tables fitted offline per shape parameter (`PlayerFitTable`: fat, thin, belly, chest, arms, legs, bust, hips,
  waist), mixed with the shape the mod last built the body for (`RealBodyShape_built.txt`). A piece is recognised
  by its model path (`ModelMesh.getPath()`); the mod lists the models it has data for in `RealBodyShape_fit.txt`
  (written at every game start; an older file means the mod is off and nothing is done), and those, hair, beards
  and bags carried in the hand are left alone.
- **Heights (v6):** `rbs.Height` works on the model-space bone matrices right after
  `AnimationPlayer.UpdateSkinTransforms()` (and `transformRootChildBones`, which rebuilds them on a turn-around).
  Not a scale of the whole model: the legs, trunk, neck and arms get longer or shorter, the head, hands and feet keep
  their size, and the body moves so the feet stay on the ground. Only joint positions change, so clothes, held
  items, corpses and the game's hit points follow, and nothing builds up from frame to frame.
- **Head look (v6):** `rbs.HeadLook` turns the neck (35 %) and head (65 %) in model space at the same point, smoothed
  and speed-limited. It picks the nearest zombie the player is shown on screen; it never changes what the game
  thinks the character sees. Standing still, the target is the camera of the isometric view (south-east, 30 degrees
  up).
- **Soft body physics (v6):** `rbs.Jiggle` runs a small spring per character (belly, bust, buttocks, bag, breathing,
  hit shake) and writes the offsets into the two last slots of the matrix palette (58 and 59, unused by the game's
  skeletons). The body and clothes are drawn with the shader `RBSJiggle` that Real Body Shape 0.3.4+ ships (the
  game's `basicEffect` plus those offsets), which moves each vertex by its bind-pose position, so the body and every
  clothing piece on it move together. Since v6.5, pieces that are stiff or hang on the body (the `BackPacks` model
  folder, canteens, chest rigs, skinned armour) get `RBSJiggleBag` instead, the same shader with `RBS_BAG` defined
  (Real Body Shape 0.3.6+): only the belly, breathing and their own sway, so the bust, buttocks and thighs move under
  them. Torso armour and chest rigs also hold the player's bust and belly (25 %). Without that shader they move as before.
- **Face layer (prepared, v6):** `SmartTexture.calculate()` and `ModelInstanceTextureCreator.init(...)` are hooked so a
  later version of the mod can add a finer face layer to player skins. Without the layer files nothing happens.
- **ZombieBuddy build:** the same runtime classes, renamed to the package `rbszb` and loaded by ZombieBuddy with
  Byte Buddy advice on the same ten methods (`zb/`). The classpath copy checks whether that build is running and then
  only passes calls through, so the two never apply twice.

## 9. FAQ

**ZombieBuddy?** Use the ZombieBuddy mod of the Workshop item instead of this download (see the top of this page).
Both together are fine: the ZombieBuddy one is used.

**Multiplayer?** The patch is client-side: it only changes what your own game draws. Since v6.7 (mod 0.3.8+, with
*Zombie Bodies* on) the other players get the body variant nearest their weight, which their game sends through the
server whenever it changes by 1 kg; until it arrives they have the normal body. They get one of four bodies, not
their exact shape (their muscles and style are not shown). `RealBodyShape.ZOMBIES.players = false` switches it off.
The head look is shown for your own character only, and the server still thinks every zombie has the normal body.

**Linux / macOS?** The launcher scripts differ (`projectzomboid.sh`). The Java part would work; the installer is
Windows-only for now.

**A mod that replaces the body?** The zombie variants are made from the vanilla body, so zombies get
vanilla-shaped bodies.

**Is this safe / allowed?** It is an unofficial modification of how the game is launched, fully reversible, with no
game code redistributed and no advantage in multiplayer. It is not endorsed by The Indie Stone. Use at your own risk.

## 10. Build from source

Requires a JDK 11 or newer (the bundled game runtime has no compiler).
```
build.bat            -> dist\rbs_agent.jar  and  RealBodyShapeZombiesPatch-vN.zip
```
Sources: `src/rbs/` – `ClassPatcher` (class-file rewriter), `RBSAgent` (patch specs, also usable as `-javaagent`
with a plain `java.exe`), `RBSHook` (runtime logic), `Limbs` (missing limbs, v5), `Fit` / `FitTable` / `Clean`
(zombie clothes, v5), `Height`, `HeadLook`, `Jiggle`, `FaceLayer` (v6), `MakePatch` (installer), `Marker`,
`PatchTest` / `CpCheck` / `VerifyLoader` (offline checks). `zb/` – the ZombieBuddy patches (`rbszb/Patches.java`)
and their offline check; that jar is built against the game and ZombieBuddy jars and ships in the Workshop item.
The GitHub Actions workflow builds the same zip on every push so the released jar can be reproduced from source.

## 11. License

MIT – see `LICENSE`. Project Zomboid is a trademark of The Indie Stone; this project is not affiliated with them.

---

# (TH) คู่มือภาษาไทย

## มันทำอะไร
ปกติเกมใช้โมเดลร่างกาย "ตัวเดียว" ร่วมกันทั้งผู้เล่นและซอมบี้ทุกตัว mod Real Body Shape จึงทำให้ซอมบี้อ้วน/ผอมตามผู้เล่น
แพตช์นี้แก้จุดนั้น: ซอมบี้แต่ละตัว (และศพของมัน) ได้ร่างของตัวเอง ผอม / ปกติ / อ้วน / อ้วนมาก และส่วนสูงของตัวเอง คงที่ต่อตัว ส่วนผู้เล่นยังเปลี่ยนตามน้ำหนักจริงเหมือนเดิม
- v6: ตัวละครหันหัวมองซอมบี้ที่มองเห็น ซอมบี้ที่ไล่เราหันมามอง ยืนนิ่งครบ 2 นาทีตัวละครเงยหน้ามองกล้อง (แค่ภาพ ไม่เปลี่ยนการมองเห็นในเกม)
- v6: ส่วนนิ่มกระเพื่อม ท้อง หน้าอก ก้น กระเป๋าหลัง การหายใจ การสั่นตอนโดนตี และกระโปรงคลุมตักตอนนั่ง
- v6.5: กระเป๋า เป้ กระติกน้ำ chest rig และเกราะ ไม่เด้งตามเนื้อแล้ว (แกว่งของมันเอง) เกราะลำตัวกับ chest rig รัดอกและพุงให้เด้งน้อยลง (ต้องใช้ mod 0.3.6 ขึ้นไป)
- v6.6: ใช้กับเกม 42.22.2 ได้ (เกมรุ่นนี้เซ็นไฟล์ jar รุ่นเก่าของ patch ทำให้เกมปิดตอนเริ่ม), ใส่แค่ชุดชั้นใน หน้าอกกับก้นเด้งน้อยลง และแจ็กเก็ตกับเสื้อโค้ตของ mod เสื้อผ้าบางตัวนับเป็นเสื้อผ้าแล้ว
- v6.7: ตอนถืออาวุธแล้วหัวหันไปหาซอมบี้ มือไม่หลุดจากอาวุธแล้ว (เดิมแขนหมุนตามคอ ขวานเหมือนลอยออกจากมือ)
- v6.7: เสื้อผ้า 3D ที่ mod ไม่มีข้อมูล (ส่วนใหญ่มาจาก mod เสื้อผ้าอื่น) อ้วน/ผอมตามตัวละครแบบคร่าวๆ (ต้องใช้ mod 0.3.8 ขึ้นไป และเปิด mod เสริม Real Body Shape - Other Mods' Clothes)
- v6.7: เล่นหลายคน ผู้เล่นคนอื่นได้ร่างที่ใกล้น้ำหนักของเขาที่สุด (ผอม / ปกติ / อ้วน / อ้วนมาก แบบเดียวกับซอมบี้) เสื้อผ้าและการเด้งตามร่างนั้น น้ำหนักส่งผ่าน server (เดิมคนเพศเดียวกับเราได้หุ่นของเรา; ต้องใช้ mod 0.3.8 ขึ้นไป และเปิด Zombie Bodies)
- v5: ซ่อนแขน/ขาที่ขาดให้ mod ตัวเลือก Zombie Dismemberment

**ใช้ ZombieBuddy อยู่แล้ว?** ไม่ต้องโหลดตัวนี้ ใน Workshop item มี **Real Body Shape - zombie patch through ZombieBuddy** เปิดใน mod list ได้เลย ถ้าลงทั้งสองแบบ จะใช้ตัว ZombieBuddy

## ต้องมีอะไร
- Windows + Project Zomboid Build 42 (Steam)
- Workshop item **Real Body Shape 0.3.4** ขึ้นไป เปิด Real Body Shape กับ **Real Body Shape - Zombie Bodies**
  และ **Real Body Shape - Zombie Dismemberment** ถ้าต้องการแขน/ขาขาด
- **ไม่ต้องลง Java** ใช้ Java ที่มากับเกม (`jre64`)

## ติดตั้ง
1. ดาวน์โหลด zip จากหน้า Releases แตกไว้ที่ไหนก็ได้
2. **ปิดเกม**
3. ดับเบิลคลิก `install.bat` → หน้าต่างดำจะพิมพ์ `patched` 6 บรรทัด, `verified` 6 บรรทัด, `written`, `launcher`, `DONE`
   ถ้าขึ้น `Project Zomboid folder not found` ให้เปิด cmd แล้วรัน `install.bat "D:\...\steamapps\common\ProjectZomboid"`
   อัปเดตจากเวอร์ชันเก่า: รัน `install.bat` ตัวใหม่ได้เลย มันแทนของเดิมให้
4. เปิดเกม → Mods → เปิดทั้ง Real Body Shape และ Real Body Shape - Zombie Bodies (จะเปิด Zombie Dismemberment ด้วยก็ได้) → โหลดเซฟ
5. เช็คใน `%USERPROFILE%\Zomboid\console.txt` ต้องมี `[RBS hook] ready (classpath) v6.7 ...` และ `[RealBodyShape] zombie bodies: patch active`

สิ่งที่ถูกแก้ในเครื่อง: สร้าง `%USERPROFILE%\Zomboid\RealBodyShape\rbs_patch.jar` (สร้างจาก `projectzomboid.jar` ของคุณเอง)
และเติม 1 รายการหน้า `"classpath"` ใน `ProjectZomboid64.json` ของเกม (สำรองไฟล์เดิมเป็น `.rbs_backup`) ไม่แตะไฟล์อื่นของเกมเลย

## ตั้งค่า
ทุกค่าอยู่ใน `RealBodyShape.lua` ของ mod: `RealBodyShape.ZOMBIES` (สัดส่วนซอมบี้ ผอม 15 / ปกติ 55 / อ้วน 22 / อ้วนมาก 8),
`RealBodyShape.HEIGHT` (ส่วนสูง ±15%), `RealBodyShape.JIGGLE` (การกระเพื่อม เป็น %), `RealBodyShape.LOOK` (การหันหัว; `idle` = ยืนนิ่งกี่วินาทีแล้วมองกล้อง, 0 = ปิด)
(`enabled = false` = ปิดส่วนนั้นโดยไม่ต้องถอดแพตช์) ค่าใหม่มีผลภายในไม่กี่วินาทีหลังโหลดเซฟ ไม่ต้องเปิดเกมใหม่

## ซอมบี้แขน/ขาขาด (ตัวเลือก, แพตช์ v5 ขึ้นไป)
เปิด mod ตัวที่ 3 ใน item: **Real Body Shape - Zombie Dismemberment** ซอมบี้ส่วนน้อย (รวมราว 6.5%) จะมือขาด / ขาดจากข้อศอก / แขนขาดทั้งท่อน /
ขาขาดข้างเดียวหรือสองข้าง (พวกขาขาดจะคลานและไม่ลุกอีก) มีเลือดที่ตอ ศพคงสภาพเดิม ตัวไหนขาดอะไรคำนวณจากหน้าตาของมัน จึงเป็นตัวเดิมทุกครั้ง
สัดส่วนแก้ได้ที่ `RealBodyShape.LIMBS` ใน `RealBodyShapeLimbs.lua` (`enabled = false` = ปิด) ถ้าไม่มีแพตช์จะเห็นแค่เลือดกับการคลาน แขนขายังอยู่

## เกมอัปเดต
Steam จะเขียน `ProjectZomboid64.json` ทับ แพตช์หลุดเองแบบปลอดภัย (เกมเปิดได้ปกติ) รัน `install.bat` อีกครั้ง มันจะสร้าง jar ใหม่ให้ตรงเวอร์ชัน
`status.bat` ใช้ดูว่าแพตช์ยังตรงกับเกมหรือไม่

## ถอด
ปิดเกม → `uninstall.bat` (หรือเอาไฟล์ `.rbs_backup` มาแทน json) เกมกลับเป็นปกติ
ถ้าเคยใช้ `uninstall.bat` ของ v5 แล้วขึ้น `nothing to change` (installer ของ ZombieBuddy เขียน json ใหม่เป็นบรรทัดเดียว) ให้ใช้ตัวของ v6

## ปัญหาที่พบบ่อย
- เกมปิดทันทีตอนเริ่ม และ `console.txt` มีคำว่า signer information → patch เก่ากว่า v6.6 ใช้กับเกม 42.22.2 ไม่ได้ ให้รัน `install.bat` จาก v6.6 หรือลบโฟลเดอร์ `Zomboid\RealBodyShape` ในโฟลเดอร์ผู้ใช้เพื่อเล่นแบบไม่มี patch
- เกมเปิดไม่ขึ้นหลังติดตั้ง → `uninstall.bat` แล้วส่ง `console.txt` มาแจ้ง
- `jiggle: shader RBSJiggle not found` → mod Real Body Shape เก่ากว่า 0.3.4 ให้อัปเดต
- `WARNING model script RBS_Zombie... not found` → ยังไม่ได้เปิด mod Real Body Shape - Zombie Bodies
- ไม่มีบรรทัด `[RBS hook]` เลย → เกมเปิดก่อน install เสร็จ หรือเกมเพิ่งอัปเดต → รัน `status.bat` แล้ว `install.bat`
- ซอมบี้โครงกระดูกไม่เปลี่ยน → ตั้งใจ มันใช้โมเดลของตัวเองอยู่แล้ว

## ข้อควรรู้
แพตช์นี้ไม่เป็นทางการ แก้วิธีเปิดเกม (ไม่แก้ไฟล์เกม) ถอดได้ทั้งหมด ไม่แจกโค้ดของเกม เปลี่ยนแค่ภาพในเครื่องตัวเอง (เล่นหลายคน: ใครอยากเห็นร่างของคนอื่นต้องลงในเครื่องตัวเอง) ใช้ด้วยความเสี่ยงของตัวเอง
