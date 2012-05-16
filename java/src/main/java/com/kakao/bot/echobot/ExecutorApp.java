package com.kakao.bot.echobot;

import static org.jboss.netty.channel.Channels.pipeline;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.oio.OioClientSocketChannelFactory;
import org.jboss.netty.handler.execution.ExecutionHandler;
import org.jboss.netty.handler.execution.OrderedMemoryAwareThreadPoolExecutor;
import org.jboss.netty.util.HashedWheelTimer;
import org.jboss.netty.util.Timer;

/**
 * Biz Logic을 태울 경우에 사용함!!
 * MINA의 2중 Thread Pool Filter 개념을 차용(executionHandler를 2중으로 사용함)
 *   -> 실제 Nio에서는 Boss TP 1개, Worker TP 1개, OMA TP * 2개임
 * 성능 테스트시에 상황에 맞게 튜닝 필요 
 * 추가정보: JCO 2009년  발표자료, 자바 네트워킹 기초에서 응용까지, http://gleamynode.net/file_download/11/JCO2009.pdf
 */
public class ExecutorApp {

  private static String host = "localhost";
  private static int port = 11111;

  /**
   * OioClientSocketChannelFactory는 request가 많이 몰릴 경우 Live-Lock 현상이 발생했음
   * NioClientSocketChannelFactory를 이용하면 Live-Lock 문제를 해결할 수 있음
   */
  private static final ClientSocketChannelFactory clientSocketChannel = newClientSocketChannelFactory (true);

  private static ClientSocketChannelFactory newClientSocketChannelFactory (boolean nio) {
    if ( nio ) {
      return new NioClientSocketChannelFactory(Executors.newCachedThreadPool(),Executors.newCachedThreadPool());
    }
    return new OioClientSocketChannelFactory ( Executors.newCachedThreadPool() );
  }

  public static void main(String[] args) {

    if (args.length > 1) {
      host = args[0];
      System.out.println(host);
      port = Integer.parseInt(args[1]);
      System.out.println(port);
    }
    createClient();
  }


  private static void createClient() {
    final ClientBootstrap bootstrap = new ClientBootstrap(clientSocketChannel);
    final Timer timer = new HashedWheelTimer();

    //상황에 따라 조절 필요~
    final int maxMemory = 10*1024*1024;
    final int coreThreadCnt = 8;

    final OrderedMemoryAwareThreadPoolExecutor executor = 
        new OrderedMemoryAwareThreadPoolExecutor( coreThreadCnt, maxMemory, maxMemory);
    final ExecutionHandler executionHandler = new ExecutionHandler(executor);

    final OrderedMemoryAwareThreadPoolExecutor executor2 = 
        new OrderedMemoryAwareThreadPoolExecutor( coreThreadCnt, maxMemory, maxMemory);
    final ExecutionHandler executionHandler2 = new ExecutionHandler(executor2);

    bootstrap.setOption("connectTimeoutMillis", 1000);
    /**
     * tcpNoDelay 옵션, true: Nagle Algorithm을 사용하지 않음, false: Nagle Algorithm을 사용함
     * 작은 패킷에 대해서 응답을 빠르게 줘야 할 경우(채팅 서버, 게임서버 등은 true로 사용하는게 낳음)
     * Nagle Algorithm은 작은 패킷을 모아서 한번에 보내주는 것으로 latency이 조금 있더라도 overal performance측면에서 좋음
     */
    bootstrap.setOption("tcpNoDelay", true);
    /**
     * KeepAlive는 종단 시스템중 하나가 다운될 때 발생할 수 있는 한쪽만 열린 상태를 정리하는 것 
     *  - Checking for dead peers
     *  - Preventing disconnection due to network inactivity
     */
    bootstrap.setOption("keepAlive", true);
    /**
     * reuseAddress는 클라이언트 접속시 할당되는 Port를 기다림없이 재활용하고 싶을때
     */
    bootstrap.setOption("reuseAddress", true);
    bootstrap.setOption("sendBufferSize",    20*1024*1024);   // 20MB, 상황에 따라 조절     
    bootstrap.setOption("receiveBufferSize", 20*1024*1024);   // 20MB, 상황에 따라 조절
    bootstrap.setOption("remoteAddress",    new InetSocketAddress(host, port));


    bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
      public ChannelPipeline getPipeline() throws Exception {
        ChannelPipeline pipeline = pipeline();
        pipeline.addLast("executor",      executionHandler);
        pipeline.addLast("uptimeClient",  new UptimeClientHandler(bootstrap, timer, 3000));   // connection 이 끊어졌는지 체크!!

        pipeline.addLast("decoder",       new BsonDecoder());
        pipeline.addLast("encoder",       new BsonEncoder());

        pipeline.addLast("executor2",     executionHandler2);
        // 1분에 한번씩 ping check
        pipeline.addLast("pingpong",      new PingPongHandler(bootstrap, timer, 60*1000));    

        // 10ms뒤에 응답을 주는 Echo
        pipeline.addLast("handler",       new EchoBotHandler(bootstrap, timer, 10,"tayobot", "pass")); 
        return pipeline;
      }
    });

    bootstrap.setOption("remoteAddress", new InetSocketAddress(host, port));
    bootstrap.connect();
  }
}
