package rbs;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The weather for our shaders: palette slot 56 of every human mesh whose palette leaves it free (Jiggle.palette), so
 * a shader can read the wind where the character stands (user, 2026-11-14: "เปิดช่องข้อมูลให้รับทิศทางและความแรงลม
 * เพื่อเอามาใช้ในอนาคต"). Nothing uses it yet: sbj_HairSway reads it with WEATHER_WIND = 0.
 *   row 0 = the game's wind direction in the character's model space (xyz, unit, horizontal; +z is behind the
 *           character, as the running air of Hair), 56 (w: the slot is ours - a bone matrix never has exactly 56)
 *   row 1 = intensity 0..1 (ClimateManager.getWindIntensity), power 0..1 (getWindPower = km/h / 120),
 *           1 outdoors / 0 in a room (the game's own wind force is 0 in a room), 0
 *   row 2 = the wind angle in the world (radians, ClimateManager.getWindAngleRadians: the same convention as a
 *           character's facing, Vector2.getDirection), the character's facing angle (radians), the wind in km/h, 0
 *   row 3 = 0 (free for more weather later: rain, snow, temperature...)
 * The direction is the game's wind angle as it is; whether the air moves towards it or comes from it is NOT checked
 * yet - look at rain or snow drifting in game before bending anything with it.
 */
final class Env {
    static final int SLOT = 56;
    static final float MAGIC = 56f;
    /** the weather is read this often (ms), a character's rows and indoors at most this often */
    static final long CLIMATE_MS = 250, ROWS_MS = 50, ROOM_MS = 1000;

    private static volatile boolean failed, ready;
    private static Object climate;
    private static Method cmAngle, cmIntensity, cmPower, cmKph, chrSquare, sqInRoom;
    private static long climateAt;
    private static float angle, intensity, power, kph;
    /** character -> {rows (16), time of rows, time of the room check, in a room 1/0} */
    private static final Map<Object, Object[]> perChar = Collections.synchronizedMap(new WeakHashMap<>());
    private static int warns;

    private Env() {}

    /** the slot's matrix for this mesh's palette (identity when it cannot be worked out) */
    static Object slot(Object player, Jiggle.Pad pad) {
        if (failed) return Jiggle.identity;
        try {
            if (!ready) init();
            if (failed) return Jiggle.identity;
            Object chr = Jiggle.apCharacter.invoke(player);
            if (chr == null) return Jiggle.identity;
            float[] rows = rowsFor(player, chr);
            if (rows == null) return Jiggle.identity;
            if (pad.slot4 == null) pad.slot4 = Jiggle.matrixCtor.newInstance();
            Jiggle.write(pad.slot4, rows);
            return pad.slot4;
        } catch (Throwable t) {
            if (warns++ < 5) RBSHook.warn("weather slot failed: " + t);
            return Jiggle.identity;
        }
    }

    private static float[] rowsFor(Object player, Object chr) throws Exception {
        long now = System.nanoTime() / 1_000_000L;
        Object[] c = perChar.get(chr);
        if (c != null && now - (Long) c[1] < ROWS_MS) return (float[]) c[0];
        if (c == null) {
            c = new Object[]{ new float[16], 0L, -1_000_000L, 0f };
            perChar.put(chr, c);
        }
        readClimate(now);
        if (now - (Long) c[2] >= ROOM_MS) {
            Object sq = chrSquare.invoke(chr);
            c[3] = sq != null && (Boolean) sqInRoom.invoke(sq) ? 1f : 0f;
            c[2] = now;
        }
        // the character's frame in its model space, exactly as Hair builds it (left = forward x up)
        Object data = Jiggle.apSkinning.invoke(player);
        float[] fr = data == null ? null : Jiggle.frameOf(data);
        Object fwd = Jiggle.chrForward.invoke(chr);
        if (fr == null || fwd == null) return null;
        float fx = ((Number) Jiggle.v2X.invoke(fwd)).floatValue(), fy = ((Number) Jiggle.v2Y.invoke(fwd)).floatValue();
        float fl = (float) Math.sqrt(fx * fx + fy * fy);
        if (fl < 1e-4f) return null;
        fx /= fl;
        fy /= fl;
        float[] fw = { fr[4], fr[5], fr[6] }, up = { fr[7], fr[8], fr[9] };
        float[] left = { fw[1] * up[2] - fw[2] * up[1], fw[2] * up[0] - fw[0] * up[2], fw[0] * up[1] - fw[1] * up[0] };
        // the wind in the character's frame (world left of the facing is (fy, -fx), as Hair / HeadLook)
        float wx = (float) Math.cos(angle), wy = (float) Math.sin(angle);
        float wF = wx * fx + wy * fy, wL = wx * fy - wy * fx;
        float[] r = (float[]) c[0];
        for (int i = 0; i < 3; i++) r[i] = fw[i] * wF + left[i] * wL;
        float n = (float) Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]);
        if (n > 1e-6f) for (int i = 0; i < 3; i++) r[i] /= n;
        r[3] = MAGIC;
        r[4] = intensity;
        r[5] = power;
        r[6] = (Float) c[3] > 0.5f ? 0f : 1f;
        r[7] = 0f;
        r[8] = angle;
        r[9] = (float) Math.atan2(fy, fx);
        r[10] = kph;
        r[11] = 0f;
        r[12] = r[13] = r[14] = r[15] = 0f;
        c[1] = now;
        return r;
    }

    private static void readClimate(long now) throws Exception {
        if (now - climateAt < CLIMATE_MS) return;
        climateAt = now;
        angle = ((Number) cmAngle.invoke(climate)).floatValue();
        intensity = clamp01(((Number) cmIntensity.invoke(climate)).floatValue());
        power = clamp01(((Number) cmPower.invoke(climate)).floatValue());
        kph = ((Number) cmKph.invoke(climate)).floatValue();
    }

    private static float clamp01(float v) {
        return Float.isNaN(v) ? 0f : Math.max(0f, Math.min(1f, v));
    }

    private static synchronized void init() {
        if (ready || failed) return;
        try {
            ClassLoader cl = Env.class.getClassLoader();
            Class<?> cm = Class.forName("zombie.iso.weather.ClimateManager", false, cl);
            climate = cm.getMethod("getInstance").invoke(null);
            if (climate == null) return;                              // not yet (the main menu): try again next time
            cmAngle = cm.getMethod("getWindAngleRadians");
            cmIntensity = cm.getMethod("getWindIntensity");
            cmPower = cm.getMethod("getWindPower");
            cmKph = cm.getMethod("getWindspeedKph");
            Class<?> igc = Class.forName("zombie.characters.IsoGameCharacter", false, cl);
            chrSquare = igc.getMethod("getCurrentSquare");
            sqInRoom = Class.forName("zombie.iso.IsoGridSquare", false, cl).getMethod("isInARoom");
            ready = true;
            RBSHook.log("weather: the wind is in palette slot 56 for our shaders (not used yet)");
        } catch (Throwable t) {
            failed = true;
            RBSHook.warn("weather slot off: " + t);
        }
    }
}
