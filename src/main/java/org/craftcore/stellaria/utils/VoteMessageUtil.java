package org.craftcore.stellaria.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.OfflinePlayer;
import org.craftcore.stellaria.StellariaCore;

import java.util.ArrayList;
import java.util.List;

public final class VoteMessageUtil {

    private VoteMessageUtil() {
    }

    public static List<Component> build(
            StellariaCore plugin,
            List<String> lines,
            OfflinePlayer player
    ) {
        List<Component> result = new ArrayList<>();

        for (String rawLine : lines) {
            String line = rawLine;

            if (player != null) {
                line = line.replace("%player%", player.getName() != null
                        ? player.getName()
                        : "Unknown");
            }

            result.add(buildLine(plugin, line));
        }

        return result;
    }

    private static Component buildLine(
            StellariaCore plugin,
            String line
    ) {
        Component result = Component.empty();

        int position = 0;

        while (position < line.length()) {
            int jmsIndex = line.indexOf("%jms%", position);
            int mineportalIndex = line.indexOf("%mineportal%", position);

            int nextIndex;
            String placeholder;

            if (jmsIndex == -1 && mineportalIndex == -1) {
                result = result.append(
                        ColorUtil.component(line.substring(position))
                );
                break;
            }

            if (jmsIndex != -1
                    && (mineportalIndex == -1 || jmsIndex < mineportalIndex)) {

                nextIndex = jmsIndex;
                placeholder = "%jms%";

            } else {
                nextIndex = mineportalIndex;
                placeholder = "%mineportal%";
            }

            if (nextIndex > position) {
                result = result.append(
                        ColorUtil.component(
                                line.substring(position, nextIndex)
                        )
                );
            }

            if (placeholder.equals("%jms%")) {
                result = result.append(
                        buildSiteComponent(plugin, "jms")
                );
            } else {
                result = result.append(
                        buildSiteComponent(plugin, "mineportal")
                );
            }

            position = nextIndex + placeholder.length();
        }

        if (line.isEmpty()) {
            return Component.empty();
        }

        return result;
    }

    private static Component buildSiteComponent(
            StellariaCore plugin,
            String site
    ) {
        String base = "vote.sites." + site;

        if (!plugin.getConfigManager()
                .getBoolean(base + ".enabled", true)) {

            return Component.empty();
        }

        String name =
                plugin.getConfigManager()
                        .getString(base + ".name", site);

        String url =
                plugin.getConfigManager()
                        .getString(base + ".url", "");

        String tooltip =
                plugin.getConfigManager()
                        .getString(base + ".tooltip", "");

        Component component =
                ColorUtil.component(name);

        if (!url.isBlank()) {
            component = component.clickEvent(
                    ClickEvent.openUrl(url)
            );
        }

        if (!tooltip.isBlank()) {
            component = component.hoverEvent(
                    HoverEvent.showText(
                            ColorUtil.component(tooltip)
                    )
            );
        }

        return component;
    }
}