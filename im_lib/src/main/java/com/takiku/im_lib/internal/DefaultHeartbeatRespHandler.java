package com.takiku.im_lib.internal;

import com.google.protobuf.GeneratedMessageV3;
import com.takiku.im_lib.internal.handler.listener.MessageHandler;
import com.takiku.im_lib.protobuf.PackProtobuf;

/**
 * 心跳包响应处理
 */
public class DefaultHeartbeatRespHandler implements MessageHandler<PackProtobuf.Pack> {
    public static final int HEART_REPLY_TYPE=0x11;//与服务端保持类型统一


    @Override
    public boolean isFocusMsg(Object msg) {
        PackProtobuf.Pack pack= (PackProtobuf.Pack) msg;
        return pack.getPackType()==PackProtobuf.Pack.PackType.REPLY&&pack.getReply().getReplyType()==HEART_REPLY_TYPE;
    }

    @Override
    public void handleMsg(PackProtobuf.Pack pack) {//TODO 实现心跳包响应处理

    }


}
