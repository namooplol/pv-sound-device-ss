package com.namo.pvsound.audio;

import net.minecraft.util.Mth;

/**
 * Mixer knobs. Shared by the server DSP, the block entity NBT and the client screen.
 */
public enum MixerParam {
    VOLUME("volume", 0f, 2f, 1f, Unit.PERCENT),
    BASS("bass", -12f, 12f, 0f, Unit.DB),
    MID("mid", -12f, 12f, 0f, Unit.DB),
    TREBLE("treble", -12f, 12f, 0f, Unit.DB),
    BALANCE("balance", -1f, 1f, 0f, Unit.BALANCE),
    WIDTH("width", 0f, 2f, 1f, Unit.PERCENT),
    FILTER("filter", -1f, 1f, 0f, Unit.FILTER),
    ECHO("echo", 0f, 1f, 0f, Unit.PERCENT),
    SUB("sub", 0f, 2f, 1f, Unit.PERCENT),
    MIC("mic", 0f, 2f, 1f, Unit.PERCENT);

    public enum Unit { PERCENT, DB, BALANCE, FILTER }

    private static final MixerParam[] VALUES = values();

    public final String key;
    public final float min;
    public final float max;
    public final float def;
    public final Unit unit;

    MixerParam(String key, float min, float max, float def, Unit unit) {
        this.key = key;
        this.min = min;
        this.max = max;
        this.def = def;
        this.unit = unit;
    }

    public float clamp(float value) {
        if (Float.isNaN(value)) return def;
        return Mth.clamp(value, min, max);
    }

    public float toSlider(float value) {
        return (value - min) / (max - min);
    }

    public float fromSlider(double slider) {
        float value = (float) (min + slider * (max - min));
        // snap to default so knobs are easy to center
        if (Math.abs(value - def) < (max - min) * 0.02f) value = def;
        return clamp(value);
    }

    public String format(float value) {
        return switch (unit) {
            case PERCENT -> Math.round(value * 100) + "%";
            case DB -> (value > 0 ? "+" : "") + String.format("%.1f dB", value);
            case BALANCE -> value == 0 ? "C" : (value < 0 ? "L" : "R") + Math.round(Math.abs(value) * 100);
            case FILTER -> value == 0 ? "OFF" : (value < 0 ? "LPF " : "HPF ") + Math.round(Math.abs(value) * 100) + "%";
        };
    }

    public static MixerParam byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : null;
    }

    public static float[] defaults() {
        float[] values = new float[VALUES.length];
        for (MixerParam p : VALUES) values[p.ordinal()] = p.def;
        return values;
    }

    public static int count() {
        return VALUES.length;
    }
}
