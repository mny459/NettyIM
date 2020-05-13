package com.takiku.im_lib.internal.connection;

import com.takiku.im_lib.codec.Codec;
import com.takiku.im_lib.client.IMClient;
import com.takiku.im_lib.dispatcher.Connection;
import com.takiku.im_lib.exception.AuthException;
import com.takiku.im_lib.internal.handler.internalhandler.HeartbeatChannelHandler;
import com.takiku.im_lib.internal.handler.internalhandler.MessageChannelHandler;
import com.takiku.im_lib.internal.MessageParser;
import com.takiku.im_lib.internal.handler.internalhandler.StatusChannelHandler;
import com.takiku.im_lib.listener.EventListener;
import com.takiku.im_lib.util.LRUMap;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.timeout.IdleStateHandler;


public class RealConnection  implements Connection {


    private Channel channel;
    private final ConnectionPool connectionPool;
    private Bootstrap bootstrap;
    private static volatile LRUMap<String,Object> subsequentLruMap; //后续消息回复，一般指，该消息被读、被撤回等状态
    private static volatile LRUMap<String,Object> ackLruMap;//消息被确认，即代表消息发送成功
    private com.google.protobuf.GeneratedMessageV3  heartBeatMsg;
    private boolean hasInit=false;
    private InetSocketAddress inetSocketAddress;
    private LinkedHashMap<String, ChannelHandler> handlers;
    private EventListener eventListener;
    private volatile boolean reConnect;
    private connectionBrokenListener connectionBrokenListener;
    private static volatile Map<String,OnSubsequentResponseListener> onSubsequentResponseListenerMap=new LRUMap<>(20);

