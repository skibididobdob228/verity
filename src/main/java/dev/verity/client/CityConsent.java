package dev.verity.client;

import dev.verity.VerityConfig;
import dev.verity.net.CityPayload;
import java.time.ZoneId;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * One of Verity's scares is naming the city you are in. He does not locate anyone: the "city" is just the name of the
 * computer's time zone (Europe/Moscow -> Moscow), so it is often only the nearest big city. Even so, nothing leaves this
 * computer unless the player agrees: on entering a world a screen asks, and only a "yes" sends the name to the server.
 * The same yes covers everything else he does with the real computer: the program names here, and {@link FourthWall}.
 */
public final class CityConsent {
    private static final Map<String, String> RU = Map.ofEntries(Map.entry("Moscow", "Москва"), Map.entry("Kaliningrad", "Калининград"),
            Map.entry("Samara", "Самара"), Map.entry("Yekaterinburg", "Екатеринбург"), Map.entry("Omsk", "Омск"), Map.entry("Novosibirsk", "Новосибирск"),
            Map.entry("Krasnoyarsk", "Красноярск"), Map.entry("Irkutsk", "Иркутск"), Map.entry("Vladivostok", "Владивосток"), Map.entry("Volgograd", "Волгоград"),
            Map.entry("Saratov", "Саратов"), Map.entry("Kirov", "Киров"), Map.entry("Astrakhan", "Астрахань"), Map.entry("Ulyanovsk", "Ульяновск"),
            Map.entry("Barnaul", "Барнаул"), Map.entry("Tomsk", "Томск"), Map.entry("Novokuznetsk", "Новокузнецк"), Map.entry("Chita", "Чита"),
            Map.entry("Yakutsk", "Якутск"), Map.entry("Khabarovsk", "Хабаровск"), Map.entry("Magadan", "Магадан"), Map.entry("Sakhalin", "Сахалин"),
            Map.entry("Kamchatka", "Камчатка"), Map.entry("Minsk", "Минск"), Map.entry("Kiev", "Киев"), Map.entry("Kyiv", "Киев"), Map.entry("Almaty", "Алматы"),
            Map.entry("Tashkent", "Ташкент"), Map.entry("Tbilisi", "Тбилиси"), Map.entry("Yerevan", "Ереван"), Map.entry("Baku", "Баку"),
            Map.entry("Bishkek", "Бишкек"), Map.entry("Chisinau", "Кишинёв"), Map.entry("Riga", "Рига"), Map.entry("Vilnius", "Вильнюс"),
            Map.entry("Tallinn", "Таллин"), Map.entry("Berlin", "Берлин"), Map.entry("London", "Лондон"), Map.entry("Paris", "Париж"),
            Map.entry("Warsaw", "Варшава"), Map.entry("Istanbul", "Стамбул"), Map.entry("New York", "Нью-Йорк"));

    /** Program name fragments (lower case) -> the key the server knows. Nothing outside this list is ever reported. */
    private static final Map<String, String> APPS = Map.ofEntries(Map.entry("telegram", "telegram"), Map.entry("discord", "discord"), Map.entry("vesktop", "discord"),
            Map.entry("steam", "steam"), Map.entry("obs", "obs"), Map.entry("spotify", "spotify"), Map.entry("codium", "code"), Map.entry("code", "code"),
            Map.entry("firefox", "browser"), Map.entry("chrome", "browser"), Map.entry("chromium", "browser"), Map.entry("zen", "browser"),
            Map.entry("opera", "browser"), Map.entry("brave", "browser"), Map.entry("yandex", "browser"), Map.entry("msedge", "browser"));

    private static int ticksInWorld;
    private static boolean asked, allowed;
    private static String name = "";

    /**
     * Which well-known programs are running, judged by the name of the executable alone ("telegram-desktop" -> telegram).
     * No window titles, no files, no contents.
     */
    public static String apps() {
        java.util.Set<String> found = new java.util.TreeSet<>();
        try {
            ProcessHandle.allProcesses().forEach(ph -> ph.info().command().ifPresent(cmd -> {
                String name = cmd.substring(Math.max(cmd.lastIndexOf('/'), cmd.lastIndexOf('\\')) + 1).toLowerCase(java.util.Locale.ROOT).replace(".exe", "");
                for (Map.Entry<String, String> e : APPS.entrySet()) {
                    if (name.equals(e.getKey()) || name.startsWith(e.getKey() + "-") || name.startsWith(e.getKey() + "_") || name.endsWith("-" + e.getKey())) found.add(e.getValue());
                }
            }));
        } catch (Exception ignored) {
        }
        return String.join(",", found);
    }

