package top.hmjmfabc.projector.client.music;

import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 歌词（LRC）：时间轴 → 那一行文字。
 *
 * <p>解析规则参考 <b>Net Music Mod（网络音乐机）</b> 的 {@code api/lyric/LyricParser}
 * 与 {@code LyricRecord}（MIT 许可，见 NOTICE），但修掉了参考实现的两个缺口：</p>
 * <ol>
 *   <li><b>一行多个时间戳</b>：{@code [00:01.00][00:05.00]歌词} 这种写法很常见，
 *       参考实现只取第一个、把第二个时间戳当成歌词文字；这里全部登记。</li>
 *   <li><b>不破坏性删除</b>：参考实现用「删掉已经过去的第一行」来推进，删了就回不去
 *       （拖动进度、倒回去重放都会失灵）。这里只做**查询**（floorEntry），数据不动。</li>
 * </ol>
 */
public final class LyricRecord {
    /** 时间戳：{@code [分:秒.毫秒]} 或 {@code [分:秒]}；一行里可以出现多次。 */
    private static final Pattern TIMESTAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");

    private final TreeMap<Integer, String> original;
    private final TreeMap<Integer, String> translated;

    private LyricRecord(TreeMap<Integer, String> original, TreeMap<Integer, String> translated) {
        this.original = original;
        this.translated = translated;
    }

    /** 「这首歌没有歌词」：用来占位，免得每次播放都重试一遍网络请求。 */
    public static LyricRecord empty() {
        return new LyricRecord(new TreeMap<>(), null);
    }

    /** 解析歌词；没有可用内容时返回 null。 */
    public static LyricRecord parse(String originalLrc, String translatedLrc, String title) {
        TreeMap<Integer, String> o = parseLines(originalLrc);
        if (o.isEmpty()) {
            return null;
        }
        TreeMap<Integer, String> t = parseLines(translatedLrc);
        return new LyricRecord(o, t.isEmpty() ? null : t);
    }

    private static TreeMap<Integer, String> parseLines(String lrc) {
        TreeMap<Integer, String> map = new TreeMap<>();
        if (lrc == null || lrc.isBlank()) {
            return map;
        }
        for (String raw : lrc.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = TIMESTAMP.matcher(line);
            int lastEnd = -1;
            while (m.find()) {
                lastEnd = m.end();
                int tick = toTick(m);
                String text = line.substring(m.end()).trim();
                // 去掉紧跟其后的其它时间戳（一行多时间戳的写法）
                Matcher inner = TIMESTAMP.matcher(text);
                if (inner.lookingAt()) {
                    text = text.substring(inner.end()).trim();
                }
                map.put(tick, text);
            }
            if (lastEnd < 0) {
                // 没有任何时间戳的行（[ti:] [ar:] 之类的元信息）直接忽略
                continue;
            }
        }
        return map;
    }

    private static int toTick(Matcher m) {
        int minutes = Integer.parseInt(m.group(1));
        int seconds = Integer.parseInt(m.group(2));
        String frac = m.group(3);
        double millis = 0;
        if (frac != null) {
            millis = Integer.parseInt(frac) * Math.pow(10, 3 - frac.length());
        }
        double totalMs = minutes * 60_000.0 + seconds * 1000.0 + millis;
        // 20 tick = 1 秒（MC 50ms 一 tick）
        return (int) Math.round(totalMs / 50.0);
    }

    public boolean isEmpty() {
        return original.isEmpty();
    }

    public int lineCount() {
        return original.size();
    }

    /** 当前该显示哪一行（没有就返回空串）。 */
    public String lineAt(int tick) {
        return lookup(original, tick);
    }

    /** 当前该显示的翻译（没有就返回空串）。 */
    public String translationAt(int tick) {
        return translated == null ? "" : lookup(translated, tick);
    }

    private static String lookup(TreeMap<Integer, String> map, int tick) {
        Map.Entry<Integer, String> entry = map.floorEntry(tick);
        if (entry == null) {
            // 第一句之前：直接给第一句，免得长时间空白
            entry = map.ceilingEntry(tick);
        }
        return entry == null ? "" : entry.getValue();
    }

    /** 最后一行的时间（tick），用于估算歌词总长。 */
    public int lastTick() {
        return original.isEmpty() ? 0 : original.lastKey();
    }
}
