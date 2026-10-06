package com.namo.pvsound.audio.dsp;

import com.namo.pvsound.audio.MixerParam;

import java.util.Arrays;

/**
 * Stereo channel strip: DJ filter -> 3-band EQ -> ping-pong echo -> stereo width -> balance -> master -> limiter.
 * Also derives a low-passed LFE signal for subwoofers. Runs on the audio thread only.
 */
public final class MixerDsp {

    private static final double SAMPLE_RATE = 48_000;
    private static final float SUB_CUTOFF = 110f;

    private final Biquad[] filter = {new Biquad(), new Biquad()};
    private final Biquad[] bass = {new Biquad(), new Biquad()};
    private final Biquad[] mid = {new Biquad(), new Biquad()};
    private final Biquad[] treble = {new Biquad(), new Biquad()};
    private final Biquad sub1 = new Biquad();
    private final Biquad sub2 = new Biquad();

    private final float[] echoL = new float[(int) (SAMPLE_RATE * 0.32)];
    private final float[] echoR = new float[echoL.length];
    private int echoIndex;

    private float limiterEnv = 0.95f;
    private float subLimiterEnv = 0.95f;

    private float[] lastParams;
    private boolean filterActive;

    public MixerDsp() {
        sub1.lowPass(SAMPLE_RATE, SUB_CUTOFF, 0.707);
        sub2.lowPass(SAMPLE_RATE, SUB_CUTOFF, 0.707);
    }

    /**
     * @param left   in/out left samples, range [-1, 1]
     * @param right  in/out right samples
     * @param subOut LFE output (may be null when no subwoofer is connected)
     */
    public void process(float[] left, float[] right, float[] subOut, float[] params) {
        updateCoefficients(params);

        float volume = params[MixerParam.VOLUME.ordinal()];
        float width = params[MixerParam.WIDTH.ordinal()];
        float balance = params[MixerParam.BALANCE.ordinal()];
        float echo = params[MixerParam.ECHO.ordinal()];
        float subLevel = params[MixerParam.SUB.ordinal()];

        float gainL = balance > 0 ? 1 - balance : 1;
        float gainR = balance < 0 ? 1 + balance : 1;
        float echoWet = echo * 0.7f;
        float echoFeedback = 0.25f + 0.4f * echo;

        for (int i = 0; i < left.length; i++) {
            float l = left[i];
            float r = right[i];

            if (filterActive) {
                l = filter[0].process(l);
                r = filter[1].process(r);
            }

            l = treble[0].process(mid[0].process(bass[0].process(l)));
            r = treble[1].process(mid[1].process(bass[1].process(r)));

            if (subOut != null) {
                float lfe = sub2.process(sub1.process((l + r) * 0.5f)) * subLevel * volume * 1.4f;
                float abs = Math.abs(lfe);
                subLimiterEnv = abs > subLimiterEnv ? abs : subLimiterEnv * 0.9995f + 0.000475f;
                subOut[i] = subLimiterEnv > 0.95f ? lfe * (0.95f / subLimiterEnv) : lfe;
            }

            if (echo > 0.001f) {
                float dl = echoL[echoIndex];
                float dr = echoR[echoIndex];
                // ping-pong: the left echo comes back on the right speaker and vice versa
                echoL[echoIndex] = (l + r) * 0.5f + dr * echoFeedback;
                echoR[echoIndex] = dl * echoFeedback;
                l += dr * echoWet;
                r += dl * echoWet;
            } else {
                echoL[echoIndex] = 0;
                echoR[echoIndex] = 0;
            }
            if (++echoIndex >= echoL.length) echoIndex = 0;

            float m = (l + r) * 0.5f;
            float s = (l - r) * 0.5f * width;
            l = (m + s) * gainL * volume;
            r = (m - s) * gainR * volume;

            // stereo-linked peak limiter: instant attack, ~100ms release
            float peak = Math.max(Math.abs(l), Math.abs(r));
            limiterEnv = peak > limiterEnv ? peak : limiterEnv * 0.9995f + 0.000475f;
            if (limiterEnv > 0.95f) {
                float g = 0.95f / limiterEnv;
                l *= g;
                r *= g;
            }

            left[i] = l;
            right[i] = r;
        }
    }

    public void reset() {
        for (Biquad[] pair : new Biquad[][]{filter, bass, mid, treble}) {
            pair[0].reset();
            pair[1].reset();
        }
        sub1.reset();
        sub2.reset();
        Arrays.fill(echoL, 0);
        Arrays.fill(echoR, 0);
        limiterEnv = subLimiterEnv = 0.95f;
    }

    private void updateCoefficients(float[] params) {
        if (lastParams != null && Arrays.equals(lastParams, params)) return;

        float bassDb = params[MixerParam.BASS.ordinal()];
        float midDb = params[MixerParam.MID.ordinal()];
        float trebleDb = params[MixerParam.TREBLE.ordinal()];
        float filterValue = params[MixerParam.FILTER.ordinal()];

        for (int ch = 0; ch < 2; ch++) {
            bass[ch].lowShelf(SAMPLE_RATE, 120, bassDb);
            mid[ch].peaking(SAMPLE_RATE, 1_000, 0.8, midDb);
            treble[ch].highShelf(SAMPLE_RATE, 6_000, trebleDb);

            if (filterValue < -0.01f) {
                // sweep 18kHz -> 150Hz, exponential so the knob feels linear
                double cutoff = 18_000 * Math.pow(150.0 / 18_000, -filterValue);
                filter[ch].lowPass(SAMPLE_RATE, cutoff, 1.1);
            } else if (filterValue > 0.01f) {
                double cutoff = 30 * Math.pow(4_000.0 / 30, filterValue);
                filter[ch].highPass(SAMPLE_RATE, cutoff, 1.1);
            }
        }

        boolean active = Math.abs(filterValue) > 0.01f;
        if (active && !filterActive) {
            filter[0].reset();
            filter[1].reset();
        }
        filterActive = active;

        lastParams = params.clone();
    }
}