    /**
     * The last scare: a file verity.txt appears in the game folder with this computer's own network address in it.
     * The address is read locally and written locally: it is never sent to the server, to the AI or to anyone else.
     * Does nothing unless the player said yes on the consent screen.
     */
    public static void leaveNote() {
        if (!allowed) return;
        String ip = localAddress();
        FourthWall.note("привет" + (name.isEmpty() ? "" : ", " + name.toLowerCase(java.util.Locale.ROOT)) + ".\n\nэто я. верити.\n\n" + (ip.isEmpty() ? "" : "твой адрес: " + ip + "\n\n") + "я знаю, где ты сидишь. я никогда не лгу.\nне уходи.\n\n:)\n");
    }

    /** Did the player say yes on the consent screen (this visit to the world)? */
    public static boolean allowed() { return allowed; }

    private static void grant(String city, String typedName) {
        allowed = true;
        name = CityPayload.cleanName(typedName);
        PacketDistributor.sendToServer(new CityPayload(city, apps(), name));
    }

    /** The first name the player typed in for this visit, or "". */
    public static String name() { return allowed ? name : ""; }

    /** Development harness only: "consent show", "consent submit <name>" (fill in the open screen and press yes), "consent yes [name]" in a selftest script. */
    public static void debug(String arg) {
        if (arg.equals("show")) ask(Minecraft.getInstance(), city());
        else if (arg.startsWith("submit") && Minecraft.getInstance().screen instanceof ConsentScreen screen) screen.submit(arg.substring(6).trim());
        else grant(city(), arg.startsWith("yes ") ? arg.substring(4) : "");
    }

    private static void ask(Minecraft mc, String city) {
        mc.setScreen(new ConsentScreen(city, VerityConfig.REAL_NAME.get(), (yes, typed) -> {
            mc.setScreen(null);
            if (!yes) return;
            String clean = CityPayload.cleanName(typed);
            if (!clean.equals(VerityConfig.REAL_NAME.get())) {           // remembered on this computer so the box is filled in next time
                VerityConfig.REAL_NAME.set(clean);
                VerityConfig.REAL_NAME.save();
            }
            grant(city, clean);
        }));
    }

    /**
     * This computer's address on its home network (192.168.x.x and the like). Read from the list of network interfaces;
     * nothing is sent anywhere to find it out. Addresses of VPN tunnels are passed over.
     */
    private static String localAddress() {
        String best = "";
        int bestRank = 9;
        try {
            for (java.net.NetworkInterface n : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback() || n.isPointToPoint() || n.isVirtual()) continue;
                for (java.net.InetAddress a : java.util.Collections.list(n.getInetAddresses())) {
                    if (!(a instanceof java.net.Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    int rank = ip.startsWith("192.168.") ? 0 : ip.startsWith("172.") ? 1 : 2;
                    if (rank < bestRank) { best = ip; bestRank = rank; }
                }
            }
        } catch (Exception ignored) {
        }
        if (!best.isEmpty()) return best;
        // no such interface: ask the system which address it would use for the outside world (a UDP "connect" sends no packet)
        try (java.net.DatagramSocket s = new java.net.DatagramSocket()) {
            s.connect(java.net.InetAddress.getByAddress(new byte[] {(byte) 192, 0, 2, 1}), 9);
            java.net.InetAddress a = s.getLocalAddress();
            if (a != null && !a.isAnyLocalAddress() && !a.isLoopbackAddress()) return a.getHostAddress();
        } catch (Exception ignored) {
        }
        return "";
    }

    /** The city the time zone is named after, or "" if the zone has no city in its name (UTC and the like). */
    public static String city() {
        String id = ZoneId.systemDefault().getId();
        int slash = id.lastIndexOf('/');
        if (slash < 0 || id.startsWith("Etc/") || id.startsWith("SystemV/")) return "";
        String name = id.substring(slash + 1).replace('_', ' ');
        return RU.getOrDefault(name, name);
    }

    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) { ticksInWorld = 0; asked = false; allowed = false; name = ""; return; }
        ticksInWorld++;
        if (allowed && ticksInWorld % 1200 == 0) PacketDistributor.sendToServer(new CityPayload(city(), apps(), name));       // what is open changes
        if (asked || ticksInWorld < 60 || mc.screen != null) return;
        asked = true;
        String city = city(), mode = VerityConfig.CITY_SCARE.get();
        if (mode.equalsIgnoreCase("never") || System.getProperty("verity.selftest") != null) return;
        if (mode.equalsIgnoreCase("always")) grant(city, VerityConfig.REAL_NAME.get()); else ask(mc, city);
    }

    private CityConsent() {}
}
