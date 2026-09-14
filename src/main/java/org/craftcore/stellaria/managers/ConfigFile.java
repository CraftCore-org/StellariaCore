package org.craftcore.stellaria.managers;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ディスク上の1個のYAML設定ファイルを表すクラス。jar内にバンドルされたデフォルト値は
 * {@link ConfigManager#register(String)} 側でコピー済みという前提で、ここではロード/保存だけを扱う。
 * orelia-serverutil の {@code ConfigFile} を移植したもの。
 */
public final class ConfigFile {

    private final Logger logger;
    private final File file;
    private final String resourcePath;
    private YamlConfiguration configuration;

    ConfigFile(Logger logger, File dataFolder, String fileName) {
        this.logger = logger;
        this.file = new File(dataFolder, fileName);
        this.resourcePath = fileName;
        load();
    }

    public void load() {
        if (!file.exists()) {
            file.getParentFile().mkdirs();
        }
        this.configuration = YamlConfiguration.loadConfiguration(file);
    }

    public void reload() {
        load();
    }

    public void save() {
        try {
            configuration.save(file);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "設定ファイルの保存に失敗: " + resourcePath, e);
        }
    }

    public YamlConfiguration get() {
        return configuration;
    }

    public File getFile() {
        return file;
    }
}
