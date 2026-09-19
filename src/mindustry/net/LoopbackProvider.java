package mindustry.net;

import arc.Core;
import java.net.InetSocketAddress;

/** v160.4 adapter: retain ArcNet framing/serialization/listeners; disable UDP/discovery.
 * Reflective field access is version-pinned: mod and engine have separate classloaders. No ServerSocket fallback. */
public final class LoopbackProvider extends ArcNetProvider {
    private final arc.net.Server localServer;
    private final arc.net.Client localClient;
    public LoopbackProvider(){
        try{
            var s=ArcNetProvider.class.getDeclaredField("server");s.setAccessible(true);localServer=(arc.net.Server)s.get(this);
            var c=ArcNetProvider.class.getDeclaredField("client");c.setAccessible(true);localClient=(arc.net.Client)c.get(this);
        }catch(ReflectiveOperationException e){throw new IllegalStateException("v1604-provider-fields",e);}
    }
    @Override public void hostServer(int port) throws java.io.IOException {
        check(port);
        localServer.bind(new InetSocketAddress("127.0.0.1",port), null);
        Thread serverThread=new Thread(localServer,"SC5 ArcNet server");
        serverThread.setDaemon(true);serverThread.start();
    }
    @Override public void connectClient(String host,int port,Runnable done) {
        check(port);
        if(!host.equals("127.0.0.1")) throw new IllegalArgumentException("loopback-only");
        localClient.stop();
        Thread update=new Thread(localClient,"SC5 ArcNet client");update.setDaemon(true);update.start();
        Thread connect=new Thread(() -> {
            try {localClient.connect(5000,host,port);Core.app.post(done);}
            catch(Exception e){System.out.println("SC_M5_CONNECT_ERROR "+e);}
        },"SC5 connect");connect.setDaemon(true);connect.start();
    }
    private static void check(int p){if(p<41000||p>41999)throw new IllegalArgumentException("port");}
    @Override public void discoverServers(arc.func.Cons<Host> c,Runnable r){throw new IllegalArgumentException("discovery-disabled");}
    @Override public void pingHost(String h,int p,arc.func.Cons<Host> c,arc.func.Cons<Exception> e){throw new IllegalArgumentException("discovery-disabled");}
}
