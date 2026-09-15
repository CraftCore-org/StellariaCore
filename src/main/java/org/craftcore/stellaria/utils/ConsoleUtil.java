package org.craftcore.stellaria.utils;

import org.bukkit.Bukkit;

public class ConsoleUtil {
    private static final String[] LOGO = {
        " §b   §b+  §9▒▒    §b+       §9+         ",
        " §b     §9▄██▄  §9* §d┏┓   ┓┓ §b*  §d• §b+   ",
        " §b  §9▒▒██████▒▒ §d┗┓╋┏┓┃┃┏┓┏┓┓┏┓§9+  ",
        " §b     §9▀██▀    §d┗┛┗┗ ┗┗┗┻┛ ┗┗┻   ",
        " §b  §9*   §9▒▒              §b+       "
    };

//
//   +  ▒▒    +       +         
//     ▄██▄  * ┏┓   ┓┓ *  • +   
//  ▒▒██████▒▒ ┗┓╋┏┓┃┃┏┓┏┓┓┏┓+  
//     ▀██▀    ┗┛┗┗ ┗┗┗┻┛ ┗┗┻   
//  *   ▒▒              +       
//

    public static void sendLineBreak() {
        Bukkit.getConsoleSender().sendMessage("");
    }

    public static void printLogo(String version) {
        sendLineBreak();
        for (String line : LOGO) {
            Bukkit.getConsoleSender().sendMessage(line);
        }
        sendLineBreak();
        Bukkit.getConsoleSender().sendMessage(" §dStellaria §7v" + version + " §5launched!");
        sendLineBreak();
    }

    public static void printDisabledMessage() {
        sendLineBreak();
        Bukkit.getConsoleSender().sendMessage(" §dStellaria §7Disabled.");
        sendLineBreak();
    }
}