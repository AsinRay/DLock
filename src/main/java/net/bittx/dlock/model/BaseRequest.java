package net.bittx.dlock.model;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

public class BaseRequest implements Serializable {

    @NotNull(message = "渠道不能为空！")
    private String channel;


    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    @Override
    public String toString() {
        return "BaseRequest{" +
                "channel='" + channel + '\'' +
                '}';
    }
}
