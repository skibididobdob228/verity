package dev.verity.client.fx;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which of the six stages one of the player's own music files belongs to, judged by its path alone.
 * Three ways of saying it are understood, in this order: the words "stage 3" anywhere in the path; a record-side code at
 * the start of the track title, as on a release pressed over many sides ("A1 - ...", "F8 - ...": sides A and B are the
 * first stage, C and D the second, E and F the third, G to J the fourth, K to N the fifth, O to R the sixth);
 * or a top folder named with a single digit. Anything else belongs to no stage and may play at any time.
 */
public final class MusicStages {
    private static final Pattern STAGE = Pattern.compile("(?:stage|стадия|этап|уровень)[ _.-]*([1-6])(?![0-9])");
    private static final Pattern SIDE = Pattern.compile("(?:^|[ _-])([a-r])[0-9]{1,2}[ _]*-[ _]");

    /** @param path the file's path below the music folder, lower case, with forward slashes. @return 1..6, or 0 for "any" */
    public static int of(String path) {
        Matcher stage = STAGE.matcher(path);
        if (stage.find()) return Integer.parseInt(stage.group(1));
        Matcher side = SIDE.matcher(path.substring(path.lastIndexOf('/') + 1));
        if (side.find()) {
            char c = side.group(1).charAt(0);
            return c <= 'b' ? 1 : c <= 'd' ? 2 : c <= 'f' ? 3 : c <= 'j' ? 4 : c <= 'n' ? 5 : 6;
        }
        return path.matches("^[1-6]/.*") ? path.charAt(0) - '0' : 0;
    }

    private MusicStages() {}
}
