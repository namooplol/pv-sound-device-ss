package com.namo.pvsound.audio;

import net.minecraft.ChatFormatting;
import net.minecraft.util.StringRepresentable;

/**
 * Which part of the mixer output a speaker reproduces. Each speaker is a mono Plasmo Voice
 * source, so the client positions it in 3D: two speakers set to LEFT/RIGHT give real stereo.
 */
public enum SpeakerChannel implements StringRepresentable {
    LEFT("left", ChatFormatting.AQUA, 0x3BA7FF),
    RIGHT("right", ChatFormatting.RED, 0xFF4A4A),
    MONO("mono", ChatFormatting.GREEN, 0x5BFF6A),
    SUB("sub", ChatFormatting.GOLD, 0xFFB52E);

    public final String name;
    public final ChatFormatting color;
    public final int rgb;

    SpeakerChannel(String name, ChatFormatting color, int rgb) {
        this.name = name;
        this.color = color;
        this.rgb = rgb;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public String translationKey() {
        return "pvsound.channel." + name;
    }

    /** Cycle used by right-clicking a speaker: L -> R -> MONO -> L. */
    public SpeakerChannel next() {
        return switch (this) {
            case LEFT -> RIGHT;
            case RIGHT -> MONO;
            case MONO, SUB -> LEFT;
        };
    }
}
