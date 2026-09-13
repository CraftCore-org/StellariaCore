package org.craftcore.stellaria;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.utils.Color;

public class TabList {
    //仮
    public static void updatePlayerTablist(Player player){
        String Header =
                "\n" +
                Color.colorize("&%d") + player.getName() + "さん\n" +
                Color.colorize("&%5すてらりあへようこそ\n");

        String Footer =
                "\n" +
                Color.colorize("&%5stellaria.craftcore.org\n") +
                Color.colorize("     &%ddiscord.gg/ssaREjBJXg     \n");

        player.setPlayerListHeaderFooter(Header,Footer);
    }

    public static void updateAllPlayersTablist(){
        for (Player player : Bukkit.getOnlinePlayers()){
            updatePlayerTablist(player);
        }
    }
}
