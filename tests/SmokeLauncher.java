package fixture;
import java.nio.file.Path;
import java.security.Permission;
import mindustry.server.ServerLauncher;
/** Cooperative test guard, not a hostile-code sandbox. */
@SuppressWarnings("removal")
public final class SmokeLauncher {
    public static void main(String[] args){
        String root=Path.of(System.getProperty("smoke.root")).toAbsolutePath().normalize()+"/";
        System.setSecurityManager(new SecurityManager(){
            @Override public void checkPermission(Permission p){}
            @Override public void checkWrite(String p){if(!Path.of(p).toAbsolutePath().normalize().toString().startsWith(root))throw new SecurityException("Outside test directory");}
            @Override public void checkDelete(String p){throw new SecurityException("Deletion denied");}
            @Override public void checkListen(int p){throw new SecurityException("No listener");}
            @Override public void checkConnect(String h,int p){throw new SecurityException("No connection");}
            @Override public void checkMulticast(java.net.InetAddress a){throw new SecurityException("No multicast");}
            @Override public void checkExec(String p){throw new SecurityException("No subprocess");}
        });
        boolean denied=false;
        try{System.getSecurityManager().checkListen(0);}catch(SecurityException e){denied=true;}
        if(!denied)throw new AssertionError("Network guard");
        System.out.println("SC_NETWORK_DENIED");
        ServerLauncher.main(new String[0]);
    }
}
