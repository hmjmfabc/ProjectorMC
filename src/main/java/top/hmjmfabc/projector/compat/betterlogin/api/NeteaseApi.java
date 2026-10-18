package top.hmjmfabc.projector.compat.betterlogin.api;

import top.hmjmfabc.projector.compat.betterlogin.util.HashUtil;
import top.hmjmfabc.projector.compat.netmusic.api.NetWorker;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class NeteaseApi {
    private final Map<String, String> requestPropertyData = new HashMap<>() {{
        put("Content-Type", "application/x-www-form-urlencoded");
        put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.1.6");
    }};

    public String getPlayInfo(long musicId, NeteaseMusicLevel level) throws Exception {
        String url = String.format("https://music.163.com/api/song/enhance/player/url/v1?encodeType=flac&ids=[%d]&level=%s", musicId, level.toString().toLowerCase());
        return NetWorker.get(url, getRequestPropertyData());
    }

    public Map<String, String> getRequestPropertyData() {
        return requestPropertyData;
    }

    public void setCookie(String cookie) {
        getRequestPropertyData().put("Cookie", cookie);
    }

    public String getQRKey() throws IOException {
        String url = "https://music.163.com/api/login/qrcode/unikey?type=3";
        return BetterNetWorker.get(url, getRequestPropertyData()).body;
    }

    public BetterNetWorker.HttpResponse checkQRLoginStatus(String key) throws IOException {
        String url = String.format("https://music.163.com/api/login/qrcode/client/login?key=%s&type=3", key);
        return BetterNetWorker.get(url, getRequestPropertyData());
    }

    public String sendCaptcha(String phone) throws IOException {
        String url = String.format("https://music.163.com/api/sms/captcha/sent?cellphone=%s&ctcode=86", phone);
        return NetWorker.get(url, getRequestPropertyData());
    }

    public BetterNetWorker.HttpResponse phoneCaptchaLogin(String phone, String captcha) throws IOException {
        String url = String.format("https://music.163.com/api/w/login/cellphone?phone=%s&countrycode=86&rememberLogin=true&captcha=%s&https=true&type=1", phone, captcha);
        return BetterNetWorker.get(url, getRequestPropertyData());
    }

    public BetterNetWorker.HttpResponse emailLogin(String email, String password) throws IOException {
        String url = String.format("https://music.163.com/api/w/login/?username=%s&rememberLogin=true&password=%s&https=true&type=0", email, HashUtil.md5(password));
        return BetterNetWorker.get(url, getRequestPropertyData());
    }
}
