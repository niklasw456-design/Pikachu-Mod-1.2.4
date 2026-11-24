package de.texturemixer;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import de.texturemixer.net.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextCodecs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Client-Seite: welche Spieler haben die Mod, und das Icon am Nametag. */
public final class NameIcon {
    private NameIcon() {
    }

    public static final String OFF = "off";
    public static final String BEFORE = "before";
    public static final String AFTER = "after";

    private static final Set<UUID> USERS = ConcurrentHashMap.newKeySet();
    /** Nametag-Farben (RGB) der anderen Mod-Spieler, vom Server geschickt. */
    private static final Map<UUID, Net.NameStyle> COLORS = new ConcurrentHashMap<>();
    private static Text icon;

    public static void setUsers(Iterable<UUID> users) {
        USERS.clear();
        for (UUID u : users) USERS.add(u);
    }

    public static void clear() {
        USERS.clear();
        COLORS.clear();
    }

    public static void setColors(Map<UUID, Net.NameStyle> colors) {
        COLORS.clear();
        COLORS.putAll(colors);
    }

    /** Schickt die eigene Nametag-Farbe an den Server (der sie an alle Mod-Spieler verteilt). Ohne Mod auf dem Server passiert nichts. */
    public static void sendColor() {
        if (!ClientPlayNetworking.canSend(Net.NameColorC2S.ID)) return;
        MixerConfig c = MixerConfig.get();
        ClientPlayNetworking.send(new Net.NameColorC2S(c.nameColor, c.nameColor2, c.nameMode, c.nameIgn));
    }

    /** Stil des Nametags dieses Spielers oder null = Standard. Der eigene Spieler nimmt die Einstellung direkt. */
    private static Net.NameStyle styleOf(UUID id) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && mc.player.getUuid().equals(id)) {
            MixerConfig c = MixerConfig.get();
            return c.nameColor >= 0 ? new Net.NameStyle(c.nameColor, c.nameColor2, c.nameMode, c.nameIgn) : null;
        }
        return COLORS.get(id);
    }

    /** Der Ingame-Name, der gefärbt wird: der eingegebene IGN, sonst der echte Name des Spielers. */
    private static String ignOf(UUID id, String realName) {
        MinecraftClient mc = MinecraftClient.getInstance();
        String ign;
        if (mc.player != null && mc.player.getUuid().equals(id)) {
            ign = MixerConfig.get().nameIgn;
        } else {
            Net.NameStyle st = COLORS.get(id);
            ign = st == null ? null : st.ign();
        }
        return ign == null || ign.isBlank() ? realName : ign.trim();
    }

    /**
     * Färbt NUR das Vorkommen von {@code ign} im Namen ein. Alles andere (Rang, Prefix, Suffix, Teamfarbe)
     * behält seinen Stil. Kommt der IGN nicht vor, bleibt der Text unverändert. Bei mehreren Treffern
     * zählt der letzte (der Name steht fast immer hinter dem Rang).
     */
    public static Text recolor(Text name, String ign, int color) {
        return recolor(name, ign, color, -1, "none");
    }

    /** Farbe des Buchstabens k von n: eine Farbe, abwechselnd oder Verlauf. */
    public static int colorAt(int k, int n, int c1, int c2, String mode) {
        if (c2 < 0 || "none".equals(mode)) return c1;
        if ("alternate".equals(mode)) return k % 2 == 0 ? c1 : c2;
        if ("gradient".equals(mode)) {
            float t = n <= 1 ? 0f : (float) k / (n - 1);
            int r = Math.round(((c1 >> 16) & 255) * (1 - t) + ((c2 >> 16) & 255) * t);
            int g = Math.round(((c1 >> 8) & 255) * (1 - t) + ((c2 >> 8) & 255) * t);
            int b = Math.round((c1 & 255) * (1 - t) + (c2 & 255) * t);
            return (r << 16) | (g << 8) | b;
        }
        return c1;
    }

    /** Wie oben, aber mit zweiter Farbe und Modus ("none", "alternate", "gradient"): jeder Buchstabe des IGN bekommt seine eigene Farbe. */
    public static Text recolor(Text name, String ign, int color, int color2, String mode) {
        if (color < 0 || ign == null || ign.isEmpty()) return name;
        List<Style> styles = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        StringBuilder all = new StringBuilder();
        name.visit((style, str) -> {
            styles.add(style);
            parts.add(str);
            all.append(str);
            return java.util.Optional.<Void>empty();
        }, Style.EMPTY);

        int start = -1;
        for (int i = all.length() - ign.length(); i >= 0; i--) {
            if (all.toString().regionMatches(true, i, ign, 0, ign.length())) {
                start = i;
                break;
            }
        }
        if (start < 0) return name;
        int end = start + ign.length();

        MutableText out = Text.empty();
        int pos = 0;
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            Style st = styles.get(i);
            int ps = pos;
            int pe = pos + part.length();
            pos = pe;
            int os = Math.max(ps, start);
            int oe = Math.min(pe, end);
            if (os >= oe) {
                if (!part.isEmpty()) out.append(Text.literal(part).setStyle(st));
                continue;
            }
            if (os > ps) out.append(Text.literal(part.substring(0, os - ps)).setStyle(st));
            for (int i2 = os; i2 < oe; i2++) {
                int col = colorAt(i2 - start, ign.length(), color, color2, mode);
                out.append(Text.literal(String.valueOf(part.charAt(i2 - ps))).setStyle(st.withColor(col)));
            }
            if (oe < pe) out.append(Text.literal(part.substring(oe - ps)).setStyle(st));
        }
        return out;
    }

    /** Nächster Zustand des Reglers: vor -> hinter -> aus -> vor. */
    public static String next(String mode) {
        if (BEFORE.equals(mode)) return AFTER;
        if (AFTER.equals(mode)) return OFF;
        return BEFORE;
    }

    public static String label(String mode) {
        if (BEFORE.equals(mode)) return "Icon: vor Name";
        if (AFTER.equals(mode)) return "Icon: hinter Name";
        return "Icon: aus";
    }

    private static Text icon() {
        if (icon == null) {
            String json = "{\"text\":\"\\uE000\",\"font\":\"texturemixer:icon\",\"color\":\"white\"}";
            icon = TextCodecs.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).result().orElse(Text.empty());
        }
        return icon;
    }

    private static boolean isModUser(UUID id) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && mc.player.getUuid().equals(id)) return true; // ich selbst habe die Mod ja
        return USERS.contains(id);
    }

    /** Gibt den Namen mit eingefärbtem IGN und Icon zurück (je nach Einstellung und ob der Spieler die Mod hat). */
    public static Text decorate(Text name, UUID player, String realName) {
        if (name.getString().indexOf('\uE000') >= 0) return name; // schon dekoriert, nie doppelt
        Net.NameStyle style = styleOf(player);
        if (style != null) {
            name = recolor(name, ignOf(player, realName), style.color(), style.color2(), style.mode()); // nur der IGN, Rang bleibt wie er ist
        }
        String mode = MixerConfig.get().nameIcon;
        if (mode == null || OFF.equals(mode) || !isModUser(player)) return name;
        Text sp = Text.literal(" ");
        if (BEFORE.equals(mode)) return Text.empty().append(icon()).append(sp).append(name);
        return Text.empty().append(name).append(sp).append(icon());
    }
}
