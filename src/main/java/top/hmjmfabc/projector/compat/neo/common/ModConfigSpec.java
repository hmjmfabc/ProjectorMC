package top.hmjmfabc.projector.compat.neo.common;

import net.fabricmc.loader.api.FabricLoader;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.neo.common.ModConfigSpec} 的替身。
 *
 * <p>NeoForge 的配置系统在 Fabric 上没有对应物。这里实现<b>足够本项目用</b>的一套：
 * 同样的 Builder 写法（{@code comment / push / pop / define / defineInRange}）、
 * 同样的取值方式（{@code INSTANCE.xxx.get()} / {@code .set()} / {@code .save()}）、
 * 同样的文件位置（{@code config/projector-common.toml}）与同样的 TOML 段落结构。</p>
 *
 * <h2>语义要点（与原版对齐）</h2>
 * <ul>
 *   <li>{@code defineInRange} 的上下限是<b>夹取</b>：文件里写了 999999 会被夹回上限，
 *       并打一行 WARN（历史上"defineInRange 上限把默认值夹掉"踩过坑，所以必须留痕）；</li>
 *   <li>文件不存在 ⇒ 用默认值并生成一份带注释的完整文件；文件存在 ⇒ 只认里面写了的键，
 *       缺的键用默认值并补写进文件（新版本新增的键会自动出现）；</li>
 *   <li>任何 I/O 失败都只打日志，<b>绝不影响启动</b>（配置坏了也要能进游戏）。</li>
 * </ul>
 */
public final class ModConfigSpec {

    // ------------------------------------------------------------------
    // 值类型
    // ------------------------------------------------------------------

    /** 一个配置项。 */
    public static class ConfigValue<T> {
        private final String path;
        private final T defaultValue;
        private final List<String> comments;
        private T value;

        ConfigValue(String path, T defaultValue, List<String> comments) {
            this.path = path;
            this.defaultValue = defaultValue;
            this.comments = comments;
            this.value = defaultValue;
        }

        public T get() {
            return value;
        }

        /** 只改内存里的值；写盘要显式 {@link #save()}（与原版一致）。 */
        public void set(T newValue) {
            if (newValue == null) return;
            this.value = newValue;
        }

        /** 把整个配置文件按当前内存值重写一遍。 */
        public void save() {
            ModConfigSpec owner = OWNER;
            if (owner != null) owner.writeFile();
        }

        public String getPath() {
            return path;
        }

        public T getDefault() {
            return defaultValue;
        }

        List<String> comments() {
            return comments;
        }

        /** 把字符串写回值（加载时用）。不认识的格式返回 false。 */
        boolean parseAndSet(String raw) {
            try {
                value = convert(raw);
                return true;
            } catch (Throwable t) {
                logWarn("配置项 " + path + " 的值 " + raw + " 无法解析，已改用默认值 " + defaultValue);
                return false;
            }
        }

        @SuppressWarnings("unchecked")
        T convert(String raw) {
            return (T) raw;
        }

        String serialize() {
            Object v = value;
            return v == null ? "\"\"" : String.valueOf(v);
        }
    }

    /** 整数配置项。 */
    public static class IntValue extends ConfigValue<Integer> {
        IntValue(String path, Integer def, List<String> comments) {
            super(path, def, comments);
        }

        @Override
        Integer convert(String raw) {
            return Integer.parseInt(raw.trim().replace("_", ""));
        }
    }

    /** 长整数配置项。 */
    public static class LongValue extends ConfigValue<Long> {
        LongValue(String path, Long def, List<String> comments) {
            super(path, def, comments);
        }

        @Override
        Long convert(String raw) {
            return Long.parseLong(raw.trim().replace("_", ""));
        }
    }

    /** 浮点配置项。 */
    public static class DoubleValue extends ConfigValue<Double> {
        DoubleValue(String path, Double def, List<String> comments) {
            super(path, def, comments);
        }

        @Override
        Double convert(String raw) {
            return Double.parseDouble(raw.trim());
        }

        @Override
        String serialize() {
            double v = get();
            // TOML 里整数与浮点是两种类型：1 会被读成整数 ⇒ 这里强制带小数点
            if (v == Math.rint(v) && !Double.isInfinite(v)) {
                return String.valueOf((long) v) + ".0";
            }
            return String.valueOf(v);
        }
    }

    /** 布尔配置项。 */
    public static class BooleanValue extends ConfigValue<Boolean> {
        BooleanValue(String path, Boolean def, List<String> comments) {
            super(path, def, comments);
        }

        @Override
        Boolean convert(String raw) {
            String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (s.equals("true")) return Boolean.TRUE;
            if (s.equals("false")) return Boolean.FALSE;
            throw new IllegalArgumentException("不是布尔值：" + raw);
        }
    }

