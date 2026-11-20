package rbs;

/**
 * Room in clothes (allowance.py, 2026-11-20): per sex, region (belly, waist, hips, butt, thighs, calves, chest, back, arms) and bone (FitTable.BONES),
 * the change of the bind-space map per 1 cm of room (3 rows: three numbers and a translation each), or null.
 * Sized on the game's bodies so the skin's surface there moves out by 1 cm. Generated, do not edit.
 */
final class Allowance {
    static final String[] REGIONS = { "belly", "waist", "hips", "butt", "thighs", "calves", "chest", "back", "arms" };

    static final float[][][][] PER_CM = {
        {   // Female
            {   // belly
                { 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.047865f, -0.004629f },   // Bip01_Pelvis
                { 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.053185f, -0.004280f },   // Bip01_Spine
                null,   // Bip01_Spine1
                null,   // Bip01_Neck
                null,   // Bip01_L_Clavicle
                null,   // Bip01_R_Clavicle
                null,   // Bip01_L_UpperArm
                null,   // Bip01_R_UpperArm
                null,   // Bip01_L_Forearm
                null,   // Bip01_R_Forearm
                null,   // Bip01_L_Thigh
                null,   // Bip01_R_Thigh
                null,   // Bip01_L_Calf
                null,   // Bip01_R_Calf
            },
            {   // waist
                null,   // Bip01_Pelvis
                { 0.067736f, 0.000000f, 0.000000f, -0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.106370f, -0.003029f },   // Bip01_Spine
                null,   // Bip01_Spine1
                null,   // Bip01_Neck
                null,   // Bip01_L_Clavicle
                null,   // Bip01_R_Clavicle
                null,   // Bip01_L_UpperArm
                null,   // Bip01_R_UpperArm
                null,   // Bip01_L_Forearm
                null,   // Bip01_R_Forearm
                null,   // Bip01_L_Thigh
                null,   // Bip01_R_Thigh
                null,   // Bip01_L_Calf
                null,   // Bip01_R_Calf
            },
            {   // hips
                { 0.060287f, 0.000000f, 0.000000f, -0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f },   // Bip01_Pelvis
                null,   // Bip01_Spine
                null,   // Bip01_Spine1
                null,   // Bip01_Neck
                null,   // Bip01_L_Clavicle
                null,   // Bip01_R_Clavicle
                null,   // Bip01_L_UpperArm
                null,   // Bip01_R_UpperArm
                null,   // Bip01_L_Forearm
                null,   // Bip01_R_Forearm
                null,   // Bip01_L_Thigh
                null,   // Bip01_R_Thigh
                null,   // Bip01_L_Calf
                null,   // Bip01_R_Calf
            },
            {   // butt
                { 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.047865f, 0.000902f },   // Bip01_Pelvis
                null,   // Bip01_Spine
                null,   // Bip01_Spine1
                null,   // Bip01_Neck
                null,   // Bip01_L_Clavicle
                null,   // Bip01_R_Clavicle
                null,   // Bip01_L_UpperArm
                null,   // Bip01_R_UpperArm
                null,   // Bip01_L_Forearm
                null,   // Bip01_R_Forearm
                null,   // Bip01_L_Thigh
                null,   // Bip01_R_Thigh
                null,   // Bip01_L_Calf
                null,   // Bip01_R_Calf
            },
            {   // thighs
                null,   // Bip01_Pelvis
                null,   // Bip01_Spine
                null,   // Bip01_Spine1
                null,   // Bip01_Neck
                null,   // Bip01_L_Clavicle
                null,   // Bip01_R_Clavicle
                null,   // Bip01_L_UpperArm
                null,   // Bip01_R_UpperArm
                null,   // Bip01_L_Forearm
                null,   // Bip01_R_Forearm
                { 0.109459f, 0.012742f, -0.000654f, -0.012473f, 0.012742f, 0.001775f, 0.005603f, -0.001669f, -0.000654f, 0.005603f, 0.110659f, -0.004145f },   // Bip01_L_Thigh
                { 0.109459f, -0.012742f, 0.000654f, 0.012473f, -0.012742f, 0.001775f, 0.005603f, -0.001669f, 0.000654f, 0.005603f, 0.110658f, -0.004145f },   // Bip01_R_Thigh
                null,   // Bip01_L_Calf
                null,   // Bip01_R_Calf
            },
            {   // calves
                null,   // Bip01_Pelvis
                null,   // Bip01_Spine
                null,   // Bip01_Spine1
                null,   // Bip01_Neck
                null,   // Bip01_L_Clavicle
                null,   // Bip01_R_Clavicle
                null,   // Bip01_L_UpperArm
                null,   // Bip01_R_UpperArm
                null,   // Bip01_L_Forearm
                null,   // Bip01_R_Forearm
                null,   // Bip01_L_Thigh
                null,   // Bip01_R_Thigh
                { 0.157026f, 0.017986f, -0.002479f, -0.017777f, 0.017986f, 0.005027f, 0.021239f, -0.003359f, -0.002479f, 0.021239f, 0.156198f, -0.009315f },   // Bip01_L_Calf
                { 0.157025f, -0.017986f, 0.002479f, 0.017777f, -0.017986f, 0.005027f, 0.021239f, -0.003359f, 0.002479f, 0.021239f, 0.156197f, -0.009315f },   // Bip01_R_Calf
            },
            {   // chest
                null,   // Bip01_Pelvis
                null,   // Bip01_Spine
                { 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.047809f, -0.003733f },   // Bip01_Spine1
                null,   // Bip01_Neck
                null,   // Bip01_L_Clavicle
                null,   // Bip01_R_Clavicle
                null,   // Bip01_L_UpperArm
                null,   // Bip01_R_UpperArm
                null,   // Bip01_L_Forearm
                null,   // Bip01_R_Forearm
                null,   // Bip01_L_Thigh
                null,   // Bip01_R_Thigh
                null,   // Bip01_L_Calf
                null,   // Bip01_R_Calf
            },
            {   // back
                null,   // Bip01_Pelvis
                null,   // Bip01_Spine
                { 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.047809f, 0.001798f },   // Bip01_Spine1
                null,   // Bip01_Neck
                null,   // Bip01_L_Clavicle
                null,   // Bip01_R_Clavicle
                null,   // Bip01_L_UpperArm
                null,   // Bip01_R_UpperArm
                null,   // Bip01_L_Forearm
                null,   // Bip01_R_Forearm
                null,   // Bip01_L_Thigh
                null,   // Bip01_R_Thigh
                null,   // Bip01_L_Calf
                null,   // Bip01_R_Calf
            },
            {   // arms
                null,   // Bip01_Pelvis
                null,   // Bip01_Spine
                null,   // Bip01_Spine1
                null,   // Bip01_Neck
                null,   // Bip01_L_Clavicle
                null,   // Bip01_R_Clavicle
                { 0.002327f, 0.011309f, -0.017790f, -0.008194f, 0.011309f, 0.192634f, 0.001053f, -0.148907f, -0.017790f, 0.001053f, 0.191647f, -0.006699f },   // Bip01_L_UpperArm
                { 0.002327f, -0.011309f, 0.017789f, 0.008194f, -0.011309f, 0.192637f, 0.001053f, -0.148909f, 0.017789f, 0.001053f, 0.191650f, -0.006699f },   // Bip01_R_UpperArm
                { 0.003895f, 0.016562f, 0.023106f, -0.014708f, 0.016562f, 0.210060f, -0.001844f, -0.163220f, 0.023106f, -0.001844f, 0.208809f, -0.015083f },   // Bip01_L_Forearm
                { 0.003895f, -0.016562f, -0.023106f, 0.014708f, -0.016562f, 0.210053f, -0.001844f, -0.163215f, -0.023106f, -0.001844f, 0.208802f, -0.015082f },   // Bip01_R_Forearm
                null,   // Bip01_L_Thigh
                null,   // Bip01_R_Thigh
                null,   // Bip01_L_Calf
                null,   // Bip01_R_Calf
            },
        },
        {   // Male
            {   // belly
                { 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.041678f, -0.003863f },   // Bip01_Pelvis
                { 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.047384f, -0.003503f },   // Bip01_Spine
                null,   // Bip01_Spine1
                null,   // Bip01_Neck
                null,   // Bip01_L_Clavicle
                null,   // Bip01_R_Clavicle
                null,   // Bip01_L_UpperArm
                null,   // Bip01_R_UpperArm
                null,   // Bip01_L_Forearm
                null,   // Bip01_R_Forearm
                null,   // Bip01_L_Thigh
                null,   // Bip01_R_Thigh
                null,   // Bip01_L_Calf
                null,   // Bip01_R_Calf
            },
            {   // waist
                null,   // Bip01_Pelvis
                { 0.066540f, 0.000000f, 0.000000f, -0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.094767f, -0.001402f },   // Bip01_Spine
                null,   // Bip01_Spine1
                null,   // Bip01_Neck
                null,   // Bip01_L_Clavicle
                null,   // Bip01_R_Clavicle
                null,   // Bip01_L_UpperArm
                null,   // Bip01_R_UpperArm
                null,   // Bip01_L_Forearm
                null,   // Bip01_R_Forearm
                null,   // Bip01_L_Thigh
                null,   // Bip01_R_Thigh
                null,   // Bip01_L_Calf
                null,   // Bip01_R_Calf
            },
            {   // hips
                { 0.060876f, 0.000000f, 0.000000f, -0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f },   // Bip01_Pelvis
                null,   // Bip01_Spine
                null,   // Bip01_Spine1
                null,   // Bip01_Neck
                null,   // Bip01_L_Clavicle
                null,   // Bip01_R_Clavicle
                null,   // Bip01_L_UpperArm
                null,   // Bip01_R_UpperArm
                null,   // Bip01_L_Forearm
                null,   // Bip01_R_Forearm
                null,   // Bip01_L_Thigh
                null,   // Bip01_R_Thigh
                null,   // Bip01_L_Calf
                null,   // Bip01_R_Calf
            },
            {   // butt
                { 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.041678f, 0.001741f },   // Bip01_Pelvis
                null,   // Bip01_Spine
                null,   // Bip01_Spine1
                null,   // Bip01_Neck
                null,   // Bip01_L_Clavicle
                null,   // Bip01_R_Clavicle
                null,   // Bip01_L_UpperArm
                null,   // Bip01_R_UpperArm
                null,   // Bip01_L_Forearm
                null,   // Bip01_R_Forearm
                null,   // Bip01_L_Thigh
                null,   // Bip01_R_Thigh
                null,   // Bip01_L_Calf
                null,   // Bip01_R_Calf
            },
            {   // thighs
                null,   // Bip01_Pelvis
                null,   // Bip01_Spine
                null,   // Bip01_Spine1
                null,   // Bip01_Neck
                null,   // Bip01_L_Clavicle
                null,   // Bip01_R_Clavicle
                null,   // Bip01_L_UpperArm
                null,   // Bip01_R_UpperArm
                null,   // Bip01_L_Forearm
                null,   // Bip01_R_Forearm
                { 0.097264f, 0.011323f, -0.000581f, -0.011084f, 0.011323f, 0.001577f, 0.004979f, -0.001476f, -0.000581f, 0.004979f, 0.098330f, -0.003551f },   // Bip01_L_Thigh
                { 0.097264f, -0.011323f, 0.000581f, 0.011084f, -0.011323f, 0.001577f, 0.004979f, -0.001476f, 0.000581f, 0.004979f, 0.098330f, -0.003551f },   // Bip01_R_Thigh
                null,   // Bip01_L_Calf
                null,   // Bip01_R_Calf
            },
            {   // calves
                null,   // Bip01_Pelvis
                null,   // Bip01_Spine
                null,   // Bip01_Spine1
                null,   // Bip01_Neck
                null,   // Bip01_L_Clavicle
                null,   // Bip01_R_Clavicle
                null,   // Bip01_L_UpperArm
                null,   // Bip01_R_UpperArm
                null,   // Bip01_L_Forearm
                null,   // Bip01_R_Forearm
                null,   // Bip01_L_Thigh
                null,   // Bip01_R_Thigh
                { 0.170662f, 0.019548f, -0.002694f, -0.019324f, 0.019548f, 0.005463f, 0.023084f, -0.003619f, -0.002694f, 0.023084f, 0.169761f, -0.009896f },   // Bip01_L_Calf
                { 0.170661f, -0.019548f, 0.002694f, 0.019324f, -0.019548f, 0.005463f, 0.023084f, -0.003619f, 0.002694f, 0.023084f, 0.169761f, -0.009896f },   // Bip01_R_Calf
            },
            {   // chest
                null,   // Bip01_Pelvis
                null,   // Bip01_Spine
                { 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.039410f, -0.004028f },   // Bip01_Spine1
                null,   // Bip01_Neck
                null,   // Bip01_L_Clavicle
                null,   // Bip01_R_Clavicle
                null,   // Bip01_L_UpperArm
                null,   // Bip01_R_UpperArm
                null,   // Bip01_L_Forearm
                null,   // Bip01_R_Forearm
                null,   // Bip01_L_Thigh
                null,   // Bip01_R_Thigh
                null,   // Bip01_L_Calf
                null,   // Bip01_R_Calf
            },
            {   // back
                null,   // Bip01_Pelvis
                null,   // Bip01_Spine
                { 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.039410f, 0.001576f },   // Bip01_Spine1
                null,   // Bip01_Neck
                null,   // Bip01_L_Clavicle
                null,   // Bip01_R_Clavicle
                null,   // Bip01_L_UpperArm
                null,   // Bip01_R_UpperArm
                null,   // Bip01_L_Forearm
                null,   // Bip01_R_Forearm
                null,   // Bip01_L_Thigh
                null,   // Bip01_R_Thigh
                null,   // Bip01_L_Calf
                null,   // Bip01_R_Calf
            },
            {   // arms
                null,   // Bip01_Pelvis
                null,   // Bip01_Spine
                null,   // Bip01_Spine1
                null,   // Bip01_Neck
                null,   // Bip01_L_Clavicle
                null,   // Bip01_R_Clavicle
                { 0.002621f, 0.013192f, -0.011731f, -0.009953f, 0.013192f, 0.120053f, 0.001302f, -0.093552f, -0.011731f, 0.001302f, 0.120359f, -0.004327f },   // Bip01_L_UpperArm
                { 0.002621f, -0.013192f, 0.011731f, 0.009953f, -0.013192f, 0.120053f, 0.001302f, -0.093553f, 0.011731f, 0.001302f, 0.120359f, -0.004327f },   // Bip01_R_UpperArm
                { 0.004531f, 0.020772f, 0.016986f, -0.017579f, 0.020772f, 0.160703f, -0.002221f, -0.125770f, 0.016986f, -0.002221f, 0.161603f, -0.010628f },   // Bip01_L_Forearm
                { 0.004532f, -0.020772f, -0.016986f, 0.017578f, -0.020772f, 0.160700f, -0.002221f, -0.125767f, -0.016986f, -0.002221f, 0.161599f, -0.010628f },   // Bip01_R_Forearm
                null,   // Bip01_L_Thigh
                null,   // Bip01_R_Thigh
                null,   // Bip01_L_Calf
                null,   // Bip01_R_Calf
            },
        },
    };

