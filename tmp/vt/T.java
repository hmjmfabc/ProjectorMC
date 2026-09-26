import top.hmjmfabc.projector.common.text.FormatCodes;
import top.hmjmfabc.projector.common.text.FormatCodes.Run;

public class T {
    static int fails = 0;
    static void chk(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + "  " + detail);
        if (!ok) fails++;
    }
    static String runs(String s) {
        StringBuilder sb = new StringBuilder();
        for (Run r : FormatCodes.parse(s)) {
            sb.append('[').append(r.text()).append(" g=").append(r.style().gradient)
              .append(" from=").append(String.format("%06X", r.style().gradFrom & 0xFFFFFF))
              .append(" to=").append(String.format("%06X", r.style().gradTo & 0xFFFFFF))
              .append(" color=").append(String.format("%06X", r.style().color & 0xFFFFFF)).append("] ");
        }
        return sb.toString().trim();
    }
    public static void main(String[] a) {
        String s1 = "&z测试文本";
        System.out.println("&z -> " + runs(s1));
        var r1 = FormatCodes.parse(s1);
        chk("&z 只有一段且文字干净", r1.size() == 1 && r1.get(0).text().equals("测试文本"), "text=" + r1.get(0).text());
        chk("&z 渐变类型=RAINBOW", r1.get(0).style().gradient == FormatCodes.GRAD_RAINBOW, "");
        chk("&z strip() 干净", FormatCodes.strip(s1).equals("测试文本"), "strip=" + FormatCodes.strip(s1));
        var st = r1.get(0).style();
        int[] cols = new int[4];
        for (int i = 0; i < 4; i++) cols[i] = FormatCodes.gradientColor(st, i / 3.0);
        boolean distinct = cols[0] != cols[1] && cols[1] != cols[2] && cols[2] != cols[3];
        chk("&z 四色互不相同", distinct, String.format("%06X %06X %06X %06X",
                cols[0]&0xFFFFFF, cols[1]&0xFFFFFF, cols[2]&0xFFFFFF, cols[3]&0xFFFFFF));
        chk("&z 全不透明", ((cols[0]>>>24)&0xFF)==0xFF && ((cols[3]>>>24)&0xFF)==0xFF, "");

        String s2 = "&s#66CCFF&#39C5BB渐变文字";
        System.out.println("&s 原例 -> " + runs(s2));
        var r2 = FormatCodes.parse(s2);
        chk("&s 原例 解析成功", r2.size() == 1 && r2.get(0).text().equals("渐变文字"), runs(s2));
        chk("&s 原例 from=66CCFF", (r2.get(0).style().gradFrom & 0xFFFFFF) == 0x66CCFF,
                String.format("%06X", r2.get(0).style().gradFrom & 0xFFFFFF));
        chk("&s 原例 to=39C5BB", (r2.get(0).style().gradTo & 0xFFFFFF) == 0x39C5BB,
                String.format("%06X", r2.get(0).style().gradTo & 0xFFFFFF));
        int cA = FormatCodes.gradientColor(r2.get(0).style(), 0.0);
        int cB = FormatCodes.gradientColor(r2.get(0).style(), 1.0);
        int cM = FormatCodes.gradientColor(r2.get(0).style(), 0.5);
        chk("&s t=0 -> 66CCFF", (cA & 0xFFFFFF) == 0x66CCFF, String.format("%06X", cA & 0xFFFFFF));
        chk("&s t=1 -> 39C5BB", (cB & 0xFFFFFF) == 0x39C5BB, String.format("%06X", cB & 0xFFFFFF));
        chk("&s t=0.5 居中插值", (cM & 0xFFFFFF) == 0x50C9DD, String.format("%06X", cM & 0xFFFFFF));

        String s3 = "&s#FF0000e#0000FF规";
        System.out.println("&s 规格写法 -> " + runs(s3));
        var r3 = FormatCodes.parse(s3);
        chk("&s 规格写法 解析成功", r3.size() == 1 && r3.get(0).text().equals("规"), runs(s3));

        String s4 = "&sae b";
        var r4 = FormatCodes.parse(s4);
        System.out.println("&s 16色端点 '&sae b' -> " + runs(s4));
        chk("&s 16色端点 解析成功", r4.size() == 1 && (r4.get(0).style().gradFrom & 0xFFFFFF) == 0x55FF55,
                runs(s4));

        int[] smp = FormatCodes.gradientSamples(FormatCodes.GRAD_RAINBOW, 0, 0, 8);
        StringBuilder sb = new StringBuilder();
        for (int c : smp) sb.append(String.format("%06X ", c & 0xFFFFFF));
        System.out.println("rainbow samples -> " + sb);

        String s5 = "&zAB&cCD";
        System.out.println("&zAB&cCD -> " + runs(s5));
        var r5 = FormatCodes.parse(s5);
        chk("颜色代码终止渐变", r5.size() == 2 && r5.get(0).style().gradient == FormatCodes.GRAD_RAINBOW
                && r5.get(1).style().gradient == FormatCodes.GRAD_NONE, runs(s5));
        chk("终止后颜色=c 红", (r5.get(1).style().color & 0xFFFFFF) == 0xFF5555,
                String.format("%06X", r5.get(1).style().color & 0xFFFFFF));

        chk("&& 仍是字面 &", FormatCodes.strip("A&&B").equals("A&B"), FormatCodes.strip("A&&B"));

        String s6 = "&sXY随便";
        System.out.println("非法 &s -> " + runs(s6));
        chk("非法 &s 不丢字符", FormatCodes.strip(s6).contains("随便"), FormatCodes.strip(s6));

        char[] D = {'\u3007','\u58f9','\u8d30','\u53c1','\u8086','\u4f0d','\u9646','\u67d2','\u634c','\u7396'};
        char TEN = '\u62fe';
        StringBuilder up = new StringBuilder();
        for (int n : new int[]{0, 5, 10, 12, 19}) {
            String got;
            if (n < 10) got = "" + D[0] + D[n];
            else if (n < 20) got = "" + TEN + (n == 10 ? "" : String.valueOf(D[n % 10]));
            else got = "" + D[n / 10] + TEN + (n % 10 == 0 ? "" : String.valueOf(D[n % 10]));
            up.append(n).append("->").append(got).append("  ");
        }
        System.out.println("upperTwo: " + up);
        chk("大写 12 = 拾贰", up.toString().contains("12->拾贰"), up.toString());
        chk("大写 0 = 〇〇", up.toString().contains("0->〇〇"), up.toString());
        System.out.println(fails == 0 ? "\n== ALL PASS ==" : "\n== " + fails + " FAILED ==");
        if (fails > 0) System.exit(1);
    }
}
