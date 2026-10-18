package top.hmjmfabc.projector.compat.betterlogin.api.pojo;

import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Optional;

public class NetEaseMusicPlayInfo {

    @SerializedName("code")
    private int code;

    @SerializedName("data")
    private List<PlayInfo> data;

    public static class PlayInfo {
        @SerializedName("id")
        private long id;

        @SerializedName("url")
        private String url;

        @SerializedName("time")
        private int time;

        @SerializedName("code")
        private int code;

        @SerializedName("podcastCtrp")
        private String podcastCtrp;

        public long getId() {
            return id;
        }

        public String getUrl() {
            return url;
        }

        public int getTime() {
            return time;
        }

        public int getCode() {
            return code;
        }

        public String getPodcastCtrp() {
            return podcastCtrp;
        }
    }

    public Optional<PlayInfo> getPlayInfo() {
        return Optional.ofNullable(data)
                .map(list -> list.get(0));
    }
}
