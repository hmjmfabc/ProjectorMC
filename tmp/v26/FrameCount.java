import top.hmjmfabc.projector.client.media.VideoSource;
public class FrameCount {
    public static void main(String[] a) throws Exception {
        for (String p : a) {
            try (VideoSource s = VideoSource.open(java.nio.file.Path.of(p))) {
                System.out.println(p + " -> kind=" + s.kind + " 帧数=" + s.frameCount
                        + " 尺寸=" + s.width + "x" + s.height);
                long t0 = System.nanoTime();
                for (int i = 0; i < Math.min(30, s.frameCount); i++) s.frameBytes(i);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                System.out.println("   前 30 帧定位读用时=" + ms + " ms（本机实测）");
            } catch (Throwable t) {
                System.out.println(p + " -> 打不开：" + t);
            }
        }
    }
}
