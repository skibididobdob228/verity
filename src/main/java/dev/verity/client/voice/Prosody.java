package dev.verity.client.voice;

import dev.verity.client.voice.VoiceRecipe.Mood;
import dev.verity.client.voice.VoiceRecipe.Profile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intonation. A port of pipeline/voice_prosody.py (keep them in sync): a line is cut into sentences, and each sentence's
 * pitch is moved along a melody: the voice's own rises and falls made bigger, a yes/no question carried upwards at its end,
 * a full stop let down, an ellipsis left to sag. The pitch is moved with PSOLA, which changes neither speed nor timbre.
 */
public final class Prosody {
    public record Sentence(String text, char end) {}

    private static final float HOP_S = 0.01f;
    private static final Pattern SENTENCE = Pattern.compile("[^.!?…]+[.!?…]*"), WORD = Pattern.compile("[а-яёa-z]+");
    private static final Set<String> WH = Set.of("кто", "что", "чего", "чем", "где", "куда", "когда", "зачем", "почему", "как", "какой", "какая", "какое",
            "какого", "какие", "сколько", "чей", "откуда", "кому", "кого"), FILLER = Set.of("а", "и", "ну", "но", "так");

    public static List<Sentence> split(String text) {
        List<Sentence> out = new ArrayList<>();
        Matcher m = SENTENCE.matcher(text.trim());
        while (m.find()) {
            String part = m.group().trim();
            int cut = part.length();
            while (cut > 0 && ".!?…".indexOf(part.charAt(cut - 1)) >= 0) cut--;
            String tail = part.substring(cut);
            char end = tail.contains("?") ? '?' : tail.contains("!") ? '!' : tail.contains("…") || tail.contains("..") ? '…' : '.';
            if (part.codePoints().anyMatch(Character::isLetterOrDigit)) out.add(new Sentence(part, end));
        }
        if (out.isEmpty()) out.add(new Sentence(text.trim(), '.'));
        return out;
    }

    /** A question that opens with a question word falls at the end in Russian; a yes/no question rises. */
    public static boolean isWh(String sentence) {
        Matcher m = WORD.matcher(sentence.toLowerCase(Locale.ROOT));
        while (m.find()) {
            if (!FILLER.contains(m.group())) return WH.contains(m.group());
        }
        return false;
    }

    /** One-word answers ("Да.", "Что-то.") are the heaviest lines he has; Piper rattles them off, so they are slowed down. */
    public static float brevity(String sentence) {
        int vowels = 0;
        for (char c : sentence.toLowerCase(Locale.ROOT).toCharArray()) if ("аеёиоуыэюяaeiouy".indexOf(c) >= 0) vowels++;
        return vowels <= 1 ? 1.8f : vowels == 2 ? 1.35f : vowels == 3 ? 1.15f : 1f;
    }

