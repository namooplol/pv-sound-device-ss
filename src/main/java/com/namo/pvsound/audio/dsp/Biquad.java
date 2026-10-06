package com.namo.pvsound.audio.dsp;

/**
 * RBJ cookbook biquad, transposed direct form II.
 */
public final class Biquad {

    private double b0 = 1, b1, b2, a1, a2;
    private double z1, z2;

    public float process(float x) {
        double y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return (float) y;
    }

    public void reset() {
        z1 = z2 = 0;
    }

    public void bypass() {
        set(1, 0, 0, 1, 0, 0);
    }

    public void lowShelf(double sampleRate, double freq, double gainDb) {
        double a = Math.pow(10, gainDb / 40);
        double w0 = 2 * Math.PI * freq / sampleRate;
        double cos = Math.cos(w0);
        double alpha = Math.sin(w0) / 2 * Math.sqrt(2); // shelf slope S = 1
        double sq = 2 * Math.sqrt(a) * alpha;

        set(
                a * ((a + 1) - (a - 1) * cos + sq),
                2 * a * ((a - 1) - (a + 1) * cos),
                a * ((a + 1) - (a - 1) * cos - sq),
                (a + 1) + (a - 1) * cos + sq,
                -2 * ((a - 1) + (a + 1) * cos),
                (a + 1) + (a - 1) * cos - sq
        );
    }

    public void highShelf(double sampleRate, double freq, double gainDb) {
        double a = Math.pow(10, gainDb / 40);
        double w0 = 2 * Math.PI * freq / sampleRate;
        double cos = Math.cos(w0);
        double alpha = Math.sin(w0) / 2 * Math.sqrt(2);
        double sq = 2 * Math.sqrt(a) * alpha;

        set(
                a * ((a + 1) + (a - 1) * cos + sq),
                -2 * a * ((a - 1) + (a + 1) * cos),
                a * ((a + 1) + (a - 1) * cos - sq),
                (a + 1) - (a - 1) * cos + sq,
                2 * ((a - 1) - (a + 1) * cos),
                (a + 1) - (a - 1) * cos - sq
        );
    }

    public void peaking(double sampleRate, double freq, double q, double gainDb) {
        double a = Math.pow(10, gainDb / 40);
        double w0 = 2 * Math.PI * freq / sampleRate;
        double cos = Math.cos(w0);
        double alpha = Math.sin(w0) / (2 * q);

        set(1 + alpha * a, -2 * cos, 1 - alpha * a, 1 + alpha / a, -2 * cos, 1 - alpha / a);
    }

    public void lowPass(double sampleRate, double freq, double q) {
        double w0 = 2 * Math.PI * freq / sampleRate;
        double cos = Math.cos(w0);
        double alpha = Math.sin(w0) / (2 * q);

        set((1 - cos) / 2, 1 - cos, (1 - cos) / 2, 1 + alpha, -2 * cos, 1 - alpha);
    }

    public void highPass(double sampleRate, double freq, double q) {
        double w0 = 2 * Math.PI * freq / sampleRate;
        double cos = Math.cos(w0);
        double alpha = Math.sin(w0) / (2 * q);

        set((1 + cos) / 2, -(1 + cos), (1 + cos) / 2, 1 + alpha, -2 * cos, 1 - alpha);
    }

    private void set(double nb0, double nb1, double nb2, double na0, double na1, double na2) {
        b0 = nb0 / na0;
        b1 = nb1 / na0;
        b2 = nb2 / na0;
        a1 = na1 / na0;
        a2 = na2 / na0;
    }
}