    public RealConnection(ConnectionPool connectionPool, InetSocketAddress inetSocketAddress, EventListener eventListener){
        this.inetSocketAddress=inetSocketAddress;
        this.connectionPool=connectionPool;
        this.eventListener=eventListener;
        subsequentLruMap=new LRUMap(20);
        ackLruMap=new LRUMap<>(20);
        EventLoopGroup loopGroup = new NioEventLoopGroup(4);
        bootstrap = new Bootstrap();
        bootstrap.group(loopGroup).channel(NioSocketChannel.class);
        // 设置该选项以后，如果在两小时内没有数据的通信时，TCP会自动发送一个活动探测数据报文
        bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        // 设置禁用nagle算法
        bootstrap.option(ChannelOption.TCP_NODELAY, true);
        reConnect=true;
    }
    public void release(boolean reConnect){
        this.reConnect=reConnect;
        if (channel!=null){
            removeHandler(StatusChannelHandler.class.getSimpleName(),channel);
            removeHandler(HeartbeatChannelHandler.class.getSimpleName(),channel);
            removeHandler(MessageChannelHandler.class.getSimpleName(),channel);

            if (handlers!=null){
                for (String key : handlers.keySet()) {
                    removeHandler(key, channel);
                }
            }

            channel.close();
            channel=null;
        }
        if (bootstrap != null&&bootstrap.group()!=null) {
            bootstrap.group().shutdownGracefully();
        }
        connectionPool.destroyWorkLoopGroup();
        bootstrap=null;
        eventListener.connectionReleased(this);
        subsequentLruMap.clear();
        ackLruMap.clear();
        onSubsequentResponseListenerMap.clear();
        eventListener.connectionReleased(this);
    }
    /**
     * 移除指定handler
     *
     * @param handlerName
     */
    private void removeHandler(String handlerName, Channel channel) {
        try {
            if (channel.pipeline()!=null){
                if (channel.pipeline().get(handlerName) != null) {
                    channel.pipeline().remove(handlerName);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("移除handler失败，handlerName=" + handlerName);
        }
    }

    public void ChannelInitializerHandler(final Codec codec,
                                          final com.google.protobuf.GeneratedMessageV3 heartBeatMsg,
                                          final LinkedHashMap<String, ChannelHandler> handlers,
                                          final int heartbeatInterval,
                                          final MessageParser messageParser,
                                          final connectionBrokenListener connectionBrokenListener
                                         ) throws AuthException {
        if (hasInit){
            return;
        }
        this.handlers=handlers;
        this.heartBeatMsg=heartBeatMsg;
        this.connectionBrokenListener=connectionBrokenListener;
        bootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel ) throws Exception {
                ChannelPipeline pipeline = channel.pipeline();
                //解决tcp拆包、粘包
                pipeline.addLast("frameEncoder", new LengthFieldPrepender(2));
                pipeline.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(65535,
                        0, 2, 0, 2));
                //编解码支持
                if (codec==null){
                    throw new  IllegalArgumentException("codec is null");
                }
                messageParser.addShakeResultListener(new MessageParser.onShakeHandsResultListener() {
                    @Override
                    public void shakeHandsResult(boolean isSuccess) {
                        if (isSuccess){
                            if (heartBeatMsg!=null){ //握手成功且设置了心跳包，则里面启动心跳机制
                                addHeartbeatHandler(connectionPool,heartbeatInterval);
                            }
                        }else {
                            release(false);
                        }
                    }
                });
                messageParser.addSubsequentReplyListener(new MessageParser.onSubsequentReplyListener() {
                    @Override
                    public void subsequentReply(String tag, Object msg) {
                         putSubsequentMsg(tag,msg);
                    }
                });


                pipeline.addLast(codec.EnCoder().getClass().getSimpleName(),codec.EnCoder());
                pipeline.addLast(codec.DeCoder().getClass().getSimpleName(),codec.DeCoder());

                if (messageParser.getMessageShakeHandsHandler()==null){ //如果没有握手消息且设置了心跳包，则直接添加心跳机制，否则等握手成功后添加心跳机制
                    if (heartBeatMsg!=null){
//                        // 3次心跳没响应，代表连接已断开
                        addHeartbeatHandler(connectionPool,heartbeatInterval);

                    }
                }
                pipeline.addLast(StatusChannelHandler.class.getSimpleName(),new StatusChannelHandler(eventListener,connectionBrokenListener));
                pipeline.addLast(MessageChannelHandler.class.getSimpleName(),new MessageChannelHandler(messageParser));
                if (handlers!=null){
                    for (String key : handlers.keySet()) {
                        pipeline.addLast(key, handlers.get(key));
                    }
                }
                hasInit=true;
            }
        });
    }
    public void addHeartbeatHandler(ConnectionPool connectionPool,int heartbeatInterval) {
        if (channel == null || channel.pipeline() == null) {
            return;
        }
        try {
            // 之前存在的读写超时handler，先移除掉，再重新添加
            if (channel.pipeline().get(IdleStateHandler.class.getSimpleName()) != null) {
                channel.pipeline().remove(IdleStateHandler.class.getSimpleName());
            }
            // 3次心跳时间内没得到服务端响应，即可代表连接已断开
            channel.pipeline().addFirst(IdleStateHandler.class.getSimpleName(), new IdleStateHandler(
                    heartbeatInterval * 3, heartbeatInterval, 0, TimeUnit.MILLISECONDS));

            // 重新添加HeartbeatHandler
            if (channel.pipeline().get(HeartbeatChannelHandler.class.getSimpleName()) != null) {
                channel.pipeline().remove(HeartbeatChannelHandler.class.getSimpleName());
            }
            if (channel.pipeline().get(IdleStateHandler.class.getSimpleName()) != null) {
                channel.pipeline().addLast(HeartbeatChannelHandler.class.getSimpleName(),
                        new HeartbeatChannelHandler(connectionPool,heartBeatMsg,connectionBrokenListener));
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("添加心跳消息管理handler失败，reason：" + e.getMessage());
        }
    }


    @Override
    public Channel channel() {
        return channel;
    }

    @Override
    public InetSocketAddress InetSocketAddress() {
        return inetSocketAddress;
    }


    public void connect( int connectTimeout) throws InterruptedException {
          eventListener.connectStart(inetSocketAddress);
        // 设置连接超时时长
          bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout);
           String ip;
          if (inetSocketAddress.getAddress()!=null){
              ip= inetSocketAddress.getAddress().getHostAddress();
          }else {
              ip=inetSocketAddress.getHostName();
          }
          int port=inetSocketAddress.getPort();
         channel=  bootstrap.connect(ip,port).sync().channel();
    }
    public boolean isHealth(){
      if (channel!=null&&channel.isActive()){
          return true;
      }else {
          return false;
      }
    }

    public TcpStream newStream(IMClient client,StreamAllocation streamAllocation){
        TcpStream tcpStream=new Stream(client,streamAllocation,channel);
        return tcpStream;
    }



    public  static  LRUMap<String,Object> ackMsgLruMap(){
        return ackLruMap;
    }


    private  void  putAck(String tag,Object o) throws IOException {
        synchronized (ackMsgLruMap()){
            ackLruMap.put(tag,o);
        }
    }
    private  void putSubsequentMsg(String tag,Object o){
      if (onSubsequentResponseListenerMap.containsKey(tag)){
          onSubsequentResponseListenerMap.get(tag).onSubsequentResponseArrive(tag,o);
      }
    }



    /**
     * 注册后续消息状态的监听，例如已读、撤回、等状态
     * @param tag
     * @param listener
     */
    public void registerSubsequentResponse(String tag,OnSubsequentResponseListener listener){
            onSubsequentResponseListenerMap.put(tag,listener);

    }
    public boolean isReConnect(){
        return reConnect;
    }

   public   interface connectionBrokenListener{
        void connectionBroken();
    }

    public interface OnSubsequentResponseListener{
       void onSubsequentResponseArrive(String tag,Object o);
    }


}