    private Allowance() {}

    /** a += the room's change for this bone (cm: the player's values, REGIONS order); true when anything was added */
    static boolean add(float[] a, int sex, int bone, float[] cm) {
        if (cm == null || sex < 0 || sex >= PER_CM.length) return false;
        boolean any = false;
        float[][][] s = PER_CM[sex];
        for (int r = 0; r < s.length && r < cm.length; r++) {
            if (cm[r] == 0f || bone >= s[r].length || s[r][bone] == null) continue;
            float[] d = s[r][bone];
            for (int j = 0; j < 12; j++) a[j] += cm[r] * d[j];
            any = true;
        }
        return any;
    }

    /**
     * The same for a rigid piece (a canteen): only the move of the point c (the piece's middle, bind space), so it
     * goes out whole instead of being stretched by a map that scales about a line or a plane (2026-11-20).
     */
    static boolean addAt(float[] a, int sex, int bone, float[] cm, float[] c) {
        if (cm == null || c == null || sex < 0 || sex >= PER_CM.length) return false;
        boolean any = false;
        float[][][] s = PER_CM[sex];
        for (int r = 0; r < s.length && r < cm.length; r++) {
            if (cm[r] == 0f || bone >= s[r].length || s[r][bone] == null) continue;
            float[] d = s[r][bone];
            for (int i = 0; i < 3; i++)
                a[4 * i + 3] += cm[r] * (d[4 * i] * c[0] + d[4 * i + 1] * c[1] + d[4 * i + 2] * c[2] + d[4 * i + 3]);
            any = true;
        }
        return any;
    }

    /** the room's move of the point c under one bone's maps (cm: REGIONS order), or null when none */
    static float[] moveAt(int sex, int bone, float[] cm, float[] c) {
        if (cm == null || c == null || sex < 0 || sex >= PER_CM.length) return null;
        float[] t = new float[3];
        boolean any = false;
        float[][][] s = PER_CM[sex];
        for (int r = 0; r < s.length && r < cm.length; r++) {
            if (cm[r] == 0f || bone < 0 || bone >= s[r].length || s[r][bone] == null) continue;
            float[] d = s[r][bone];
            for (int i = 0; i < 3; i++)
                t[i] += cm[r] * (d[4 * i] * c[0] + d[4 * i + 1] * c[1] + d[4 * i + 2] * c[2] + d[4 * i + 3]);
            any = true;
        }
        return any ? t : null;
    }

    static boolean any(float[] cm) {
        if (cm == null) return false;
        for (float v : cm) if (v != 0f) return true;
        return false;
    }
}
