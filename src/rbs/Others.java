package rbs;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Other players in multiplayer (part of "Real Body Shape - Zombie Bodies").
 * Every machine reshapes only its own player, by rewriting the shared body model the game draws for everyone of that
 * sex: without this, every other player of the same sex wore our own shape, and those of the other sex the vanilla
 * one. The mod's Lua sends each player's weight through the server and writes it into the other players' characters
 * on this machine (ModData RBS_Weight in kg, RBS_BodyVariant 0 thin .. 3 obese). Here those players get the body
 * variant of that weight (the zombies' Thin / Normal / Fat / Obese bodies), their clothes the untouched copies fitted
 * to it ({@link Clean}, {@link Fit}), and their belly bounces by their own weight ({@link Jiggle}).
 * A player whose weight has not arrived yet gets the Normal body, never ours. Only on a multiplayer client, and only
 * for real players of the server (an online id): previews, mannequins and the local players are never touched.
 */
final class Others {
    private static volatile boolean failed, ready;
    private static Class<?> playerClass;
    private static Method isLocal, onlineId, isAnimal, isFemale, modData, rawget;
    private static Field clientFlag;
    /** character -> {variant, weight kg (-1 unknown), expiry ns}; the last value seen is kept if the tag goes away */
    private static final Map<Object, Object[]> cache = Collections.synchronizedMap(new WeakHashMap<>());
    /** character -> false when another mod chose its body (it keeps that body, no clothes fit) */
    private static final Map<Object, Boolean> kept = Collections.synchronizedMap(new WeakHashMap<>());
    private static int diag;

    private Others() {}

    /** another player of the server on this client (not one of ours, not an animal, not a preview) */
    static boolean isOther(Object chr) {
        if (chr == null || failed) return false;
        if (!ready) init();
        if (failed || !playerClass.isInstance(chr)) return false;
        try {
            if (!clientFlag.getBoolean(null)) return false;            // single player / the server
            if ((Boolean) isLocal.invoke(chr)) return false;
            if (((Number) onlineId.invoke(chr)).intValue() < 0) return false;
            return !(Boolean) isAnimal.invoke(chr);
        } catch (Throwable e) {
            return false;
        }
    }

    static boolean isFemale(Object chr) throws Exception {
        return (Boolean) isFemale.invoke(chr);
    }

    /** body variant of another player (index of RBSHook.NAMES): what the Lua tagged, Normal until then */
    static int variant(Object chr) {
        Object[] e = entry(chr);
        return (Integer) e[0];
    }

    /** weight of another player in kg as their machine sent it, -1 while unknown */
    static float weight(Object chr) {
        Object[] e = entry(chr);
        return (Float) e[1];
    }

    /** this other player draws a variant body (the hook gave it one), so its clothes and belly follow that body */
    static boolean wearsVariant(Object chr) {
        if (!isOther(chr) || !RBSHook.othersEnabled()) return false;
        return !Boolean.FALSE.equals(kept.get(chr));
    }

    static void keep(Object chr, boolean ours) {
        if (chr != null) kept.put(chr, ours);
    }

    private static Object[] entry(Object chr) {
        long now = System.nanoTime();
        Object[] e = cache.get(chr);
        if (e != null && now < (Long) e[2]) return e;
        int v = e == null ? 1 : (Integer) e[0];
        float w = e == null ? -1f : (Float) e[1];
        try {
            Object t = modData.invoke(chr);
            if (t != null) {
                Object tv = rawget.invoke(t, "RBS_BodyVariant");
                Object tw = rawget.invoke(t, "RBS_Weight");
                if (tv instanceof Number) {
                    int n = ((Number) tv).intValue();
                    if (n >= 0 && n < RBSHook.NAMES.length) v = n;
                }
                if (tw instanceof Number) w = ((Number) tw).floatValue();
            }
        } catch (Throwable ex) {
            // keep what we had
        }
        Object[] r = { v, w, now + 1_000_000_000L };
        cache.put(chr, r);
        if (e == null || (Integer) e[0] != v) note(chr, v, w);
        return r;
    }

    private static void note(Object chr, int v, float w) {
        if (diag >= 12) return;
        diag++;
        String who = "?";
        try {
            who = String.valueOf(onlineId.invoke(chr));
        } catch (Throwable ignored) {}
        RBSHook.log("other player " + who + ": " + RBSHook.NAMES[v] + " body"
                + (w > 0 ? String.format(java.util.Locale.ROOT, " (%.1f kg)", w) : " (weight not received yet)"));
    }

    private static synchronized void init() {
        if (ready || failed) return;
        try {
            ClassLoader cl = Others.class.getClassLoader();
            playerClass = Class.forName("zombie.characters.IsoPlayer", false, cl);
            Class<?> igc = Class.forName("zombie.characters.IsoGameCharacter", false, cl);
            Class<?> obj = Class.forName("zombie.iso.IsoObject", false, cl);
            Class<?> kt = Class.forName("se.krka.kahlua.vm.KahluaTable", false, cl);
            Class<?> client = Class.forName("zombie.network.GameClient", false, cl);
            isLocal = playerClass.getMethod("isLocalPlayer");
            onlineId = playerClass.getMethod("getOnlineID");
            isAnimal = igc.getMethod("isAnimal");
            isFemale = igc.getMethod("isFemale");
            modData = obj.getMethod("getModData");
            rawget = kt.getMethod("rawget", Object.class);
            clientFlag = client.getField("client");
            ready = true;
        } catch (Throwable e) {
            failed = true;
            RBSHook.warn("other players: init failed, they keep the shared body: " + e);
        }
    }
}
