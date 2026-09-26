package top.hmjmfabc.projector.common.game;

import java.util.ArrayList;
import java.util.List;

/**
 * 【⑪】落子类棋：井字棋、五子棋、围棋。
 *
 * <p>这三个都是「往空格里放一枚自己的子」，着法生成与胜负判定都在棋盘上做，
 * 因此共用同一套扫描工具。</p>
 */
final class GamesDrop {

    private GamesDrop() {
    }

    /** 公共工具：直线方向。 */
    static final int[][] DIRS4 = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};

    // ==================================================================
    // 井字棋：3x3，横竖斜连三子胜
    // ==================================================================
    static final class TicTacToe implements GameRules {

        @Override
        public int kind() {
            return GameKind.TIC_TAC_TOE;
        }

        @Override
        public int width() {
            return 3;
        }

        @Override
        public int height() {
            return 3;
        }

        @Override
        public int[] initialBoard() {
            return new int[9];
        }

        @Override
        public List<Move> moves(int[] board, int side) {
            List<Move> out = new ArrayList<>();
            for (int y = 0; y < 3; y++) {
                for (int x = 0; x < 3; x++) {
                    if (board[y * 3 + x] == 0) out.add(Move.drop(x, y));
                }
            }
            return out;
        }

        @Override
        public int[] apply(int[] board, Move m, int side) {
            int[] b = board.clone();
            if (m.isDrop() && GameRules.inside(3, 3, m.tx(), m.ty())
                    && b[m.ty() * 3 + m.tx()] == 0) {
                b[m.ty() * 3 + m.tx()] = side;
            }
            return b;
        }

        @Override
        public int result(int[] board, int side, int movesWithoutProgress) {
            int[] lines = {
                    0, 1, 2, 3, 4, 5, 6, 7, 8,          // 行
                    0, 3, 6, 1, 4, 7, 2, 5, 8,          // 列
                    0, 4, 8, 2, 4, 6};                  // 两条斜线
            for (int i = 0; i < lines.length; i += 3) {
                int a = board[lines[i]], b2 = board[lines[i + 1]], c = board[lines[i + 2]];
                if (a != 0 && a == b2 && b2 == c) return Integer.signum(a);
            }
            for (int v : board) {
                if (v == 0) return 0;
            }
            return 2;   // 满盘平局
        }

        @Override
        public int evaluate(int[] board, int side) {
            // 井字棋局面太小，直接按「赢了给巨大分」即可，AI 会自动走满值搜索
            int r = result(board, side, 0);
            if (r == 2) return 0;
            if (r != 0) return r == side ? 100000 : -100000;
            return 0;
        }

        @Override
        public String glyph(int piece) {
            if (piece == 0) return "";
            return piece > 0 ? "\u00d7" : "\u25cb";   // × / ○
        }

        @Override
        public long aiThinkMs() {
            return 120;
        }
    }

    // ==================================================================
    // 五子棋：15x15，横竖斜先连成五子者胜
    // ==================================================================
    static final class Gomoku implements GameRules {

        static final int N = 15;

        @Override
        public int kind() {
            return GameKind.GOMOKU;
        }

        @Override
        public int width() {
            return N;
        }

        @Override
        public int height() {
            return N;
        }

        @Override
        public int[] initialBoard() {
            return new int[N * N];
        }

        @Override
        public List<Move> moves(int[] board, int side) {
            List<Move> out = new ArrayList<>();
            boolean any = false;
            for (int v : board) {
                if (v != 0) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                out.add(Move.drop(N / 2, N / 2));
                return out;
            }
            // 只考虑已有棋子附近 2 格内的空点：五子棋的着法数太大，
            // 不裁剪的话连「生成一遍着法」都会拖慢 AI（这是标准做法）。
            boolean[] seen = new boolean[N * N];
            for (int y = 0; y < N; y++) {
                for (int x = 0; x < N; x++) {
                    if (board[y * N + x] == 0) continue;
                    for (int dy = -2; dy <= 2; dy++) {
                        for (int dx = -2; dx <= 2; dx++) {
                            int nx = x + dx, ny = y + dy;
                            if (!GameRules.inside(N, N, nx, ny)) continue;
                            int id = ny * N + nx;
                            if (board[id] != 0 || seen[id]) continue;
                            seen[id] = true;
                            out.add(Move.drop(nx, ny));
                        }
                    }
                }
            }
            return out;
        }

        @Override
        public int[] apply(int[] board, Move m, int side) {
            int[] b = board.clone();
            if (m.isDrop() && GameRules.inside(N, N, m.tx(), m.ty())
                    && b[m.ty() * N + m.tx()] == 0) {
                b[m.ty() * N + m.tx()] = side;
            }
            return b;
        }

        @Override
        public int result(int[] board, int side, int movesWithoutProgress) {
            for (int y = 0; y < N; y++) {
                for (int x = 0; x < N; x++) {
                    int v = board[y * N + x];
                    if (v == 0) continue;
                    for (int[] d : DIRS4) {
                        if (runLength(board, x, y, d[0], d[1], v) >= 5) return Integer.signum(v);
                    }
                }
            }
            return 0;   // 五子棋默认不判满盘和棋，交给界面「重新开始」
        }

        /** 从 (x,y) 沿 (dx,dy) 连续同色子的个数。 */
        static int runLength(int[] b, int x, int y, int dx, int dy, int v) {
            int n = 0;
            int cx = x, cy = y;
            while (GameRules.inside(N, N, cx, cy) && b[cy * N + cx] == v) {
                n++;
                cx += dx;
                cy += dy;
            }
            return n;
        }

        /**
         * 评估：对每一方，把所有「活口」形状加权求和。
         *
         * <p>这是五子棋 AI 的经典做法：不搜到很深也能下得像样。
         * 权重按「连子长度 + 两端是否被堵」分档。</p>
         */
        @Override
        public int evaluate(int[] board, int side) {
            return shapeScore(board, side) - (int) (shapeScore(board, -side) * 1.1);
        }

        private static int shapeScore(int[] b, int target) {
            int total = 0;
            for (int y = 0; y < N; y++) {
                for (int x = 0; x < N; x++) {
                    if (b[y * N + x] != target) continue;
                    for (int[] d : DIRS4) {
                        // 只看每一段的起点，避免重复计数
                        int px = x - d[0], py = y - d[1];
                        if (GameRules.inside(N, N, px, py) && b[py * N + px] == target) continue;
                        int len = runLength(b, x, y, d[0], d[1], target);
                        boolean openA = GameRules.inside(N, N, px, py) && b[py * N + px] == 0;
                        int ex = x + d[0] * len, ey = y + d[1] * len;
                        boolean openB = GameRules.inside(N, N, ex, ey) && b[ey * N + ex] == 0;
                        int open = (openA ? 1 : 0) + (openB ? 1 : 0);
                        total += shapeValue(len, open);
                    }
                }
            }
            return total;
        }

        private static int shapeValue(int len, int open) {
            if (len >= 5) return 10_000_000;
            if (open == 0) return 0;
            return switch (len) {
                case 4 -> open == 2 ? 1_000_000 : 100_000;
                case 3 -> open == 2 ? 50_000 : 5_000;
                case 2 -> open == 2 ? 2_000 : 300;
                default -> open == 2 ? 200 : 50;
            };
        }

        @Override
        public boolean piecesOnIntersections() {
            return true;   // 五子棋的子落在交叉点上
        }

        @Override
        public String glyph(int piece) {
            if (piece == 0) return "";
            return piece > 0 ? "\u25cf" : "\u25cb";   // ● / ○
        }

        @Override
        public long aiThinkMs() {
            return 400;
        }
    }

    // ==================================================================
    // 围棋：9x9（简化规则：气/提子/禁自杀/简单劫争；按「子 + 围空」数目差计分）
    // ==================================================================
    static final class Go implements GameRules {

        static final int N = 9;

        @Override
        public int kind() {
            return GameKind.GO;
        }

        @Override
        public int width() {
            return N;
        }

        @Override
        public int height() {
            return N;
        }

        @Override
        public int[] initialBoard() {
            return new int[N * N];
        }

        @Override
        public List<Move> moves(int[] board, int side) {
            List<Move> out = new ArrayList<>();
            for (int y = 0; y < N; y++) {
                for (int x = 0; x < N; x++) {
                    if (board[y * N + x] != 0) continue;
                    int[] after = play(board, x, y, side);
                    if (after != null) out.add(Move.drop(x, y));
                }
            }
            return out;
        }

        /**
         * 在 (x,y) 落子并结算提子；非法（自杀 / 无气）返回 null。
         *
         * <p>返回值里不含劫争信息——劫争由 {@code GameSession} 用「上一手」比较来禁，
         * 这样规则实现不必保存历史。</p>
         */
        static int[] play(int[] board, int x, int y, int side) {
            if (!GameRules.inside(N, N, x, y) || board[y * N + x] != 0) return null;
            int[] b = board.clone();
            b[y * N + x] = side;
            // 先提对方的死子
            int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : nb) {
                int nx = x + d[0], ny = y + d[1];
                if (!GameRules.inside(N, N, nx, ny)) continue;
                if (Integer.signum(b[ny * N + nx]) == -side) {
                    if (liberties(b, nx, ny) == 0) {
                        removeGroup(b, nx, ny);
                    }
                }
            }
            // 再看自己有没有气（没有就是自杀，非法）
            if (liberties(b, x, y) == 0) return null;
            return b;
        }

        /** 这一串棋的气数。 */
        static int liberties(int[] b, int x, int y) {
            int v = b[y * N + x];
            if (v == 0) return 0;
            boolean[] seen = new boolean[N * N];
            boolean[] lib = new boolean[N * N];
            java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
            stack.push(y * N + x);
            seen[y * N + x] = true;
            int count = 0;
            while (!stack.isEmpty()) {
                int id = stack.pop();
                int cx = id % N, cy = id / N;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = cx + d[0], ny = cy + d[1];
                    if (!GameRules.inside(N, N, nx, ny)) continue;
                    int nid = ny * N + nx;
                    if (b[nid] == 0) {
                        if (!lib[nid]) {
                            lib[nid] = true;
                            count++;
                        }
                    } else if (b[nid] == v && !seen[nid]) {
                        seen[nid] = true;
                        stack.push(nid);
                    }
                }
            }
            return count;
        }

        static void removeGroup(int[] b, int x, int y) {
            int v = b[y * N + x];
            if (v == 0) return;
            java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
            stack.push(y * N + x);
            b[y * N + x] = 0;
            while (!stack.isEmpty()) {
                int id = stack.pop();
                int cx = id % N, cy = id / N;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = cx + d[0], ny = cy + d[1];
                    if (!GameRules.inside(N, N, nx, ny)) continue;
                    int nid = ny * N + nx;
                    if (b[nid] == v) {
                        b[nid] = 0;
                        stack.push(nid);
                    }
                }
            }
        }

        @Override
        public int[] apply(int[] board, Move m, int side) {
            int[] r = play(board, m.tx(), m.ty(), side);
            return r == null ? board.clone() : r;
        }

        @Override
        public int result(int[] board, int side, int movesWithoutProgress) {
            // 注意：围棋的「无合法点」= 只剩自杀点或被填满，需要生成全部着法才能判，
            // 因此和象棋一样留到 GameSession.play 里判一次。
            // 这里只看棋盘是否真的一个空点都不剩（最直接的终局条件）。
            for (int v : board) {
                if (v == 0) return 0;
            }
            int s = score(board);
            return s > 0 ? 1 : (s < 0 ? -1 : 2);
        }

        /** 简化数目：子 + 只有一方能到达的空点。 */
        static int score(int[] board) {
            int s = 0;
            for (int v : board) s += Integer.signum(v);
            boolean[] seen = new boolean[N * N];
            for (int y = 0; y < N; y++) {
                for (int x = 0; x < N; x++) {
                    int id = y * N + x;
                    if (board[id] != 0 || seen[id]) continue;
                    // 洪水填出一块空区，看它挨着谁
                    java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
                    List<Integer> region = new ArrayList<>();
                    stack.push(id);
                    seen[id] = true;
                    boolean touchBlack = false, touchWhite = false;
                    while (!stack.isEmpty()) {
                        int cur = stack.pop();
                        region.add(cur);
                        int cx = cur % N, cy = cur / N;
                        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                            int nx = cx + d[0], ny = cy + d[1];
                            if (!GameRules.inside(N, N, nx, ny)) continue;
                            int nid = ny * N + nx;
                            if (board[nid] > 0) touchBlack = true;
                            else if (board[nid] < 0) touchWhite = true;
                            else if (!seen[nid]) {
                                seen[nid] = true;
                                stack.push(nid);
                            }
                        }
                    }
                    if (touchBlack && !touchWhite) s += region.size();
                    else if (touchWhite && !touchBlack) s -= region.size();
                }
            }
            return s;
        }

        @Override
        public int evaluate(int[] board, int side) {
            int s = score(board);
            return side > 0 ? s * 100 : -s * 100;
        }

        @Override
        public boolean piecesOnIntersections() {
            return true;   // 围棋的子落在交叉点上
        }

        @Override
        public String glyph(int piece) {
            if (piece == 0) return "";
            return piece > 0 ? "\u25cf" : "\u25cb";
        }

        @Override
        public long aiThinkMs() {
            return 350;
        }
    }
}
