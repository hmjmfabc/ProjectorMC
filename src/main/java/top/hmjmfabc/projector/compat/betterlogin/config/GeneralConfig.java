package top.hmjmfabc.projector.compat.betterlogin.config;

import top.hmjmfabc.projector.compat.betterlogin.api.NeteaseMusicLevel;

/**
 * 【27.2.2】BetterLogin 的配置：**改成投影仪配置的代理**。
 *
 * <p>原版它自己存一份 {@code config/netmusicbetterlogin-common.toml}（Cookie + 音质等级），
 * 那样投影仪的音乐模块读不到登录结果 —— 登录就白登了。这里两个键都落到投影仪自己的配置上：</p>
 * <ul>
 *   <li>Cookie ⇒ {@code music.neteaseCookie}（音乐模块本来就用它取高码率/VIP）</li>
 *   <li>音质等级 ⇒ {@code music.neteaseLevel}（本轮新增）</li>
 * </ul>
 * <p>于是「登录一次 → 立刻生效」，也不必再维护第二份配置。</p>
 */
public final class GeneralConfig {

    private GeneralConfig() {
    }

    /** 读取当前 Cookie（投影仪配置）。 */
    public static String getCookie() {
        try {
            String c = top.hmjmfabc.projector.ProjectorConfig.INSTANCE.musicNeteaseCookie.get();
            return c == null ? "" : c;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 写入 Cookie（登录成功后调用）。 */
    public static void setCookie(String cookie) {
        try {
            top.hmjmfabc.projector.ProjectorConfig.INSTANCE.musicNeteaseCookie.set(
                    cookie == null ? "" : cookie);
            top.hmjmfabc.projector.ProjectorConfig.INSTANCE.musicNeteaseCookie.save();
        } catch (Throwable t) {
            top.hmjmfabc.projector.Projector.LOGGER.warn(
                    "[Projector][音乐] 保存网易云 Cookie 失败：{}", String.valueOf(t));
        }
    }

    /** 读取音质等级。 */
    public static NeteaseMusicLevel getLevel() {
        try {
            String v = top.hmjmfabc.projector.ProjectorConfig.INSTANCE.musicNeteaseLevel.get();
            return NeteaseMusicLevel.valueOf(v.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Throwable t) {
            return NeteaseMusicLevel.HIGHER;
        }
    }

    /** 写入音质等级。 */
    public static void setLevel(NeteaseMusicLevel level) {
        try {
            top.hmjmfabc.projector.ProjectorConfig.INSTANCE.musicNeteaseLevel.set(
                    (level == null ? NeteaseMusicLevel.HIGHER : level).name());
            top.hmjmfabc.projector.ProjectorConfig.INSTANCE.musicNeteaseLevel.save();
        } catch (Throwable t) {
            top.hmjmfabc.projector.Projector.LOGGER.warn(
                    "[Projector][音乐] 保存音质等级失败：{}", String.valueOf(t));
        }
    }
}
