package top.hmjmfabc.projector.common.game;

/**
 * 【⑪】按棋种编号取出规则实现。
 *
 * <p>所有实现类都是包内可见的（{@code final class}），只通过这个工厂暴露出去——
 * 外部拿到的永远是 {@link GameRules} 接口，这样以后想换实现、
 * 或者给某个棋种加缓存，都不需要动调用方。</p>
 */
public final class GameRulesFactory {

    private GameRulesFactory() {
    }

    public static GameRules create(int kind) {
        return switch (GameKind.clamp(kind)) {
            case GameKind.GOMOKU -> new GamesDrop.Gomoku();
            case GameKind.XIANGQI -> new GamesPiece.Xiangqi();
            case GameKind.GO -> new GamesDrop.Go();
            case GameKind.CHESS -> new GamesPiece.Chess();
            default -> new GamesDrop.TicTacToe();
        };
    }
}
