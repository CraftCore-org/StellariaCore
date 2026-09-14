package org.craftcore.stellaria;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.utils.ColorUtil;

public class TabList {
    //仮
    public static void updatePlayerTablist(Player player){
        String Header =
                "\n" +
                ColorUtil.colorize("&%d") + player.getName() + "さん\n" +
                ColorUtil.colorize("&%5すてらりあへようこそ\n");

        String Footer =
                "\n" +
                ColorUtil.colorize("&%5stellaria.craftcore.org\n") +
                ColorUtil.colorize("     &%ddiscord.gg/ssaREjBJXg     \n");

        player.setPlayerListHeaderFooter(Header,Footer);
    }

    public static void updateAllPlayersTablist(){
        for (Player player : Bukkit.getOnlinePlayers()){
            updatePlayerTablist(player);
        }
    }
}
