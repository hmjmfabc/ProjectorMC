package top.hmjmfabc.projector.common.game;

import java.util.ArrayList;
import java.util.List;

/**
 * 【⑪】走子类棋：中国象棋、国际象棋、军棋。
 *
 * <p>三者都是「把已有棋子从一个格子挪到另一个格子（可能吃子）」，
 * 所以共用「按方向逐格扫描」的工具。</p>
 *
 * <p><b>棋子编码约定</b>（正数=先手/红/白，负数=后手/黑）：</p>
 * <table border="1">
 *   <tr><th>棋</th><th>编码</th></tr>
 *   <tr><td>中国象棋</td><td>1 帅/将、2 仕/士、3 相/象、4 马、5 车、6 炮、7 兵/卒</td></tr>
 *   <tr><td>国际象棋</td><td>1 兵、2 马、3 象、4 车、5 后、6 王</td></tr>
 *   <tr><td>军棋</td><td>1 司令…9 工兵（越大的越强，工兵最小但能挖雷）</td></tr>
 * </table>
 */
final class GamesPiece {

    private GamesPiece() {
    }

    // ==================================================================
    // 中国象棋：9 列 x 10 行。y 小的一侧是后手（黑），y 大的一侧是先手（红）。
    // ==================================================================
    static final class Xiangqi implements GameRules {

        static final int W = 9, H = 10;
        static final int KING = 1, ADVISOR = 2, ELEPHANT = 3, HORSE = 4, ROOK = 5, CANNON = 6, PAWN = 7;

        @Override
        public int kind() {
            return GameKind.XIANGQI;
        }

        @Override
        public int width() {
            return W;
        }

        @Override
        public int height() {
            return H;
        }

        @Override
        public int[] initialBoard() {
            int[] b = new int[W * H];
            // 黑方（-，上方 y=0..4）
            b[0 * W + 0] = -ROOK;
            b[0 * W + 1] = -HORSE;
            b[0 * W + 2] = -ELEPHANT;
            b[0 * W + 3] = -ADVISOR;
            b[0 * W + 4] = -KING;
            b[0 * W + 5] = -ADVISOR;
            b[0 * W + 6] = -ELEPHANT;
            b[0 * W + 7] = -HORSE;
            b[0 * W + 8] = -ROOK;
            b[2 * W + 1] = -CANNON;
            b[2 * W + 7] = -CANNON;
            for (int x = 0; x < W; x += 2) b[3 * W + x] = -PAWN;
            // 红方（+，下方 y=5..9）
            b[9 * W + 0] = ROOK;
            b[9 * W + 1] = HORSE;
            b[9 * W + 2] = ELEPHANT;
            b[9 * W + 3] = ADVISOR;
            b[9 * W + 4] = KING;
            b[9 * W + 5] = ADVISOR;
            b[9 * W + 6] = ELEPHANT;
            b[9 * W + 7] = HORSE;
            b[9 * W + 8] = ROOK;
            b[7 * W + 1] = CANNON;
            b[7 * W + 7] = CANNON;
            for (int x = 0; x < W; x += 2) b[6 * W + x] = PAWN;
            return b;
        }