    /** Makes a sound longer without lowering it (WSOLA: overlapping windows, each slid a little to line up with the last). */
    public static float[] stretch(float[] x, float factor, int sr) {
        int win = (int) (sr * 0.03f), hs = win / 2, tol = (int) (sr * 0.006f);
        if (factor <= 1.01f || x.length < win * 3) return x;
        float[] w = new float[win];
        for (int i = 0; i < win; i++) w[i] = 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * i / win);
        int frames = (int) ((x.length - win - tol) * factor / hs);
        float[] y = new float[frames * hs + win];
        for (int i = 0; i < win; i++) y[i] += x[i] * w[i];
        int prev = 0;
        for (int k = 1; k < frames; k++) {
            int ideal = (int) (k * hs / factor), best = 0;
            if (prev + hs + win <= x.length) {
                double score = -1e30;
                for (int d = -tol; d <= tol; d += 4) {
                    int a = ideal + d;
                    if (a < 0 || a + win > x.length) continue;
                    double c = 0;
                    for (int i = 0; i < win; i++) c += x[a + i] * x[prev + hs + i];
                    if (c > score) { score = c; best = d; }
                }
            }
            int a = Math.min(Math.max(ideal + best, 0), x.length - win);
            for (int i = 0; i < win; i++) y[k * hs + i] += x[a + i] * w[i];
            prev = a;
        }
        return y;
    }

    /** Piper says a lone open syllable ("Да.", "Я.") in a tenth of a second whatever its tempo: give such words their weight back. */
    public static float[] lengthen(float[] x, int sr, String sentence) {
        int vowels = 0;
        for (char c : sentence.toLowerCase(Locale.ROOT).toCharArray()) if ("аеёиоуыэюяaeiouy".indexOf(c) >= 0) vowels++;
        float seconds = x.length / (float) sr;
        if (vowels > 1 || seconds <= 0.02f || seconds >= 0.4f) return x;
        return stretch(x, Math.min(2.6f, 0.42f / seconds), sr);
    }

    public static float pause(char end) {
        return switch (end) { case '!' -> 0.20f; case '?' -> 0.30f; case '…' -> 0.48f; default -> 0.26f; };
    }

    private static float[] hops(float[] x, int sr) {
        int hop = (int) (sr * HOP_S), n = x.length / hop;
        float[] rms = new float[n];
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (int j = 0; j < hop; j++) sum += x[i * hop + j] * x[i * hop + j];
            rms[i] = (float) Math.sqrt(sum / hop);
        }
        return rms;
    }

    private static float[] smooth(float[] a, int k) {
        if (a.length == 0 || k <= 1) return a;
        int pad = k / 2;
        float[] out = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            float sum = 0;
            for (int j = i - pad; j < i - pad + k; j++) sum += a[Math.max(0, Math.min(a.length - 1, j))];
            out[i] = sum / k;
        }
        return out;
    }

    private static float ease(float v) {
        v = Math.max(0f, Math.min(1f, v));
        return v * v * (3 - 2 * v);
    }

    private static float p95(float[] a) {
        float[] sorted = a.clone();
        Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.95f))];
    }

    /** Cuts the silence Piper leaves around a sentence, so that the pauses are ours to decide. */
    public static float[] trim(float[] x, int sr) {
        float[] rms = hops(x, sr);
        if (rms.length < 4) return x;
        int hop = (int) (sr * HOP_S), first = -1, last = -1;
        float limit = 0.03f * p95(rms);
        for (int i = 0; i < rms.length; i++) {
            if (rms[i] > limit) { if (first < 0) first = i; last = i; }
        }
        if (first < 0) return x;
        return Arrays.copyOfRange(x, Math.max(0, first - 3) * hop, Math.min(rms.length, last + 7) * hop);
    }

    /** The voice's pitch in Hz for every 10 ms (0 where there is no voice): normalised autocorrelation, then the usual repairs. */
    public static float[] trackF0(float[] x, int sr) {
        int hop = (int) (sr * HOP_S), win = (int) (sr * 0.04f), lo = sr / 400, hi = sr / 70, n = x.length / hop;
        float[] f0 = new float[n];
        if (n < 4) return f0;
        double total = 0;
        for (float v : x) total += v * v;
        double ref = Math.sqrt(total / x.length) + 1e-9;
        double[] w = new double[win], c2 = new double[win + 1], r = new double[hi - lo + 1];
        for (int h = 0; h < n; h++) {
            int start = h * hop - win / 2;
            double mean = 0;
            for (int i = 0; i < win; i++) { int k = start + i; w[i] = k >= 0 && k < x.length ? x[k] : 0; mean += w[i]; }
            mean /= win;
            double energy = 0;
            for (int i = 0; i < win; i++) { w[i] -= mean; energy += w[i] * w[i]; c2[i + 1] = energy; }
            if (Math.sqrt(energy / win) < 0.12 * ref) continue;
            double top = -1;
            int pick = 0;
            for (int lag = lo; lag <= hi; lag++) {
                double num = 0;
                for (int i = 0; i < win - lag; i++) num += w[i] * w[i + lag];
                double v = num / (Math.sqrt(c2[win - lag] * (c2[win] - c2[lag])) + 1e-12);
                r[lag - lo] = v;
                if (v > top) { top = v; pick = lag - lo; }
            }
            if (top <= 0.5) continue;
            for (int j = 1; j < r.length - 1; j++) {                 // the first peak that is about as good: the shortest period that fits
                if (r[j] >= top - 0.05 && r[j] >= r[j - 1] && r[j] >= r[j + 1]) { pick = j; break; }
            }
            f0[h] = sr / (float) (lo + pick);
        }
        float[] voiced = positive(f0, 0, n);
        if (voiced.length < 4) return new float[n];
        float med = median(voiced);
        for (int h = 0; h < n; h++) {                                 // octave slips
            if (f0[h] <= 0) continue;
            float[] near = positive(f0, Math.max(0, h - 12), Math.min(n, h + 13));
            float m = near.length >= 3 ? median(near) : med;
            while (f0[h] > 1.75f * m) f0[h] /= 2;
            while (f0[h] < 0.57f * m) f0[h] *= 2;
        }
        float[] out = f0.clone();
        for (int h = 0; h < n; h++) {                                 // single wild frames
            if (f0[h] > 0) out[h] = median(positive(f0, Math.max(0, h - 2), Math.min(n, h + 3)));
        }
        int run = 0;
        for (int h = 0; h <= n; h++) {                                // voiced islands too short to be a vowel
            if (h < n && out[h] > 0) {
                run++;
            } else {
                if (run > 0 && run < 3) Arrays.fill(out, h - run, h, 0f);
                run = 0;
            }
        }
        return out;
    }

    private static float[] positive(float[] a, int from, int to) {
        int count = 0;
        for (int i = from; i < to; i++) if (a[i] > 0) count++;
        float[] out = new float[count];
        for (int i = from, k = 0; i < to; i++) if (a[i] > 0) out[k++] = a[i];
        return out;
    }

    /** numpy's median: the mean of the two middle values when there is an even number of them. */
    private static float median(float[] a) {
        if (a.length == 0) return 0f;
        float[] s = a.clone();
        Arrays.sort(s);
        return s.length % 2 == 1 ? s[s.length / 2] : 0.5f * (s[s.length / 2 - 1] + s[s.length / 2]);
    }

    private static int voicedCount(float[] f0) {
        int c = 0;
        for (float v : f0) if (v > 0) c++;
        return c;
    }

    /** Stretches of voice as {start, end}, bridging the two or three frames a consonant takes. */
    private static List<int[]> voicedRuns(float[] f0) {
        List<int[]> runs = new ArrayList<>();
        int a = -1, gap = 0;
        for (int i = 0; i <= f0.length; i++) {
            boolean on = i < f0.length && f0[i] > 0;
            if (on) {
                if (a < 0) a = i;
                gap = 0;
            } else if (a >= 0) {
                gap++;
                if (gap > 3 || i >= f0.length) { runs.add(new int[] {a, i - gap + 1}); a = -1; gap = 0; }
            }
        }
        return runs;
    }

    /**
     * How far to move the pitch, in semitones, for every 10 ms of one sentence. Mostly it makes the voice's own melody
     * bigger, raising what is stressed rather than pushing the rest down; on top of that the sentence ends the way its
     * punctuation says. Nothing here wavers on its own: every movement belongs to a syllable.
     */
    public static float[] melody(float[] x, int sr, float[] f0, char end, boolean wh, Mood mood, float amount) {
        int n = f0.length;
        float[] s = new float[n];
        int count = voicedCount(f0);
        if (count < 6) return s;
        int first = -1, last = -1;
        for (int i = 0; i < n; i++) if (f0[i] > 0) { if (first < 0) first = i; last = i; }
        float med = median(positive(f0, 0, n));
        // the voice's own melody in semitones, carried straight across the gaps where there is no voice
        float[] natural = new float[n];
        int prev = -1;
        for (int i = 0; i < n; i++) {
            if (f0[i] <= 0) continue;
            float v = (float) (12 * Math.log(f0[i] / med) / Math.log(2));
            if (prev < 0) Arrays.fill(natural, 0, i, v);
            else for (int k = prev + 1; k < i; k++) natural[k] = natural[prev] + (v - natural[prev]) * (k - prev) / (float) (i - prev);
            natural[i] = v;
            prev = i;
        }
        Arrays.fill(natural, prev + 1, n, natural[prev]);
        natural = smooth(natural, 5);
        float r = mood.range() * amount, rq = Math.min(Math.max(r, 0.9f), 1.2f);
        float[] u = new float[n];
        for (int i = 0; i < n; i++) {
            u[i] = Math.max(0f, Math.min(1f, (i - first) / (float) Math.max(last - first, 1)));
            s[i] = Math.max(-2f, Math.min(4.5f, 0.5f * r * natural[i])) + r * 0.4f * (1 - 2 * u[i]);
        }
        if (end == '?' && !wh) {
            // Piper lets a question fall like a statement. Find the last syllable said at full voice and carry the pitch up across it.
            float[] rms = hops(x, sr), env = new float[n];
            for (int i = 0; i < n; i++) env[i] = i < rms.length ? rms[i] : 0f;
            env = smooth(env, 3);
            float[] loud = new float[count];
            for (int i = 0, k = 0; i < n; i++) if (f0[i] > 0) loud[k++] = env[i];
            float floor = 0.4f * median(loud);
            List<int[]> runs = voicedRuns(f0);
            int[] best = null;
            for (int[] run : runs) if (run[1] - run[0] >= 6 && mean(env, run[0], run[1]) >= floor) best = run;
            if (best == null) for (int[] run : runs) if (run[1] - run[0] >= 4) best = run;
            if (best == null) best = runs.get(runs.size() - 1);
            int rb = best[1], ra = Math.max(best[0], rb - 30);
            float base = natural[ra] + s[ra], lift = 0f;
            for (int i = ra; i < rb; i++) {
                lift = Math.max(-1f, Math.min(7f, base + rq * 4.5f * ease((i - ra) / (float) Math.max(rb - ra - 1, 1)) - natural[i]));
                s[i] = lift;
            }
            for (int i = rb; i < n; i++) s[i] = Math.min(lift, 4f) * 0.6f;
        } else {
            for (int i = 0; i < n; i++) {
                float v = ease((u[i] - 0.6f) / 0.4f);
                if (end == '?') s[i] += r * (0.8f * ease((u[i] - 0.8f) / 0.2f) - 0.8f * ease((u[i] - 0.3f) / 0.4f));
                else if (end == '!') s[i] += r * (1.0f - 0.9f * v);
                else if (end == '…') s[i] -= r * 1.3f * ease((u[i] - 0.4f) / 0.6f);
                else s[i] -= r * 0.8f * v;
            }
        }
        double sum = 0;
        for (int i = 0; i < n; i++) if (f0[i] > 0) sum += s[i];
        float shift = 0.5f * (float) (sum / count);                   // bend the phrase, do not move the whole voice up or down
        for (int i = 0; i < n; i++) s[i] = Math.max(-3f, Math.min(7f, s[i] - shift));
        return smooth(s, 5);
    }

    private static float mean(float[] a, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) sum += a[i];
        return (float) (sum / Math.max(to - from, 1));
    }

    private static double[] box(double[] a, int k) {
        double[] out = new double[a.length];
        int left = (k - 1) / 2;                                       // numpy.convolve(..., 'same') with an all-ones kernel
        for (int i = 0; i < a.length; i++) {
            double sum = 0;
            for (int j = i - (k - 1 - left); j <= i + left; j++) if (j >= 0 && j < a.length) sum += a[j];
            out[i] = sum / k;
        }
        return out;
    }

    /** The pulse in this stretch: the highest point, but one period on from the last pulse counts for more than a stray spike. */
    private static int peak(double[] z, int from, int to, double expected, double period) {
        double top = 1e-9;
        for (int i = from; i < to; i++) top = Math.max(top, Math.abs(z[i]));
        int best = from;
        double score = -1e30;
        for (int i = from; i < to; i++) {
            double v = z[i] / top - 0.6 * Math.abs(i - from - expected) / period;
            if (v > score) { score = v; best = i; }
        }
        return best;
    }

    /** One mark per vocal-fold pulse where there is voice, one every 5 ms where there is not. marks[i] &lt; 0 never; flags say which are voiced. */
    private static int[][] pitchMarks(float[] x, int sr, float[] f0) {
        int hop = (int) (sr * HOP_S), n = x.length, k = Math.max(1, sr / 1000), frames = f0.length;
        double[] z = new double[n];
        for (int i = 0; i < n; i++) z[i] = x[i];
        z = box(box(z, k), k);                                         // the pulses without the hiss
        double max = -1e30, min = 1e30;
        for (int h = 0; h < frames; h++) {
            if (f0[h] <= 0) continue;
            for (int i = h * hop; i < Math.min(n, (h + 1) * hop); i++) { max = Math.max(max, z[i]); min = Math.min(min, z[i]); }
        }
        if (max > -1e29 && -min > max) for (int i = 0; i < n; i++) z[i] = -z[i];        // mark the stronger side of the wave
        List<Integer> marks = new ArrayList<>();
        int h = 0;
        while (h < frames) {
            if (f0[h] <= 0) { h++; continue; }
            int e = h;
            while (e < frames && f0[e] > 0) e++;
            int lo = h * hop, hi = Math.min(n, e * hop), mid = (lo + hi) / 2;
            double period = sr / f0[Math.min(frames - 1, mid / hop)];
            int a = Math.max(lo, (int) (mid - period)), b = Math.min(hi, (int) (mid + period)), c = a;
            for (int i = a; i < b; i++) if (z[i] > z[c]) c = i;
            List<Integer> seg = new ArrayList<>();
            seg.add(c);
            int cur = c;
            while (true) {                                             // forwards
                period = sr / Math.max(f0[Math.min(e - 1, Math.max(h, cur / hop))], 1f);
                a = (int) (cur + 0.7 * period);
                b = (int) (cur + 1.35 * period);
                if (b >= hi || a >= b) break;
                cur = peak(z, a, b, cur + period - a, period);
                seg.add(cur);
            }
            cur = c;
            while (true) {                                             // backwards
                period = sr / Math.max(f0[Math.min(e - 1, Math.max(h, cur / hop))], 1f);
                a = (int) (cur - 1.35 * period);
                b = (int) (cur - 0.7 * period);
                if (a < lo || a >= b) break;
                cur = peak(z, a, b, cur - period - a, period);
                seg.add(0, cur);
            }
            marks.addAll(seg);
            h = e;
        }
        marks.sort(null);
        java.util.Set<Integer> voicedAt = new java.util.HashSet<>(marks);
        int step = (int) (sr * 0.005f), prev = 0;
        List<Integer> full = new ArrayList<>();
        List<Integer> withEnd = new ArrayList<>(marks);
        withEnd.add(n - 1);
        for (int idx = 0; idx < withEnd.size(); idx++) {               // fill the voiceless gaps
            int m = withEnd.get(idx);
            boolean isEnd = idx == withEnd.size() - 1;
            for (int p = prev + step; p < m - step / 2; p += step) full.add(p);
            if (m < n - 1 || full.isEmpty() || full.get(full.size() - 1) < m) full.add(m);
            if (isEnd) voicedAt.remove(n - 1);
            prev = m;
        }
        int[] pos = new int[full.size()], flag = new int[full.size()];
        for (int i = 0; i < pos.length; i++) { pos[i] = full.get(i); flag[i] = voicedAt.contains(pos[i]) ? 1 : 0; }
        return new int[][] {pos, flag};
    }

    /**
     * Moves the pitch by 'semis' (per 10 ms) without touching speed or timbre: the voice is cut into single pulses and they
     * are laid down again closer together (higher) or further apart (lower). Where there is no voice the sound is copied.
     */
    public static float[] psola(float[] x, int sr, float[] f0, float[] semis) {
        int n = x.length;
        boolean any = false;
        for (float v : semis) if (Math.abs(v) > 0.05f) { any = true; break; }
        if (n < sr / 20 || semis.length < 2 || !any) return x;
        int[][] pm = pitchMarks(x, sr, f0);
        int[] marks = pm[0], voiced = pm[1];
        int count = marks.length;
        if (count < 4) return x;
        int hop = (int) (sr * HOP_S);
        float[] out = new float[n + 2048], wsum = new float[n + 2048];
        double t = marks[1];
        int i = 1;
        while (t <= marks[count - 2] + 0.5) {
            while (i < count - 2 && Math.abs(marks[i + 1] - t) <= Math.abs(marks[i] - t)) i++;
            int left = marks[i] - marks[i - 1], right = marks[i + 1] - marks[i], c = (int) Math.rint(t);
            if (left > 0 && right > 0 && c - left >= 0) {
                for (int j = 0; j < left + right; j++) {
                    int src = marks[i] - left + j, dst = c - left + j;
                    if (src >= n || dst >= out.length) break;
                    float w = j < left ? 0.5f - 0.5f * (float) Math.cos(Math.PI * j / left) : 0.5f + 0.5f * (float) Math.cos(Math.PI * (j - left) / right);
                    out[dst] += x[src] * w;
                    wsum[dst] += w;
                }
            }
            if (voiced[i] == 1) t += 0.5 * (left + right) / Math.pow(2.0, semis[Math.min(semis.length - 1, c / hop)] / 12.0);
            else t += Math.max(right, 1);
        }
        float[] y = new float[n];
        int head = marks[1], tail = marks[count - 2];                  // the two ends were never windowed in: keep them as they were
        for (int k = 0; k < n; k++) y[k] = k < head || k >= tail ? x[k] : out[k] / Math.max(wsum[k], 0.55f);
        return y;
    }

    /** One sentence with its melody put in. If moving the pitch would roughen the voice it is moved less, or not at all. */
    public static float[] intone(float[] x, int sr, char end, boolean wh, Mood mood, float amount) {
        float[] f0 = trackF0(x, sr);
        int had = voicedCount(f0);
        if (had < 6) return x;
        float[] semis = melody(x, sr, f0, end, wh, mood, amount);
        for (float strength : new float[] {1f, 0.6f}) {
            float[] scaled = new float[semis.length];
            for (int i = 0; i < semis.length; i++) scaled[i] = semis[i] * strength;
            float[] y = psola(x, sr, f0, scaled);
            if (voicedCount(trackF0(y, sr)) >= 0.72f * had) return y;       // still a clear voice afterwards
        }
        return x;
    }

    /** The base pitch ratio of a profile once the emotion has had its say. */
    public static float pitch(Profile p, Mood mood) {
        return p.pitch() * (1 + (mood.pitch() - 1) * Math.min(p.intonation(), 1f));
    }

    private Prosody() {}
}