    /** 字符串配置项。 */
    public static class StringValue extends ConfigValue<String> {
        StringValue(String path, String def, List<String> comments) {
            super(path, def, comments);
        }

        @Override
        String convert(String raw) {
            return unquote(raw);
        }

        @Override
        String serialize() {
            return quote(get());
        }
    }

    // ------------------------------------------------------------------
    // Builder
    // ------------------------------------------------------------------

    /** 配置构建器（写法与 NeoForge 一致）。 */
    public static final class Builder {

        private final List<ConfigValue<?>> values = new ArrayList<>();
        private final List<String> pendingComments = new ArrayList<>();
        private final List<String> path = new ArrayList<>();

        public Builder comment(String... lines) {
            for (String line : lines) {
                // 一行一个 # 前缀，与原版生成的文件看起来一样
                for (String part : String.valueOf(line).split("\n", -1)) {
                    pendingComments.add(part);
                }
            }
            return this;
        }

        public Builder push(String section) {
            path.add(section);
            return this;
        }

        public Builder pop() {
            if (!path.isEmpty()) path.remove(path.size() - 1);
            return this;
        }

        public BooleanValue define(String key, boolean def) {
            return track(new BooleanValue(fullPath(key), def, takeComments()));
        }

        public StringValue define(String key, String def) {
            return track(new StringValue(fullPath(key), def, takeComments()));
        }

        /** 带范围的整数（加载时夹取）。 */
        public IntValue defineInRange(String key, int def, int min, int max) {
            IntValue v = new IntValue(fullPath(key), def, takeComments());
            v.set(clamp(def, min, max, v.getPath()));
            RANGES.put(v.getPath(), new long[]{min, max, 0});
            return track(v);
        }

        /** 带范围的长整数（加载时夹取）。 */
        public LongValue defineInRange(String key, long def, long min, long max) {
            LongValue v = new LongValue(fullPath(key), def, takeComments());
            v.set(clamp(def, min, max, v.getPath()));
            RANGES_LONG.put(v.getPath(), new long[]{min, max});
            return track(v);
        }

        /** 带范围的浮点（加载时夹取）。 */
        public DoubleValue defineInRange(String key, double def, double min, double max) {
            DoubleValue v = new DoubleValue(fullPath(key), def, takeComments());
            v.set(clampDouble(def, min, max, v.getPath()));
            RANGES_DOUBLE.put(v.getPath(), new double[]{min, max});
            return track(v);
        }

        /** 组装：调用工厂建出配置对象，随后立刻读盘（缺的键用默认值并补写）。 */
        public <T> Pair<T, ModConfigSpec> configure(Function<Builder, T> factory) {
            T built = factory.apply(this);
            ModConfigSpec spec = new ModConfigSpec(values);
            OWNER = spec;
            spec.load();
            return Pair.of(built, spec);
        }

        private <V extends ConfigValue<?>> V track(V value) {
            values.add(value);
            return value;
        }

        private String fullPath(String key) {
            if (path.isEmpty()) return key;
            return String.join(".", path) + "." + key;
        }

        private List<String> takeComments() {
            List<String> out = new ArrayList<>(pendingComments);
            pendingComments.clear();
            return out;
        }
    }

    // ------------------------------------------------------------------
    // 规格本体
    // ------------------------------------------------------------------

    /** 夹取范围表（按全路径）。 */
    private static final Map<String, long[]> RANGES = new LinkedHashMap<>();
    private static final Map<String, long[]> RANGES_LONG = new LinkedHashMap<>();
    private static final Map<String, double[]> RANGES_DOUBLE = new LinkedHashMap<>();

    /** 当前规格（{@link ConfigValue#save()} 需要它来写盘）。 */
    private static volatile ModConfigSpec OWNER;

    private final List<ConfigValue<?>> values;
    private volatile boolean loaded;

    private ModConfigSpec(List<ConfigValue<?>> values) {
        this.values = values;
    }

