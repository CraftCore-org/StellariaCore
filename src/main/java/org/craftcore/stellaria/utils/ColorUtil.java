package org.craftcore.stellaria.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ColorUtil {

    private static final char SECTION = '§';
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([0-9A-Fa-f]{6})");
    private static final Pattern CUSTOM_PATTERN = Pattern.compile("&%(.)");

    /**
    * カスタムカラーコード（&%<char>）と、それに対応する6桁の16進RGB値のマップ 
    * ここで自由にエントリを追加・削除できます
    * マッピングされていない &%<char> は、リテラルテキストとしてそのまま残ります。 
    */
    private static final Map<Character, String> CUSTOM_COLORS = Map.ofEntries(
            // 例: 標準のものとは異なるカスタムの「赤」（RED）
            // ここに追加（例: Map.entry('x', "RRGGBB")）
            Map.entry('0', "000000"), // black
            Map.entry('1', "2D3DA8"), // blue
            Map.entry('2', "4EA34E"), // green
            Map.entry('3', "51B8B8"), // cyan
            Map.entry('4', "CF3030"), // red
            Map.entry('5', "BA41BA"), // purple
            Map.entry('6', "E39700"), // gold
            Map.entry('7', "A4A4AB"), // gray
            Map.entry('8', "57575E"), // dark gray
            Map.entry('9', "7575E0"), // light blue
            Map.entry('a', "96EBB5"), // lime
            Map.entry('b', "8BC7E0"), // aqua
            Map.entry('c', "E67777"), // light red
            Map.entry('d', "EB81EB"), // pink
            Map.entry('e', "E3D05B"), // yellow
            Map.entry('f', "FFFFFF"), // white
            Map.entry('g', "E6A667"), // orange
            Map.entry('h', "A8733E") // brown
    );

    private static final LegacyComponentSerializer HEX_AWARE_SERIALIZER = LegacyComponentSerializer.builder()
            .character(SECTION)
            .hexColors()
            .build();

    private ColorUtil() {
    }

    public static String colorize(String input) {
        if (input == null) {
            return "";
        }
        String expanded = expandCustomCodes(expandHex(input));
        return ChatColor.translateAlternateColorCodes('&', expanded);
    }

    /** Adventure の Component としての、{@link #colorize(String)} と同じ & コード変換（レガシー、16進数、カスタム） */
    public static Component component(String input) {
        return HEX_AWARE_SERIALIZER.deserialize(colorize(input));
    }

    private static String expandHex(String input) {
        Matcher matcher = HEX_PATTERN.matcher(input);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(hexToLegacySection(matcher.group(1))));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String expandCustomCodes(String input) {
        Matcher matcher = CUSTOM_PATTERN.matcher(input);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String hex = CUSTOM_COLORS.get(matcher.group(1).charAt(0));
            String replacement = hex != null ? hexToLegacySection(hex) : matcher.group();
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** 6桁の16進数値を §x§R§R§G§G§B§B に展開します（各桁は § をPrefixとする文字となります） */
    private static String hexToLegacySection(String hex) {
        StringBuilder expanded = new StringBuilder().append(SECTION).append('x');
        for (char hexDigit : hex.toLowerCase().toCharArray()) {
            expanded.append(SECTION).append(hexDigit);
        }
        return expanded.toString();
    }
}