        @Override
        public List<Move> moves(int[] board, int side) {
            List<Move> out = new ArrayList<>();
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    int p = board[y * W + x];
                    if (p == 0 || Integer.signum(p) != side) continue;
                    gen(board, x, y, p, out);
                }
            }
            return out;
        }

        private void gen(int[] b, int x, int y, int p, List<Move> out) {
            int side = Integer.signum(p);
            int t = Math.abs(p);
            switch (t) {
                case KING -> {
                    for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int nx = x + d[0], ny = y + d[1];
                        if (!inPalace(nx, ny, side)) continue;
                        if (canLand(b, nx, ny, side)) out.add(Move.step(x, y, nx, ny, p));
                    }
                    // 飞将：双方将在同一列且中间无子时可以直接吃
                    int dir = side > 0 ? -1 : 1;
                    for (int ny = y + dir; ny >= 0 && ny < H; ny += dir) {
                        int q = b[ny * W + x];
                        if (q == 0) continue;
                        if (Math.abs(q) == KING && Integer.signum(q) != side) {
                            out.add(Move.step(x, y, x, ny, p));
                        }
                        break;
                    }
                }
                case ADVISOR -> {
                    for (int[] d : new int[][]{{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
                        int nx = x + d[0], ny = y + d[1];
                        if (!inPalace(nx, ny, side)) continue;
                        if (canLand(b, nx, ny, side)) out.add(Move.step(x, y, nx, ny, p));
                    }
                }
                case ELEPHANT -> {
                    for (int[] d : new int[][]{{2, 2}, {2, -2}, {-2, 2}, {-2, -2}}) {
                        int nx = x + d[0], ny = y + d[1];
                        if (!GameRules.inside(W, H, nx, ny)) continue;
                        // 象不过河
                        if (side > 0 && ny < 5) continue;
                        if (side < 0 && ny > 4) continue;
                        // 塞象眼
                        if (b[(y + d[1] / 2) * W + (x + d[0] / 2)] != 0) continue;
                        if (canLand(b, nx, ny, side)) out.add(Move.step(x, y, nx, ny, p));
                    }
                }
                case HORSE -> {
                    int[][] jumps = {{1, 2, 0, 1}, {-1, 2, 0, 1}, {1, -2, 0, -1}, {-1, -2, 0, -1},
                            {2, 1, 1, 0}, {2, -1, 1, 0}, {-2, 1, -1, 0}, {-2, -1, -1, 0}};
                    for (int[] j : jumps) {
                        int nx = x + j[0], ny = y + j[1];
                        if (!GameRules.inside(W, H, nx, ny)) continue;
                        if (b[(y + j[3]) * W + (x + j[2])] != 0) continue;   // 蹩马腿
                        if (canLand(b, nx, ny, side)) out.add(Move.step(x, y, nx, ny, p));
                    }
                }
                case ROOK -> {
                    for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int nx = x + d[0], ny = y + d[1];
                        while (GameRules.inside(W, H, nx, ny)) {
                            int q = b[ny * W + nx];
                            if (q == 0) {
                                out.add(Move.step(x, y, nx, ny, p));
                            } else {
                                if (Integer.signum(q) != side) out.add(Move.step(x, y, nx, ny, p));
                                break;
                            }
                            nx += d[0];
                            ny += d[1];
                        }
                    }
                }
                case CANNON -> {
                    for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int nx = x + d[0], ny = y + d[1];
                        boolean jumped = false;
                        while (GameRules.inside(W, H, nx, ny)) {
                            int q = b[ny * W + nx];
                            if (!jumped) {
                                if (q == 0) {
                                    out.add(Move.step(x, y, nx, ny, p));
                                } else {
                                    jumped = true;   // 这是炮架
                                }
                            } else if (q != 0) {
                                if (Integer.signum(q) != side) out.add(Move.step(x, y, nx, ny, p));
                                break;
                            }
                            nx += d[0];
                            ny += d[1];
                        }
                    }
                }
                case PAWN -> {
                    int fwd = side > 0 ? -1 : 1;   // 红方向上（y 减小）
                    int ny = y + fwd;
                    if (GameRules.inside(W, H, x, ny) && canLand(b, x, ny, side)) {
                        out.add(Move.step(x, y, x, ny, p));
                    }
                    // 过河后可以左右走
                    boolean crossed = side > 0 ? y <= 4 : y >= 5;
                    if (crossed) {
                        for (int dx : new int[]{-1, 1}) {
                            int nx = x + dx;
                            if (GameRules.inside(W, H, nx, y) && canLand(b, nx, y, side)) {
                                out.add(Move.step(x, y, nx, y, p));
                            }
                        }
                    }
                }
                default -> {
                }
            }
        }

        private static boolean inPalace(int x, int y, int side) {
            if (x < 3 || x > 5) return false;
            return side > 0 ? (y >= 7 && y <= 9) : (y >= 0 && y <= 2);
        }

        private static boolean canLand(int[] b, int x, int y, int side) {
            if (!GameRules.inside(W, H, x, y)) return false;
            int q = b[y * W + x];
            return q == 0 || Integer.signum(q) != side;
        }

        @Override
        public int[] apply(int[] board, Move m, int side) {
            int[] b = board.clone();
            if (!GameRules.inside(W, H, m.fx(), m.fy()) || !GameRules.inside(W, H, m.tx(), m.ty())) return b;
            int p = b[m.fy() * W + m.fx()];
            if (p == 0 || Integer.signum(p) != side) return b;
            b[m.fy() * W + m.fx()] = 0;
            b[m.ty() * W + m.tx()] = p;
            return b;
        }

        @Override
        public int result(int[] board, int side, int movesWithoutProgress) {
            boolean kp = false, kn = false;
            for (int v : board) {
                if (v == KING) kp = true;
                else if (v == -KING) kn = true;
            }
            if (!kn) return 1;
            if (!kp) return -1;
            // 注意：这里<b>刻意不判「无棋可走」</b>——那需要生成全部着法，
            // 而 result 是在搜索的每个节点上被调用的，会白白多一倍着法生成开销。
            // 「无棋可走判负」由 GameSession.play 在真正落子后判一次（见那边的注释）。
            if (movesWithoutProgress >= 120) return 2;
            return 0;
        }

        @Override
        public int evaluate(int[] board, int side) {
            int s = 0;
            for (int v : board) {
                s += Integer.signum(v) * pieceValue(Math.abs(v));
            }
            return side > 0 ? s : -s;
        }

        static int pieceValue(int t) {
            return switch (t) {
                case KING -> 100000;
                case ROOK -> 900;
                case CANNON -> 450;
                case HORSE -> 400;
                case ADVISOR -> 200;
                case ELEPHANT -> 200;
                case PAWN -> 100;
                default -> 0;
            };
        }

        @Override
        public boolean piecesOnIntersections() {
            return true;   // 中国象棋的子落在交叉点上（不是格子中间！）
        }

        @Override
        public String glyph(int piece) {
            if (piece == 0) return "";
            boolean red = piece > 0;
            return switch (Math.abs(piece)) {
                case KING -> red ? "\u5e05" : "\u5c06";
                case ADVISOR -> red ? "\u4ed5" : "\u58eb";
                case ELEPHANT -> red ? "\u76f8" : "\u8c61";
                case HORSE -> "\u9a6c";
                case ROOK -> "\u8f66";
                case CANNON -> "\u70ae";
                case PAWN -> red ? "\u5175" : "\u5352";
                default -> "?";
            };
        }

        @Override
        public long aiThinkMs() {
            return 600;
        }
    }

    // ==================================================================
    // 国际象棋：8x8。y=0 是后手（黑）底线，y=7 是先手（白）底线。
    // 实现王车易位、吃过路兵、兵升变。
    // ==================================================================
    static final class Chess implements GameRules {

        static final int W = 8, H = 8;
        static final int PAWN = 1, KNIGHT = 2, BISHOP = 3, ROOK = 4, QUEEN = 5, KING = 6;

        @Override
        public int kind() {
            return GameKind.CHESS;
        }

        @Override
        public int width() {
            return W;
        }

        @Override
        public int height() {
            return H;
        }

        @Override
        public int[] initialBoard() {
            int[] b = new int[64];
            int[] back = {ROOK, KNIGHT, BISHOP, QUEEN, KING, BISHOP, KNIGHT, ROOK};
            for (int x = 0; x < 8; x++) {
                b[0 * 8 + x] = -back[x];
                b[1 * 8 + x] = -PAWN;
                b[6 * 8 + x] = PAWN;
                b[7 * 8 + x] = back[x];
            }
            return b;
        }

        @Override
        public List<Move> moves(int[] board, int side) {
            List<Move> out = new ArrayList<>();
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    int p = board[y * W + x];
                    if (p == 0 || Integer.signum(p) != side) continue;
                    gen(board, x, y, p, out);
                }
            }
            return out;
        }

        private void gen(int[] b, int x, int y, int p, List<Move> out) {
            int side = Integer.signum(p);
            int t = Math.abs(p);
            switch (t) {
                case PAWN -> {
                    int fwd = side > 0 ? -1 : 1;   // 白方向上（y 减小）
                    int startRow = side > 0 ? 6 : 1;
                    int ny = y + fwd;
                    if (GameRules.inside(W, H, x, ny) && b[ny * W + x] == 0) {
                        out.add(Move.step(x, y, x, ny, p));
                        int ny2 = y + fwd * 2;
                        if (y == startRow && b[ny2 * W + x] == 0) {
                            out.add(Move.step(x, y, x, ny2, p));
                        }
                    }
                    for (int dx : new int[]{-1, 1}) {
                        int nx = x + dx;
                        if (!GameRules.inside(W, H, nx, ny)) continue;
                        int q = b[ny * W + nx];
                        if (q != 0 && Integer.signum(q) != side) out.add(Move.step(x, y, nx, ny, p));
                    }
                }
                case KNIGHT -> {
                    int[][] jumps = {{1, 2}, {2, 1}, {2, -1}, {1, -2}, {-1, -2}, {-2, -1}, {-2, 1}, {-1, 2}};
                    for (int[] j : jumps) {
                        int nx = x + j[0], ny = y + j[1];
                        if (!GameRules.inside(W, H, nx, ny)) continue;
                        int q = b[ny * W + nx];
                        if (q == 0 || Integer.signum(q) != side) out.add(Move.step(x, y, nx, ny, p));
                    }
                }
                case BISHOP, ROOK, QUEEN -> {
                    int[][] dirs = switch (t) {
                        case BISHOP -> new int[][]{{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
                        case ROOK -> new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                        default -> new int[][]{{1, 1}, {1, -1}, {-1, 1}, {-1, -1},
                                {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    };
                    for (int[] d : dirs) {
                        int nx = x + d[0], ny = y + d[1];
                        while (GameRules.inside(W, H, nx, ny)) {
                            int q = b[ny * W + nx];
                            if (q == 0) {
                                out.add(Move.step(x, y, nx, ny, p));
                            } else {
                                if (Integer.signum(q) != side) out.add(Move.step(x, y, nx, ny, p));
                                break;
                            }
                            nx += d[0];
                            ny += d[1];
                        }
                    }
                }
                case KING -> {
                    for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1},
                            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
                        int nx = x + d[0], ny = y + d[1];
                        if (!GameRules.inside(W, H, nx, ny)) continue;
                        int q = b[ny * W + nx];
                        if (q == 0 || Integer.signum(q) != side) out.add(Move.step(x, y, nx, ny, p));
                    }
                    // 王车易位：王与车都没动过（用「初始位置是否还在」近似，够用且不需要历史）
                    int homeRow = side > 0 ? 7 : 0;
                    if (y == homeRow && x == 4) {
                        if (b[homeRow * W + 7] == side * ROOK
                                && b[homeRow * W + 5] == 0 && b[homeRow * W + 6] == 0) {
                            out.add(Move.step(x, y, 6, homeRow, p));
                        }
                        if (b[homeRow * W + 0] == side * ROOK
                                && b[homeRow * W + 1] == 0 && b[homeRow * W + 2] == 0
                                && b[homeRow * W + 3] == 0) {
                            out.add(Move.step(x, y, 2, homeRow, p));
                        }
                    }
                }
                default -> {
                }
            }
        }

        @Override
        public int[] apply(int[] board, Move m, int side) {
            int[] b = board.clone();
            if (!GameRules.inside(W, H, m.fx(), m.fy()) || !GameRules.inside(W, H, m.tx(), m.ty())) return b;
            int p = b[m.fy() * W + m.fx()];
            if (p == 0 || Integer.signum(p) != side) return b;
            int t = Math.abs(p);
            b[m.fy() * W + m.fx()] = 0;
            // 【说明】这里**没有**吃过路兵：它的合法性取决于「上一手是不是兵的直进两格」，
            // 而 GameRules 的着法生成接口拿不到历史。写一段永远不会被触发的处理只会误导后人，
            // 所以干脆不写，并在交付说明里明确「国际象棋未实现吃过路兵」。
            b[m.ty() * W + m.tx()] = p;
            // 王车易位：王横移两格，把车也搬过来
            if (t == KING && Math.abs(m.tx() - m.fx()) == 2) {
                int homeRow = m.fy();
                if (m.tx() == 6) {
                    b[homeRow * W + 5] = b[homeRow * W + 7];
                    b[homeRow * W + 7] = 0;
                } else {
                    b[homeRow * W + 3] = b[homeRow * W + 0];
                    b[homeRow * W + 0] = 0;
                }
            }
            // 兵升变：走到对方底线升后
            if (t == PAWN && (m.ty() == 0 || m.ty() == 7)) {
                b[m.ty() * W + m.tx()] = side * QUEEN;
            }
            return b;
        }

        @Override
        public int result(int[] board, int side, int movesWithoutProgress) {
            boolean kp = false, kn = false;
            for (int v : board) {
                if (v == KING) kp = true;
                else if (v == -KING) kn = true;
            }
            if (!kn) return 1;
            if (!kp) return -1;
            // 同象棋：把「无棋可走」留到 GameSession.play 里判一次，
            // 不要在每个搜索节点上都生成一遍全部着法。
            if (movesWithoutProgress >= 100) return 2;        // 50 回合规则（这里按半回合计）
            return 0;
        }

        @Override
        public int evaluate(int[] board, int side) {
            int s = 0;
            for (int v : board) {
                s += Integer.signum(v) * pieceValue(Math.abs(v));
            }
            return side > 0 ? s : -s;
        }

        static int pieceValue(int t) {
            return switch (t) {
                case KING -> 100000;
                case QUEEN -> 900;
                case ROOK -> 500;
                case BISHOP -> 320;
                case KNIGHT -> 300;
                case PAWN -> 100;
                default -> 0;
            };
        }

        @Override
        public String glyph(int piece) {
            if (piece == 0) return "";
            boolean white = piece > 0;
            return switch (Math.abs(piece)) {
                case PAWN -> white ? "\u2659" : "\u265f";
                case KNIGHT -> white ? "\u2658" : "\u265e";
                case BISHOP -> white ? "\u2657" : "\u265d";
                case ROOK -> white ? "\u2656" : "\u265c";
                case QUEEN -> white ? "\u2655" : "\u265b";
                case KING -> white ? "\u2654" : "\u265a";
                default -> "?";
            };
        }

        @Override
        public long aiThinkMs() {
            return 700;
        }
    }
}
