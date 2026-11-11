package rbs;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The player's head turns towards the nearest zombie they can see (Real Body Shape, zombie patch v6+).
 *
 * Only what the player already sees on screen: a zombie whose target alpha for this player is low (out of sight,
 * behind a wall) is never looked at, and neither are zombies that lie still faking death or sit against a wall - the
 * head would give them away. A crawling zombie is looked down at. While aiming the head looks where the gun points
 * (straight ahead with the body, which turns to the aim), or at the nearest zombie in that line (AIM_CONE); in a
 * melee stance it looks at the nearest zombie in a wide cone in front (MELEE_CONE). With nothing in the cone, a
 * zombie close by outside it gets a glance (part of the way, GLANCE).
 * Looking down, the head takes only part of the angle (the eyes do the rest) and never bends far: at a crawler or a
 * zombie on the floor right in front, the whole angle bent the head too deep (user, 2026-11-07).
 *
 * Done on the model-space bone matrices right after the pose is made (like Height): the neck turns a third of the way,
 * the head the rest, each with everything below it in the skeleton (hats, glasses, hair are skinned to the head).
 * Only what is drawn changes: the game's own head look (sitting on furniture), vision cone and aiming are untouched,
 * and the head is left alone while the game turns it itself, in a vehicle, asleep, climbing or lying down.
 * Zombies chasing a player turn their head to that player (user, 2026-11-07): only those that already have the player
 * as their target (an idle zombie's head never tells the player who has noticed them), never one faking death or
 * sitting against a wall, nor one knocked down, hit, climbing; at most Z_MAX_YAW to the side. A crawler, lying flat,
 * turns about the vertical and lifts its face.
 * Standing still for a while (idle seconds: not moving, not turning, not aiming, no action, not sitting) with no
 * zombie to look at, the player looks up at the camera (user, 2026-11-07); any move hands the head back. The camera
 * of the isometric view looks from the south-east, 30 degrees down: a character facing away from it does not turn.
 * Config: ~/Zomboid/Lua/RealBodyShape_look.txt (enabled=1, range=<tiles>, zombies=1, idle=<seconds, 0 = off>).
 */
final class HeadLook {
    static final String CONFIG = "RealBodyShape_look.txt";
    static final float MAX_YAW = (float) Math.toRadians(75), MAX_PITCH_UP = (float) Math.toRadians(20),
            MAX_PITCH_DOWN = (float) Math.toRadians(30), BEHIND = (float) Math.toRadians(110);
    /** the share of the up / down angle to a target that the head bends (the eyes look the rest of the way) */
    static final float PITCH_SHARE = 0.6f;
    /** while aiming a gun: zombies this close to the aim line (each side) may be looked at; in a melee stance (an axe
     *  ready) zombies come from all round: a wide cone */
    static final float AIM_CONE = (float) Math.toRadians(20), MELEE_CONE = (float) Math.toRadians(60);
    /** aiming / in a stance, nothing in the cone: a zombie this close outside it (tiles) is glanced at - the head turns
     *  this share of the way, at most GLANCE_MAX (user, 2026-11-07) */
    static final float GLANCE_RANGE = 3.5f, GLANCE = 0.4f, GLANCE_MAX = (float) Math.toRadians(30);
    /** zombies: how far to the side the head turns, how far the player may be (tiles), a crawler's eyes (tiles) and
     *  how far it lifts its face */
    static final float Z_MAX_YAW = (float) Math.toRadians(60), Z_BEHIND = (float) Math.toRadians(100), Z_RANGE = 10f,
            Z_CRAWL_EYE = 0.3f, Z_CRAWL_UP = (float) Math.toRadians(35);
    static final float NECK_SHARE = 0.35f;
    /** turning to a target / back to straight ahead (s), and the fastest turn (rad/s) */
    static final float TAU_TO = 0.18f, TAU_BACK = 0.45f, MAX_SPEED = (float) Math.toRadians(300);
    /** heights in tiles: eyes standing / sneaking, a standing zombie's face, a crawler on the floor */
    static final float EYE = 1.6f, EYE_LOW = 1.2f, FACE = 1.55f, CRAWLER = 0.25f;
    /** world left of the facing (fy, -fx) for x east / y south; flip here if the head turns away */
    static final float YAW_SIGN = 1f;
    /** where the camera is from a character: south-east on the ground (x east, y south), CAM_PITCH up (the 2:1
     *  isometric view looks 30 degrees down); the head turns to it slower than to a zombie (TAU_IDLE) */
    static final float CAM_X = 0.70710677f, CAM_Y = 0.70710677f, CAM_PITCH = (float) Math.toRadians(30), TAU_IDLE = 0.6f;
    /** standing still: moved less than this (tiles) and turned less than this (cos) since the clock started */
    static final float STILL_MOVE = 0.05f, STILL_TURN = (float) Math.cos(Math.toRadians(3));

    private static volatile boolean enabled = true, zombiesOn = true, failed, ready;
    private static volatile float range = 8f, idleSec = 0f;
    private static long cfgMtime = -1, cfgNextCheck;

    private static Method apCharacter, apSkinning, apCount, apModelAt, sdNumBones, sdBoneAt, sdParent;
    private static Method mStore, mLoad;
    private static Field fBoneName;
    private static Class<?> playerClass, zombieClass;
    private static Method isLocal, playerNum, chrX, chrY, chrZ, chrForward, v2X, v2Y, getCell, zombieList, targetAlpha;
    private static Method inVehicle, asleep, climbing, onFloor, dead, headLookAround, aiming, sneaking;
    private static Method zCrawling, zFakeDead, zSitWall, primaryItem, isRanged, zTarget, knockedDown, hitReaction;
    private static Method sitGround, sitFurniture, timedActions, performing;     // optional: null = not there
    private static Class<?> weaponClass;

    private static final Map<Object, Skel> skels = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, State> states = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<FloatBuffer> BUF = ThreadLocal.withInitial(() -> FloatBuffer.allocate(16));
    private static final ThreadLocal<float[]> M = ThreadLocal.withInitial(() -> new float[16]);
    private static int diag, warnings;
    private static long turned, summaryAt, zTurned, zSummaryAt;

    private HeadLook() {}

    static final class Skel {
        int n, neck = -1, head = -1, lArm = -1, rArm = -1;
        int[] neckTree, headTree;     // the neck / head bone and everything below it
        boolean ok;
    }

    static final class State {
        float yaw, pitch;             // now (rad): + yaw = to the character's left, + pitch = up
        long t;
        String why = "";              // what it looks at, for the log
        boolean zombie;
        // standing still: where and which way the player stood when the clock started
        float rx, ry, rfx, rfy;
        long stillSince;
        boolean hasRef, idle;
    }

    // ---------------- hook ----------------

    /** after the pose: advance where the head looks (once per update) and turn it */
    static void update(Object player) {
        if (failed || player == null) return;
        try {
            loadConfig();
            if (!enabled && !zombiesOn) return;
            if (!ready) init();
            if (failed) return;
            Object chr = apCharacter.invoke(player);
            if (chr == null) return;
            if (zombieClass.isInstance(chr)) {
                if (zombiesOn) zombieUpdate(player, chr);
                return;
            }
            if (!enabled || !playerClass.isInstance(chr) || !(Boolean) isLocal.invoke(chr)) return;
            State s = states.get(player);
            if (s == null) {
                s = new State();
                states.put(player, s);
            }
            long now = System.nanoTime();
            float dt = s.t == 0 ? 0f : Math.min(0.1f, (now - s.t) / 1e9f);
            s.t = now;
            float[] want = { 0f, 0f };
            boolean free = free(chr);
            boolean aim = free && (Boolean) aiming.invoke(chr);
            boolean sees = false;
            if (free) sees = target(chr, want, s, aim ? (gun(chr) ? AIM_CONE : MELEE_CONE) : BEHIND);
            else s.why = "busy";
            s.idle = idleLook(chr, s, now, want, !free || aim || sees);
            float tau = s.idle ? TAU_IDLE : (want[0] != 0f || want[1] != 0f) ? TAU_TO : TAU_BACK;
            s.yaw = approach(s.yaw, want[0], dt, tau);
            s.pitch = approach(s.pitch, want[1], dt, tau);
            if (!free) {                                        // the game or the pose has the head: hand it back at once
                s.yaw = 0f;
                s.pitch = 0f;
            }
            turn(player, s);
        } catch (Throwable t) {
            // the zombie list can change under us (another thread): skip this pose, try again on the next one
            if (warnings++ < 5) RBSHook.warn("head look skipped a pose: " + t);
        }
    }

    /** the turn-around event rebuilt the matrices: turn the head again by what it is at now (no time passes) */
    static void reapply(Object player) {
        if (failed || player == null || !enabled || !ready) return;
        try {
            State s = states.get(player);
            if (s != null) turn(player, s);
        } catch (Throwable t) {
            RBSHook.warn("head look (turn) failed: " + t);
        }
    }

    /** a zombie: its head towards the player it chases */
    private static void zombieUpdate(Object player, Object z) throws Exception {
        State s = states.get(player);
        Object t = zTarget.invoke(z);
        boolean free = t != null && playerClass.isInstance(t) && zombieFree(z);
        float[] want = { 0f, 0f };
        if (free) {
            float dx = ((Number) chrX.invoke(t)).floatValue() - ((Number) chrX.invoke(z)).floatValue();
            float dy = ((Number) chrY.invoke(t)).floatValue() - ((Number) chrY.invoke(z)).floatValue();
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            boolean floor = Math.abs(((Number) chrZ.invoke(t)).floatValue() - ((Number) chrZ.invoke(z)).floatValue()) <= 0.5f;
            Object fw = chrForward.invoke(z);
            float fx = ((Number) v2X.invoke(fw)).floatValue(), fy = ((Number) v2Y.invoke(fw)).floatValue();
            float fl = (float) Math.sqrt(fx * fx + fy * fy);
            if (floor && d > 0.2f && d < Z_RANGE && fl > 1e-4f) {
                fx /= fl;
                fy /= fl;
                float a = (float) Math.atan2(YAW_SIGN * (dx * fy - dy * fx), dx * fx + dy * fy);
                if (Math.abs(a) <= Z_BEHIND) {
                    boolean crawl = (Boolean) zCrawling.invoke(z);
                    float face = (Boolean) onFloor.invoke(t) ? CRAWLER : (Boolean) sneaking.invoke(t) ? EYE_LOW : FACE;
                    float pitch = (float) Math.atan2(face - (crawl ? Z_CRAWL_EYE : FACE), d);
                    want[0] = Math.max(-Z_MAX_YAW, Math.min(Z_MAX_YAW, a));
                    want[1] = Math.max(-MAX_PITCH_DOWN, Math.min(crawl ? Z_CRAWL_UP : MAX_PITCH_UP, PITCH_SHARE * pitch));
                }
            }
        }
        boolean looks = want[0] != 0f || want[1] != 0f;
        if (s == null) {
            if (!looks) return;                                 // most zombies: nothing to do, nothing kept
            s = new State();
            s.zombie = true;
            states.put(player, s);
        }
        long now = System.nanoTime();
        float dt = s.t == 0 ? 0f : Math.min(0.1f, (now - s.t) / 1e9f);
        s.t = now;
        float tau = looks ? TAU_TO : TAU_BACK;
        s.yaw = approach(s.yaw, want[0], dt, tau);
        s.pitch = approach(s.pitch, want[1], dt, tau);
        if (t != null && !free) {                               // knocked down, hit...: the pose has the head
            s.yaw = 0f;
            s.pitch = 0f;
        }
        turn(player, s);
    }

    private static boolean zombieFree(Object z) throws Exception {
        if ((Boolean) dead.invoke(z) || (Boolean) zFakeDead.invoke(z) || (Boolean) zSitWall.invoke(z)) return false;
        if ((Boolean) climbing.invoke(z) || (Boolean) knockedDown.invoke(z) || (Boolean) hitReaction.invoke(z)) return false;
        return !(Boolean) onFloor.invoke(z) || (Boolean) zCrawling.invoke(z);     // lying knocked over: no
    }

    private static boolean free(Object chr) throws Exception {
        return inVehicle.invoke(chr) == null && !(Boolean) asleep.invoke(chr) && !(Boolean) climbing.invoke(chr)
                && !(Boolean) onFloor.invoke(chr) && !(Boolean) dead.invoke(chr) && !(Boolean) headLookAround.invoke(chr);
    }

    /** a ranged weapon in the main hand (aiming with anything else is a melee stance) */
    private static boolean gun(Object chr) throws Exception {
        Object item = primaryItem.invoke(chr);
        return item != null && weaponClass.isInstance(item) && (Boolean) isRanged.invoke(item);
    }

    private static float approach(float cur, float want, float dt, float tau) {
        if (dt <= 0f) return cur;
        float step = (want - cur) * (1f - (float) Math.exp(-dt / tau));
        float lim = MAX_SPEED * dt;
        return cur + Math.max(-lim, Math.min(lim, step));
    }

    // ---------------- what to look at ----------------

    /** standing still long enough with nothing else to look at: {yaw, pitch} towards the camera; true while it does.
     *  'reset' (busy, aiming, a zombie in sight) restarts the clock */
    private static boolean idleLook(Object chr, State s, long now, float[] out, boolean reset) throws Exception {
        float px = ((Number) chrX.invoke(chr)).floatValue(), py = ((Number) chrY.invoke(chr)).floatValue();
        Object fw = chrForward.invoke(chr);
        float fx = ((Number) v2X.invoke(fw)).floatValue(), fy = ((Number) v2Y.invoke(fw)).floatValue();
        float fl = (float) Math.sqrt(fx * fx + fy * fy);
        if (fl < 1e-4f) return false;
        fx /= fl;
        fy /= fl;
        boolean moved = !s.hasRef || Math.abs(px - s.rx) > STILL_MOVE || Math.abs(py - s.ry) > STILL_MOVE
                || fx * s.rfx + fy * s.rfy < STILL_TURN;
        if (reset || moved || idleSec <= 0f || busy(chr)) {
            s.rx = px;
            s.ry = py;
            s.rfx = fx;
            s.rfy = fy;
            s.hasRef = true;
            s.stillSince = now;
            return false;
        }
        if (now - s.stillSince < (long) (idleSec * 1e9f)) return false;
        float a = (float) Math.atan2(YAW_SIGN * (CAM_X * fy - CAM_Y * fx), CAM_X * fx + CAM_Y * fy);
        if (Math.abs(a) > BEHIND) {
            s.why = "standing still, facing away from the camera";
            return false;
        }
        out[0] = Math.max(-MAX_YAW, Math.min(MAX_YAW, a));
        out[1] = Math.min(MAX_PITCH_UP, PITCH_SHARE * CAM_PITCH);
        s.why = String.format(Locale.ROOT, "standing still %.0f s: looking at the camera", (now - s.stillSince) / 1e9f);
        return true;
    }

    /** sitting, or doing something (a timed action, an action animation): not "standing still" */
    private static boolean busy(Object chr) throws Exception {
        for (Method m : new Method[]{ sitGround, sitFurniture, timedActions, performing }) {
            if (m != null && (Boolean) m.invoke(chr)) return true;
        }
        return false;
    }

    /** the nearest zombie this player sees within 'cone' of the facing (in front or to the side; aiming: near the
     *  aim line): {yaw, pitch} towards it; true when there is one (or one to glance at) */
    @SuppressWarnings("unchecked")
    private static boolean target(Object chr, float[] out, State s, float cone) throws Exception {
        boolean aimed = cone < BEHIND;
        Object cell = getCell.invoke(chr);
        List<Object> zs = cell == null ? null : (List<Object>) zombieList.invoke(cell);
        if (zs == null || zs.isEmpty()) {
            s.why = aimed ? "aiming" : "nothing";
            return false;
        }
        int pn = (Integer) playerNum.invoke(chr);
        float px = ((Number) chrX.invoke(chr)).floatValue(), py = ((Number) chrY.invoke(chr)).floatValue();
        float pz = ((Number) chrZ.invoke(chr)).floatValue();
        Object fw = chrForward.invoke(chr);
        float fx = ((Number) v2X.invoke(fw)).floatValue(), fy = ((Number) v2Y.invoke(fw)).floatValue();
        float fl = (float) Math.sqrt(fx * fx + fy * fy);
        if (fl < 1e-4f) return false;
        fx /= fl;
        fy /= fl;
        // the nearest zombie within the cone (looked at), and while aiming / in a stance also the nearest close one
        // outside it (glanced at: the head turns part of the way, the eyes stay near the aim)
        float best = range, yaw = 0f, pitch = 0f, bestOut = aimed ? GLANCE_RANGE : 0f, yawOut = 0f, pitchOut = 0f;
        boolean crawl = false, crawlOut = false;
        float eye = (Boolean) sneaking.invoke(chr) ? EYE_LOW : EYE;
        Object[] arr;
        synchronized (zs) {
            arr = zs.toArray();
        }
        for (Object z : arr) {
            if (z == null || (Boolean) dead.invoke(z)) continue;
            float dx = ((Number) chrX.invoke(z)).floatValue() - px, dy = ((Number) chrY.invoke(z)).floatValue() - py;
            float lim = Math.max(best, bestOut);
            if (Math.abs(dx) > lim || Math.abs(dy) > lim) continue;
            if (Math.abs(((Number) chrZ.invoke(z)).floatValue() - pz) > 0.5f) continue;   // another floor
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < 0.3f) continue;
            float a = (float) Math.atan2(YAW_SIGN * (dx * fy - dy * fx), dx * fx + dy * fy);
            boolean in = Math.abs(a) <= cone;
            if (in ? d >= best : (Math.abs(a) > BEHIND || d >= bestOut)) continue;
            if (((Number) targetAlpha.invoke(z, pn)).floatValue() < 0.5f) continue;     // not seen by this player
            if ((Boolean) zFakeDead.invoke(z) || (Boolean) zSitWall.invoke(z)) continue;  // looks like a corpse
            boolean c = (Boolean) zCrawling.invoke(z) || (Boolean) onFloor.invoke(z);
            float pt = (float) Math.atan2((c ? CRAWLER : FACE) - eye, d);
            if (in) {
                best = d;
                crawl = c;
                yaw = a;
                pitch = pt;
            } else {
                bestOut = d;
                crawlOut = c;
                yawOut = a;
                pitchOut = pt;
            }
        }
        if (best < range) {
            out[0] = Math.max(-MAX_YAW, Math.min(MAX_YAW, yaw));
            out[1] = Math.max(-MAX_PITCH_DOWN, Math.min(MAX_PITCH_UP, PITCH_SHARE * pitch));
            s.why = String.format(Locale.ROOT, "%s%s %.1f tiles, %.0f deg %s", aimed ? (cone == AIM_CONE ? "aiming at " : "stance, ") : "",
                    crawl ? "crawler" : "zombie", best, Math.toDegrees(Math.abs(yaw)), yaw >= 0 ? "left" : "right");
            return true;
        } else if (aimed && bestOut < GLANCE_RANGE) {
            out[0] = Math.max(-GLANCE_MAX, Math.min(GLANCE_MAX, GLANCE * yawOut));
            out[1] = Math.max(-MAX_PITCH_DOWN, Math.min(MAX_PITCH_UP, GLANCE * PITCH_SHARE * pitchOut));
            s.why = String.format(Locale.ROOT, "%s, glance at a %s %.1f tiles, %.0f deg %s", cone == AIM_CONE ? "aiming" : "stance",
                    crawlOut ? "crawler" : "zombie", bestOut, Math.toDegrees(Math.abs(yawOut)), yawOut >= 0 ? "left" : "right");
            return true;
        }
        s.why = aimed ? "aiming" : "no zombie in sight";
        return false;
    }

    // ---------------- turning the bones ----------------

    private static void turn(Object player, State s) throws Exception {
        if (Math.abs(s.yaw) < 1e-3f && Math.abs(s.pitch) < 1e-3f) return;
        Object data = apSkinning.invoke(player);
        if (data == null) return;
        Skel k = skelFor(data);
        if (!k.ok || (Integer) apCount.invoke(player) != k.n) return;
        float[] m = M.get();
        float[] neck = pos(player, k.neck, m), head = pos(player, k.head, m);
        float[] la = pos(player, k.lArm, m), ra = pos(player, k.rArm, m);
        float[] up = norm(sub(head, neck)), left = sub(la, ra);
        if (up == null) return;
        // lying flat (a crawler): the neck is level, a turn about it would roll the head - turn about the vertical
        // (model space is y up), the face lifting about the line across the shoulders
        if (Math.abs(up[1]) < 0.5f) up = new float[]{ 0f, 1f, 0f };
        left = norm(sub(left, mul(up, dot(left, up))));          // across the shoulders, square to the turn axis
        if (left == null) return;
        // + yaw turns the face to the character's left: a turn about 'up' by +a takes forward (up x left) to -left
        float[] qn = mulQ(rot(up, -NECK_SHARE * s.yaw), rot(left, NECK_SHARE * s.pitch));
        rotate(player, k.neckTree, qn, neck, m);
        float[] head2 = add(apply(qn, sub(head, neck)), neck);
        float[] up2 = apply(qn, up), left2 = apply(qn, left);
        float[] qh = mulQ(rot(up2, -(1 - NECK_SHARE) * s.yaw), rot(left2, (1 - NECK_SHARE) * s.pitch));
        rotate(player, k.headTree, qh, head2, m);
        if (s.zombie) {
            zTurned++;
            long now = System.nanoTime();
            if (now >= zSummaryAt) {
                zSummaryAt = now + 30_000_000_000L;
                RBSHook.log(String.format(Locale.ROOT, "head look: %d zombie head pose(s) turned to their player so far", zTurned));
            }
            return;
        }
        turned++;
        summary(s);
    }

    /** M' = T(p) Q T(-p) M for each bone of the tree, in model space */
    private static void rotate(Object player, int[] tree, float[] q, float[] p, float[] m) throws Exception {
        for (int i : tree) {
            Object mt = apModelAt.invoke(player, i);
            if (mt == null) continue;
            read(mt, m);
            boolean cv = colVec(m);
            float[] r = new float[9], t = new float[3];
            for (int a = 0; a < 3; a++) {
                for (int b = 0; b < 3; b++) r[a * 3 + b] = cv ? m[b * 4 + a] : m[a * 4 + b];
                t[a] = cv ? m[12 + a] : m[a * 4 + 3];
            }
            float[] r2 = mat3(q, r), t2 = add(apply(q, sub(t, p)), p);
            for (int a = 0; a < 3; a++) {
                for (int b = 0; b < 3; b++) {
                    if (cv) m[b * 4 + a] = r2[a * 3 + b];
                    else m[a * 4 + b] = r2[a * 3 + b];
                }
                if (cv) m[12 + a] = t2[a];
                else m[a * 4 + 3] = t2[a];
            }
            write(mt, m);
        }
    }

    private static float[] pos(Object player, int i, float[] m) throws Exception {
        read(apModelAt.invoke(player, i), m);
        return colVec(m) ? new float[]{ m[12], m[13], m[14] } : new float[]{ m[3], m[7], m[11] };
    }

    // ---------------- skeleton ----------------

    private static Skel skelFor(Object data) throws Exception {
        Skel k = skels.get(data);
        if (k != null) return k;
        k = new Skel();
        int n = (Integer) sdNumBones.invoke(data);
        k.n = n;
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            Object b = sdBoneAt.invoke(data, i);
            Object nm = b == null ? null : fBoneName.get(b);
            String x = nm == null ? "" : nm.toString().toLowerCase(Locale.ROOT);
            parent[i] = (Integer) sdParent.invoke(data, i);
            if (x.endsWith("_neck")) k.neck = i;
            else if (x.endsWith("_head")) k.head = i;
            else if (x.endsWith("_l_upperarm")) k.lArm = i;
            else if (x.endsWith("_r_upperarm")) k.rArm = i;
        }
        if (k.neck >= 0 && k.head >= 0 && k.lArm >= 0 && k.rArm >= 0) {
            // The clavicles hang on the neck (3ds Max biped), so the neck's whole tree swung both arms with the head,
            // while a held weapon - drawn at Bip01_Prop1/2, children of Bip01, not of the hands - stayed where the
            // animation put it: the axe floated out of the hand (user, 2026-11-10). The neck turn leaves the arms.
            k.neckTree = without(tree(parent, k.neck), tree(parent, childOnPath(parent, k.lArm, k.neck)),
                    tree(parent, childOnPath(parent, k.rArm, k.neck)));
            k.headTree = tree(parent, k.head);
            k.ok = k.headTree.length > 0 && k.neckTree.length > k.headTree.length;
        }
        if (diag++ < 4) RBSHook.log("head look: skeleton with " + n + " bones" + (k.ok
                ? ", neck turns " + k.neckTree.length + " bone(s) (not the arms), head " + k.headTree.length : " - skipped (no neck / head / arms)"));
        skels.put(data, k);
        return k;
    }

    /** the bone on the way up from 'bone' whose parent is 'top' (an upper arm's clavicle under the neck), else 'bone' */
    private static int childOnPath(int[] parent, int bone, int top) {
        int j = bone;
        for (int guard = 0; j >= 0 && guard < 64; guard++) {
            if (parent[j] == top) return j;
            j = parent[j];
        }
        return bone;
    }

    /** 'all' without the bones in 'drop' */
    private static int[] without(int[] all, int[]... drop) {
        List<Integer> out = new ArrayList<>();
        outer:
        for (int i : all) {
            for (int[] d : drop) for (int x : d) if (x == i) continue outer;
            out.add(i);
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int[] tree(int[] parent, int root) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < parent.length; i++) {
            int j = i;
            for (int guard = 0; j >= 0 && guard < 64; guard++) {
                if (j == root) {
                    out.add(i);
                    break;
                }
                j = parent[j];
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    // ---------------- small vector maths (3x3 row-major, acting on column vectors) ----------------

    private static float[] rot(float[] k, float a) {
        float c = (float) Math.cos(a), s = (float) Math.sin(a), v = 1 - c;
        float x = k[0], y = k[1], z = k[2];
        return new float[]{
                c + x * x * v, x * y * v - z * s, x * z * v + y * s,
                y * x * v + z * s, c + y * y * v, y * z * v - x * s,
                z * x * v - y * s, z * y * v + x * s, c + z * z * v };
    }

    private static float[] mulQ(float[] a, float[] b) {
        return mat3(a, b);
    }

    private static float[] mat3(float[] a, float[] b) {
        float[] o = new float[9];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                o[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
        return o;
    }

    private static float[] apply(float[] q, float[] v) {
        return new float[]{ q[0] * v[0] + q[1] * v[1] + q[2] * v[2], q[3] * v[0] + q[4] * v[1] + q[5] * v[2],
                q[6] * v[0] + q[7] * v[1] + q[8] * v[2] };
    }

    private static float[] sub(float[] a, float[] b) {
        return new float[]{ a[0] - b[0], a[1] - b[1], a[2] - b[2] };
    }

    private static float[] add(float[] a, float[] b) {
        return new float[]{ a[0] + b[0], a[1] + b[1], a[2] + b[2] };
    }

    private static float[] mul(float[] a, float f) {
        return new float[]{ a[0] * f, a[1] * f, a[2] * f };
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static float[] norm(float[] v) {
        float l = (float) Math.sqrt(dot(v, v));
        return l < 1e-6f ? null : mul(v, 1f / l);
    }

    // ---------------- matrices ----------------

    private static boolean colVec(float[] m) {
        return Math.abs(m[3]) + Math.abs(m[7]) + Math.abs(m[11]) <= Math.abs(m[12]) + Math.abs(m[13]) + Math.abs(m[14]);
    }

    private static void read(Object matrix, float[] out) throws Exception {
        FloatBuffer buf = BUF.get();
        buf.clear();
        mStore.invoke(matrix, buf);
        buf.rewind();
        buf.get(out, 0, 16);
    }

    private static void write(Object matrix, float[] m) throws Exception {
        FloatBuffer buf = BUF.get();
        buf.clear();
        buf.put(m, 0, 16);
        buf.rewind();
        mLoad.invoke(matrix, buf);
    }

    // ---------------- setup ----------------

    private static void summary(State s) {
        long now = System.nanoTime();
        if (now < summaryAt) return;
        summaryAt = now + 30_000_000_000L;
        RBSHook.log(String.format(Locale.ROOT, "head look: %d turned pose(s) so far; now yaw %.0f deg, pitch %.0f deg (%s)",
                turned, Math.toDegrees(s.yaw), Math.toDegrees(s.pitch), s.why));
    }

    private static synchronized void init() {
        if (ready || failed) return;
        try {
            if (!RBSHook.ensureInit()) throw new IllegalStateException("RBSHook not ready");
            ClassLoader cl = HeadLook.class.getClassLoader();
            Class<?> ap = Class.forName("zombie.core.skinnedmodel.animation.AnimationPlayer", false, cl);
            Class<?> sd = Class.forName("zombie.core.skinnedmodel.model.SkinningData", false, cl);
            Class<?> sb = Class.forName("zombie.core.skinnedmodel.model.SkinningBone", false, cl);
            Class<?> m4 = Class.forName("org.lwjgl.util.vector.Matrix4f", false, cl);
            Class<?> igc = Class.forName("zombie.characters.IsoGameCharacter", false, cl);
            playerClass = Class.forName("zombie.characters.IsoPlayer", false, cl);
            zombieClass = Class.forName("zombie.characters.IsoZombie", false, cl);
            apCharacter = ap.getMethod("getIsoGameCharacter");
            apSkinning = ap.getMethod("getSkinningData");
            apCount = ap.getMethod("getModelTransformsCount");
            apModelAt = ap.getMethod("getModelTransformAt", int.class);
            sdNumBones = sd.getMethod("numBones");
            sdBoneAt = sd.getMethod("getBoneAt", int.class);
            sdParent = sd.getMethod("getParentBoneIdx", int.class);
            fBoneName = sb.getField("name");
            mStore = m4.getMethod("store", FloatBuffer.class);
            mLoad = m4.getMethod("load", FloatBuffer.class);
            isLocal = playerClass.getMethod("isLocalPlayer");
            playerNum = playerClass.getMethod("getPlayerNum");
            aiming = playerClass.getMethod("isAiming");
            chrX = igc.getMethod("getX");
            chrY = igc.getMethod("getY");
            chrZ = igc.getMethod("getZ");
            chrForward = igc.getMethod("getForwardDirection");
            v2X = chrForward.getReturnType().getMethod("getX");
            v2Y = chrForward.getReturnType().getMethod("getY");
            getCell = igc.getMethod("getCell");
            zombieList = getCell.getReturnType().getMethod("getZombieList");
            targetAlpha = igc.getMethod("getTargetAlpha", int.class);
            inVehicle = igc.getMethod("getVehicle");
            asleep = igc.getMethod("isAsleep");
            climbing = igc.getMethod("isClimbing");
            onFloor = igc.getMethod("isOnFloor");
            dead = igc.getMethod("isDead");
            headLookAround = igc.getMethod("isHeadLookAround");
            sneaking = igc.getMethod("isSneaking");
            zCrawling = zombieClass.getMethod("isCrawling");
            zFakeDead = zombieClass.getMethod("isFakeDead");
            zSitWall = zombieClass.getMethod("isSitAgainstWall");
            primaryItem = igc.getMethod("getPrimaryHandItem");
            zTarget = zombieClass.getMethod("getTarget");
            knockedDown = igc.getMethod("isKnockedDown");
            hitReaction = igc.getMethod("hasHitReaction");
            weaponClass = Class.forName("zombie.inventory.types.HandWeapon", false, igc.getClassLoader());
            isRanged = weaponClass.getMethod("isRanged");
            sitGround = optional(playerClass, "isSitOnGround");
            sitFurniture = optional(playerClass, "isSittingOnFurniture");
            timedActions = optional(playerClass, "hasTimedActions");
            performing = optional(playerClass, "isPerformingAnAction");
            ready = true;
            RBSHook.log(String.format(Locale.ROOT, "head look ready - the player looks at the nearest zombie in sight within %.0f tiles (down at crawlers; aiming a gun: in the aim line; melee stance: in front)%s",
                    range, idleSec > 0f ? String.format(Locale.ROOT, "; standing still %.0f s: at the camera", idleSec) : ""));
        } catch (Throwable t) {
            failed = true;
            RBSHook.warn("head look init failed, the head is left alone: " + t);
        }
    }

    /** a public no-argument boolean method, or null when this game version has none */
    private static Method optional(Class<?> c, String name) {
        try {
            Method m = c.getMethod(name);
            return m.getReturnType() == boolean.class ? m : null;
        } catch (NoSuchMethodException e) {
            RBSHook.log("head look: " + c.getSimpleName() + "." + name + "() not found - not used for 'standing still'");
            return null;
        }
    }

    private static void loadConfig() {
        long now = System.nanoTime();
        if (now < cfgNextCheck) return;
        cfgNextCheck = now + 5_000_000_000L;
        try {
            Path p = Paths.get(System.getProperty("user.home"), "Zomboid", "Lua", CONFIG);
            if (!Files.exists(p)) return;
            long mt = Files.getLastModifiedTime(p).toMillis();
            if (mt == cfgMtime) return;
            cfgMtime = mt;
            boolean en = enabled, zo = zombiesOn;
            float rg = range, id = 0f;                          // no idle line (an older mod): off
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq < 0 || line.trim().startsWith("#")) continue;
                String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                try {
                    switch (k) {
                        case "enabled": en = !(v.equals("0") || v.equalsIgnoreCase("false")); break;
                        case "range": rg = (float) Math.max(1, Math.min(30, Double.parseDouble(v))); break;
                        case "zombies": zo = !(v.equals("0") || v.equalsIgnoreCase("false")); break;
                        case "idle": id = (float) Math.max(0, Math.min(3600, Double.parseDouble(v))); break;
                        default: break;
                    }
                } catch (NumberFormatException ignored) {}
            }
            enabled = en;
            zombiesOn = zo;
            range = rg;
            idleSec = id;
            RBSHook.log(String.format(Locale.ROOT, "head look config: enabled=%s range=%.0f tiles, zombies=%s, standing still: %s",
                    en, rg, zo, id > 0f ? String.format(Locale.ROOT, "at the camera after %.0f s", id) : "off"));
        } catch (Exception e) {
            RBSHook.warn("head look config: " + e);
        }
    }
}
