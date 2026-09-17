package org.craftcore.stellaria.utils;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.function.Consumer;
import java.util.Locale;

/**
 * パーティクル演出をワンショットで出すためのstaticヘルパー。
 * 円・直線を描くだけの薄いラッパーで、状態は一切持たない（{@link ColorUtil}や{@link FormatUtil}と同じ立ち位置）。
 * 継続的なアニメーション（一定間隔での再生し続け等）が必要な場合は呼び出し側でBukkitScheduler等を使うこと。
 */
public final class ParticleUtil {

    private ParticleUtil() {
    }

    /** 設定文字列をParticleへ変換する。不正な値は警告してDUSTへフォールバックする。 */
    public static Particle resolveParticle(String configuredName, Consumer<String> warn) {
        String normalized = configuredName == null ? "" : configuredName.trim().toUpperCase(Locale.ROOT);
        try {
            return Particle.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            warn.accept("パーティクル設定が不正なため、DUSTにフォールバックします: " + configuredName);
            return Particle.DUST;
        }
    }

    /**
     * {@code center}を中心に、水平（XZ平面）の円周上へ単色パーティクルを一発描画する。
     *
     * @param radius 円の半径
     * @param points 円周上の点の数（多いほど滑らかになる）
     */
    public static void spawnCircle(Location center, double radius, int points, Particle particle) {
        spawnCircle(center, radius, points, loc -> loc.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0));
    }

    /**
     * {@code center}を中心に、水平（XZ平面）の円周上へ色付き（{@link Particle#DUST}）パーティクルを一発描画する。
     */
    public static void spawnCircle(Location center, double radius, int points, Color color, float size) {
        Particle.DustOptions dust = new Particle.DustOptions(color, size);
        spawnCircle(center, radius, points, loc -> loc.getWorld().spawnParticle(Particle.DUST, loc, 1, 0, 0, 0, 0, dust));
    }

    private static void spawnCircle(Location center, double radius, int points, Consumer<Location> spawner) {
        World world = center.getWorld();
        if (world == null || points <= 0) return;

        double step = (Math.PI * 2) / points;
        for (int i = 0; i < points; i++) {
            double angle = step * i;
            double x = center.getX() + radius * Math.cos(angle);
            double z = center.getZ() + radius * Math.sin(angle);
            spawner.accept(new Location(world, x, center.getY(), z));
        }
    }

    /**
     * {@code from}から{@code to}まで、{@code spacing}間隔で単色パーティクルを一発描画する（直線）。
     */
    public static void spawnLine(Location from, Location to, double spacing, Particle particle) {
        spawnLine(from, to, spacing, loc -> loc.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0));
    }

    /**
     * {@code from}から{@code to}まで、{@code spacing}間隔で色付き（{@link Particle#DUST}）パーティクルを一発描画する（直線）。
     */
    public static void spawnLine(Location from, Location to, double spacing, Color color, float size) {
        Particle.DustOptions dust = new Particle.DustOptions(color, size);
        spawnLine(from, to, spacing, loc -> loc.getWorld().spawnParticle(Particle.DUST, loc, 1, 0, 0, 0, 0, dust));
    }

    private static void spawnLine(Location from, Location to, double spacing, Consumer<Location> spawner) {
        World world = from.getWorld();
        if (world == null || !world.equals(to.getWorld()) || spacing <= 0) return;

        Vector direction = to.toVector().subtract(from.toVector());
        double length = direction.length();
        if (length == 0) {
            spawner.accept(from.clone());
            return;
        }

        Vector step = direction.normalize().multiply(spacing);
        int count = (int) (length / spacing);
        Location cursor = from.clone();
        for (int i = 0; i <= count; i++) {
            spawner.accept(cursor.clone());
            cursor.add(step);
        }
    }

    /** {@code "#RRGGBB"}（{@code #}は省略可）の16進カラー文字列をBukkitの{@link Color}に変換する。 */
    public static Color parseColor(String hex) {
        String value = hex.startsWith("#") ? hex.substring(1) : hex;
        return Color.fromRGB(Integer.parseInt(value, 16));
    }
}