    /** 配置文件路径：{@code <gamedir>/config/projector-common.toml}。 */
    public static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("projector-common.toml");
    }

    /** 幂等加载（{@code ModContainer.registerConfig} 会再调一次）。 */
    public void loadIfNeeded() {
        if (!loaded) load();
    }

    // ------------------------------------------------------------------
    // 读写
    // ------------------------------------------------------------------

    private synchronized void load() {
        Path file = configFile();
        Set<String> seen = new LinkedHashSet<>();
        if (Files.isRegularFile(file)) {
            try {
                applyFile(file, seen);
            } catch (Throwable t) {
                logWarn("读取配置失败（将使用默认值）：" + t);
            }
        }
        loaded = true;
        // 文件不存在、或文件里缺少新版本新增的键 ⇒ 生成/补齐一份完整文件
        boolean missingAny = false;
        for (ConfigValue<?> v : values) {
            if (!seen.contains(v.getPath())) {
                missingAny = true;
                break;
            }
        }
        if (!Files.isRegularFile(file) || missingAny) {
            writeFile();
        }
    }

    private void applyFile(Path file, Set<String> seen) throws IOException {
        String section = "";
        for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim();
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String val = stripInlineComment(line.substring(eq + 1).trim());
            String full = section.isEmpty() ? key : section + "." + key;
            ConfigValue<?> target = find(full);
            if (target == null) {
                // 旧版本残留的键（例如已删除的 convert.*）：忽略即可，不必打扰玩家
                continue;
            }
            seen.add(full);
            if (target.parseAndSet(val)) {
                clampLoaded(target);
            }
        }
    }

    private void clampLoaded(ConfigValue<?> target) {
        long[] r = RANGES.get(target.getPath());
        if (r != null && target instanceof IntValue iv) {
            iv.set(clamp(iv.get(), (int) r[0], (int) r[1], target.getPath()));
            return;
        }
        long[] rl = RANGES_LONG.get(target.getPath());
        if (rl != null && target instanceof LongValue lv) {
            lv.set(clamp(lv.get(), rl[0], rl[1], target.getPath()));
            return;
        }
        double[] rd = RANGES_DOUBLE.get(target.getPath());
        if (rd != null && target instanceof DoubleValue dv) {
            dv.set(clampDouble(dv.get(), rd[0], rd[1], target.getPath()));
        }
    }

    /** 按当前内存值重写整份配置文件（{@code save()} 与首次生成都走这里）。 */
    public synchronized void writeFile() {
        Path file = configFile();
        try {
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("# 投影仪 / Projector —— 通用配置\n");
            sb.append("# 改动后重启游戏生效（音乐 Cookie 等例外，命令会立刻写盘）。\n");
            sb.append("# 本文件由模组自动维护：不认识的键会被忽略，新增的键会自动补在对应段落里。\n");
            String currentSection = null;
            for (ConfigValue<?> v : values) {
                String section = sectionOf(v.getPath());
                if (!java.util.Objects.equals(section, currentSection)) {
                    currentSection = section;
                    sb.append('\n');
                    if (!section.isEmpty()) {
                        sb.append('[').append(section).append("]\n");
                    }
                }
                for (String c : v.comments()) {
                    if (c.isEmpty()) {
                        sb.append("#\n");
                    } else {
                        sb.append("# ").append(c).append('\n');
                    }
                }
                sb.append(v.getPath().substring(section.isEmpty() ? 0 : section.length() + 1))
                        .append(" = ")
                        .append(v.serialize())
                        .append('\n');
            }
            String text = sb.toString();
            String old = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
            if (!text.equals(old)) {
                Files.writeString(file, text, StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            logWarn("写入配置失败：" + t);
        }
    }

    private static String sectionOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(0, dot);
    }

    private ConfigValue<?> find(String path) {
        for (ConfigValue<?> v : values) {
            if (v.getPath().equals(path)) return v;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static int clamp(int value, int min, int max, String path) {
        int clamped = Math.max(min, Math.min(max, value));
        if (clamped != value) {
            logWarn("配置项 " + path + " 的 " + value + " 超出范围 [" + min + "," + max + "]，已夹取为 " + clamped);
        }
        return clamped;
    }

    private static long clamp(long value, long min, long max, String path) {
        long clamped = Math.max(min, Math.min(max, value));
        if (clamped != value) {
            logWarn("配置项 " + path + " 的 " + value + " 超出范围 [" + min + "," + max + "]，已夹取为 " + clamped);
        }
        return clamped;
    }

    private static double clampDouble(double value, double min, double max, String path) {
        double clamped = Math.max(min, Math.min(max, value));
        if (clamped != value) {
            logWarn("配置项 " + path + " 的 " + value + " 超出范围 [" + min + "," + max + "]，已夹取为 " + clamped);
        }
        return clamped;
    }

    private static String stripInlineComment(String raw) {
        boolean inQuote = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && inQuote) {
                i++;
                continue;
            }
            if (c == '"') {
                inQuote = !inQuote;
                continue;
            }
            if (c == '#' && !inQuote) {
                return raw.substring(0, i).trim();
            }
        }
        return raw.trim();
    }

    private static String unquote(String raw) {
        String s = raw.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            String body = s.substring(1, s.length() - 1);
            StringBuilder sb = new StringBuilder(body.length());
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                if (c == '\\' && i + 1 < body.length()) {
                    char n = body.charAt(++i);
                    switch (n) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        default -> sb.append(n);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        return s;
    }

    private static String quote(String raw) {
        String s = raw == null ? "" : raw;
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static void logWarn(String message) {
        org.slf4j.LoggerFactory.getLogger("projector/config").warn("[Projector][配置] {}", message);
    }
}
