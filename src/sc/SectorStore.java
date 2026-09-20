package sc;

import arc.files.Fi;
import java.nio.channels.*;
import java.nio.file.*;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import mindustry.Vars;
import mindustry.io.SaveIO;
import mindustry.io.SaveMeta;
import mindustry.io.SaveOptions;
import mindustry.io.JsonIO;
import mindustry.game.SectorInfo;
import arc.struct.StringMap;
import mindustry.type.Sector;

/** Opt-in disposable storage only; never resolves Vars.saveDirectory or accepts guest paths.
 * Lock files are stable inodes: never delete/replace them. All participants must cooperate.
 */
public final class SectorStore {
    final Path root, directory;
    final String campaign;
    private FileChannel coordinatorChannel, writerChannel;
    private FileLock coordinatorLock, writerLock;
    private Sector writerSector;

    static void require(boolean b, String reason) { if(!b) throw new IllegalArgumentException(reason); }
    static boolean id(String s) { return s != null && s.matches("[A-Za-z0-9_-]{1,64}"); }
    static String key(Sector s) { return s.planet.name+":"+s.id; }
    static void noLinks(Path p) {
        for(Path q=p; q!=null; q=q.getParent()) require(!Files.isSymbolicLink(q), "symlink");
    }
    public SectorStore() throws IOException {
        String value=System.getProperty("sc.m4.workspace");
        require(value!=null, "disposable-workspace-required");
        Path workspace=Path.of(value).toAbsolutePath().normalize();
        noLinks(workspace);
        require(Files.isRegularFile(workspace.resolve(".sc-m4-disposable"),LinkOption.NOFOLLOW_LINKS), "disposable-marker");
        require(Path.of("").toAbsolutePath().normalize().startsWith(workspace), "workspace-cwd");
        String r=System.getProperty("sc.m4.root");
        require(r!=null, "store-root");
        root=Path.of(r).toAbsolutePath().normalize();
        require(root.equals(workspace.resolve("campaigns")), "store-root");
        noLinks(root.resolve("campaign.id"));
        require(Files.size(root.resolve("campaign.id"))<=64, "campaign");
        campaign=Files.readString(root.resolve("campaign.id"));
        require(id(campaign), "campaign");
        directory=root.resolve(campaign);
        noLinks(directory);
        require(Files.isDirectory(directory), "campaign-directory");
    }
    Path file(Sector s, String extension) {
        require(id(s.planet.name) && s.id>=0, "sector");
        Path p=directory.resolve(s.planet.name+"-"+s.id+extension);
        noLinks(p);
        return p;
    }
    SaveMeta meta(Sector s) {
        Path p=file(s,".msav");
        if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)) return null;
        SaveMeta m=SaveIO.getMeta(new Fi(p.toFile()));
        require(m.rules.sector==s, "save-sector");
        require(campaign.equals(m.tags.get("sc-campaign")), "save-campaign");
        return m;
    }
    boolean eligible(Sector s) {
        SaveMeta m=meta(s);
        return m!=null && "false".equals(m.tags.get("nocores"));
    }
    void coordinator() throws IOException {
        Path p=directory.resolve("coordinator.lock"); noLinks(p);
        coordinatorChannel=FileChannel.open(p,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        coordinatorLock=coordinatorChannel.tryLock();
        if(coordinatorLock==null) {coordinatorChannel.close();throw new IllegalArgumentException("coordinator-owned");}
    }
    void acquire(Sector s) throws IOException {
        require(writerLock==null,"already-owned");
        writerChannel=FileChannel.open(file(s,".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        try { writerLock=writerChannel.tryLock(); }
        catch(OverlappingFileLockException ex) {writerChannel.close();throw new IllegalArgumentException("writer-live");}
        if(writerLock==null) {writerChannel.close();throw new IllegalArgumentException("writer-live");}
        writerSector=s;
    }
    public void open(Sector s, String grant) throws IOException {
        require(Vars.state.isMenu(), "world-live");
        checkLease(s,grant);
        require(eligible(s), "ineligible");
        acquire(s); // Lifetime lock BEFORE loading a world, not just around file writes.
        // Fail closed on a load exception: keep lock until process termination.
        Vars.logic.reset();
        // Vanilla SaveLoadEvent overwrites core items from SectorInfo. Carry the SAME sector's
        // info inside its atomic .msav, not the other process's settings / another core.
        SaveMeta metadata=meta(s);
        String info=metadata.tags.get("sc-sector-info");
        require(info!=null && info.length()<=262144,"sector-info-missing");
        s.info=JsonIO.read(SectorInfo.class,info);
        SaveIO.load(new Fi(file(s,".msav").toFile()));
        require(Vars.state.isCampaign() && Vars.state.getSector()==s && Vars.state.rules.defaultTeam.core()!=null, "loaded-sector");
        // Resume a save, do NOT call Logic.play(): it clears cores to starting loadout.
        Vars.state.set(mindustry.core.GameState.State.playing);
    }
    public String save(Sector s) throws Exception {
        require(writerLock!=null && writerLock.isValid() && writerSector==s, "not-owner");
        require(Vars.state.isCampaign() && Vars.state.getSector()==s && Vars.state.rules.defaultTeam.core()!=null, "world-changed");
        Path destination=file(s,".msav");
        // A uniquely created sibling and mandatory ATOMIC_MOVE: never fall back to partial overwrite.
        Path temp=Files.createTempFile(directory,s.planet.name+"-"+s.id+"-", ".tmp");
        try {
            s.info.prepare(s);
            SaveOptions options=new SaveOptions();
            // Store revision version in save metadata via tags
            long revision = getNextRevisionVersion(s);
            options.extraTags=StringMap.of("sc-sector-info",JsonIO.write(s.info),"sc-campaign",campaign,"sc-revision",String.valueOf(revision));
            SaveIO.write(new Fi(temp.toFile()),options);
            SaveMeta m=SaveIO.getMeta(new Fi(temp.toFile()));
            require(m.rules.sector==s && "false".equals(m.tags.get("nocores")), "save-validation");
            try(FileChannel c=FileChannel.open(temp,StandardOpenOption.WRITE)) {c.force(true);}
            noLinks(destination);
            Files.move(temp,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(destination)));
            return "sector="+key(s)+" bytes="+Files.size(destination)+" sha256="+hash+" api=SaveIO.write atomic=true revision="+revision;
        } finally {Files.deleteIfExists(temp);}
    }

    private long getNextRevisionVersion(Sector s) {
        // Read current revision from existing save, increment
        SaveMeta m = meta(s);
        if (m != null) {
            String rev = m.tags.get("sc-revision");
            if (rev != null) {
                try { return Long.parseLong(rev) + 1; } catch (NumberFormatException ignored) {}
            }
        }
        return 1;
    }
    void lease(Sector s, String grant) throws IOException {
        // Durable admission record; rewritten per new lease after RELEASED. Content-checked on open.
        Files.writeString(file(s,".lease"),grant,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE);
    }
    void checkLease(Sector s, String grant) throws IOException {
        Path p=file(s,".lease");
        require(Files.isRegularFile(p) && Files.size(p)<=4096 && Files.readString(p).equals(grant), "lease");
    }
}
