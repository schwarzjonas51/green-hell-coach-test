package de.greenhell.coach;

import java.util.Locale;
import java.util.regex.*;

/** Accepts one stable, frozen LAST LAP value, never a continuously running timer. */
public final class LapTracker {
    private static final Pattern TIME = Pattern.compile("(?<![\\d:])([0-5]?\\d):([0-5]\\d)[.,](\\d{3})(?!\\d)");
    private int candidate = -1, last = -1, best = -1, laps;
    private long since;
    public static int parse(String text) {
        Matcher m = TIME.matcher(text);
        int result = -1;
        while (m.find()) {
            int value = Integer.parseInt(m.group(1))*60000 + Integer.parseInt(m.group(2))*1000 + Integer.parseInt(m.group(3));
            if (value <= 0 || result != -1) return -1; // ambiguous crop
            result = value;
        }
        return result;
    }
    public String observe(String text, long now) {
        int value = parse(text);
        if (value < 0) { candidate = -1; return null; }
        if (value != candidate) { candidate = value; since = now; return null; }
        if (now - since < 1800 || value == last) return null;
        int previous = last;
        last = value; laps++;
        String message = "Runde " + laps + ": " + spoken(value) + ".";
        if (best < 0) { best = value; return message + " Referenzzeit gesetzt."; }
        if (value < best) { best = value; message += " Neue Bestzeit!"; }
        int delta = value - previous;
        if (delta == 0) return message + " Genau wie die vorherige Runde.";
        return message + " " + String.format(Locale.GERMAN, "%.2f", Math.abs(delta)/1000.0)
                + " Sekunden " + (delta < 0 ? "schneller" : "langsamer") + " als die vorherige Runde.";
    }
    public static String format(int ms) { return ms < 0 ? "–" : String.format(Locale.GERMAN, "%d:%02d.%03d", ms/60000, ms/1000%60, ms%1000); }
    private static String spoken(int ms) { return ms/60000 + " Minuten und " + String.format(Locale.GERMAN, "%.3f", (ms%60000)/1000.0) + " Sekunden"; }
    public String summary() { return "Runden: " + laps + "   Letzte: " + format(last) + "   Beste: " + format(best); }
}
