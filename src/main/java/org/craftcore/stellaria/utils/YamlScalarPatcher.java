package org.craftcore.stellaria.utils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * config.yml の既存キーの値だけを置き換えるための小さな行ベースYAMLパッチャー。
 * BukkitのYAMLダンパーで全体を保存するとコメントが失われるため、discord.* の
 * スカラー/フロー形式リストをDiscord管理コマンドから保存する用途に限定している。
 */
public final class YamlScalarPatcher {

    private YamlScalarPatcher() {
    }

    /**
     * 指定パスの行だけを置き換える。キーが見つからない、またはファイルを読書きできない場合はfalse。
     */
    public static boolean patch(File file, String path, Object value) {
        String[] segments = path.split("\\.");
        if (segments.length == 0 || !"discord".equals(segments[0])) {
            return false;
        }

        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
            int start = 0;
            int end = lines.size();
            int parentIndent = -1;
            int targetIndex = -1;

            for (String segment : segments) {
                int childIndent = directChildIndent(lines, start, end, parentIndent);
                if (childIndent < 0) {
                    return false;
                }

                Pattern keyPattern = Pattern.compile("^(\\s*" + Pattern.quote(segment) + "\\s*:)(.*)$");
                targetIndex = -1;
                for (int index = start; index < end; index++) {
                    String line = lines.get(index);
                    if (indentOf(line) != childIndent) {
                        continue;
                    }
                    if (keyPattern.matcher(line).matches()) {
                        targetIndex = index;
                        break;
                    }
                }
                if (targetIndex < 0) {
                    return false;
                }

                parentIndent = childIndent;
                start = targetIndex + 1;
                end = blockEnd(lines, start, end, parentIndent);
            }

            String target = lines.get(targetIndex);
            Pattern finalPattern = Pattern.compile("^(\\s*" + Pattern.quote(segments[segments.length - 1]) + "\\s*:)(.*)$");
            Matcher matcher = finalPattern.matcher(target);
            if (!matcher.matches()) {
                return false;
            }

            String suffix = matcher.group(2);
            int commentIndex = commentIndex(suffix);
            if (commentIndex >= 0) {
                while (commentIndex > 0 && Character.isWhitespace(suffix.charAt(commentIndex - 1))) {
                    commentIndex--;
                }
            }
            String preservedComment = commentIndex >= 0 ? suffix.substring(commentIndex) : "";
            lines.set(targetIndex, matcher.group(1) + " " + toYaml(value) + preservedComment);
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int directChildIndent(List<String> lines, int start, int end, int parentIndent) {
        int minimum = Integer.MAX_VALUE;
        for (int index = start; index < end; index++) {
            String line = lines.get(index);
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            int indent = indentOf(line);
            if (indent > parentIndent) {
                minimum = Math.min(minimum, indent);
            }
        }
        return minimum == Integer.MAX_VALUE ? -1 : minimum;
    }

    private static int blockEnd(List<String> lines, int start, int limit, int parentIndent) {
        for (int index = start; index < limit; index++) {
            String line = lines.get(index);
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            if (indentOf(line) <= parentIndent) {
                return index;
            }
        }
        return limit;
    }

    private static int indentOf(String line) {
        int index = 0;
        while (index < line.length() && line.charAt(index) == ' ') {
            index++;
        }
        return index;
    }

    /** 行内コメントとして扱う#だけを拾い、ダブルクォート内の#は値の一部として残す。 */
    private static int commentIndex(String value) {
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                quoted = !quoted;
            } else if (character == '#' && !quoted && (index == 0 || Character.isWhitespace(value.charAt(index - 1)))) {
                return index;
            }
        }
        return -1;
    }

    private static String toYaml(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .map(YamlScalarPatcher::quoted)
                    .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Double) {
            return String.valueOf(value);
        }
        return quoted(String.valueOf(value));
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
