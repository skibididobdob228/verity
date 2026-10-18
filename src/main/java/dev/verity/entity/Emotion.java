package dev.verity.entity;

/** Verity's facial expressions. The id is what travels over the network and what the AI replies with. */
public enum Emotion {
    // new faces go at the end: the ordinal is what travels over the network and is saved
    HAPPY, GRIN, NEUTRAL, SAD, ANGRY, SURPRISED, CREEPY, TEETH, WINK, LAUGH, SMUG, SCARED, CRYING, LOVE, BLANK, SLEEPY;

    private static final Emotion[] VALUES = values();

    public static Emotion byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : HAPPY;
    }

    public static Emotion byName(String name, Emotion fallback) {
        if (name != null) {
            for (Emotion e : VALUES) {
                if (e.name().equalsIgnoreCase(name.trim())) return e;
            }
        }
        return fallback;
    }
}
