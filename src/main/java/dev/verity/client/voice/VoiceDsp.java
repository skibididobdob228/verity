package dev.verity.client.voice;

import dev.verity.client.voice.VoiceRecipe.Profile;

/** Verity's voice effect chain. A line-by-line port of pipeline/voice_dsp.py: keep them in sync. */
public final class VoiceDsp {
    public static float[] resample(float[] x, float speed) {
        int n = (int) (x.length / speed);
        float[] y = new float[Math.max(n, 0)];
        for (int k = 0; k < n; k++) {
            float pos = k * speed;
            int i = Math.min((int) pos, x.length - 2);
            float f = pos - i;
            y[k] = x[i] * (1 - f) + x[i + 1] * f;
        }
        return y;
    }

    private static float[] onePoleLp(float[] x, float hz, int sr) {
        float a = (float) (1.0 - Math.exp(-2.0 * Math.PI * hz / sr)), s = 0f;
        float[] y = new float[x.length];
        for (int n = 0; n < x.length; n++) {
            s += a * (x[n] - s);
            y[n] = s;
        }
        return y;
    }

    public static float[] process(float[] x, Profile p, int sr) {
        if (x.length < 4) return x;
        return fx(resample(x, p.pitch()), p, sr);
    }

    /** The effect chain alone, for audio that has already been brought to its final pitch (see Prosody). */
    public static float[] fx(float[] y, Profile p, int sr) {
        if (y.length < 4) return y;
        if (p.highpass() > 0) {
            float[] lp = onePoleLp(y, p.highpass(), sr);
            for (int n = 0; n < y.length; n++) y[n] -= lp[n];
        }
        if (p.drive() > 0) {
            float norm = (float) Math.tanh(p.drive());
            for (int n = 0; n < y.length; n++) y[n] = (float) Math.tanh(y[n] * p.drive()) / norm;
        }
        if (p.ringDepth() > 0) {
            for (int n = 0; n < y.length; n++) {
                y[n] *= 1f - p.ringDepth() + p.ringDepth() * (float) Math.sin(2.0 * Math.PI * p.ringHz() * n / sr);
            }
        }
        if (p.crush() > 1) {
            int k = p.crush();
            for (int n = 0; n < y.length; n++) y[n] = y[n - n % k];
        }
        if (p.combMs() > 0) {
            int d = (int) (sr * p.combMs() / 1000.0);
            float[] src = y.clone();
            for (int n = d; n < y.length; n++) y[n] += p.combGain() * src[n - d];
        }
        if (p.echoMs() > 0) {
            int d = (int) (sr * p.echoMs() / 1000.0);
            float[] z = new float[y.length + d * 3];
            System.arraycopy(y, 0, z, 0, y.length);
            for (int n = d; n < z.length; n++) z[n] += p.echoFb() * z[n - d];
            y = z;
        }
        if (p.lowpass() > 0) y = onePoleLp(onePoleLp(y, p.lowpass(), sr), p.lowpass(), sr);
        return y;
    }

    public static float[] finish(float[] y, float gain) {
        float peak = 0f;
        for (float v : y) peak = Math.max(peak, Math.abs(v));
        if (peak <= 0f) peak = 1f;
        float k = 0.89f * gain / peak;
        for (int n = 0; n < y.length; n++) y[n] *= k;
        return y;
    }

    /** Mouth-opening curve, one value (0..255) per 20 ms. */
    public static byte[] envelope(float[] y, int sr) {
        int hop = sr / 50, n = y.length / hop;
        float[] rms = new float[n];
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (int j = 0; j < hop; j++) sum += y[i * hop + j] * y[i * hop + j];
            rms[i] = (float) Math.sqrt(sum / hop);
        }
        float[] sorted = rms.clone();
        java.util.Arrays.sort(sorted);
        float ref = n == 0 ? 1f : Math.max(sorted[Math.min(n - 1, (int) (n * 0.95f))], 1e-6f);
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = (byte) (int) (Math.pow(Math.min(rms[i] / ref, 1f), 0.6) * 255);
        return out;
    }

    public static short[] toPcm(float[] y) {
        short[] pcm = new short[y.length];
        for (int n = 0; n < y.length; n++) pcm[n] = (short) (Math.max(-1f, Math.min(1f, y[n])) * 32767);
        return pcm;
    }

    private VoiceDsp() {}
}
