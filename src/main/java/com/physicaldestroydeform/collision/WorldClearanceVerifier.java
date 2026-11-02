package com.physicaldestroydeform.collision;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.physicaldestroydeform.collision.GameImpactCredit.Pair;
import com.physicaldestroydeform.collision.RealFixBoundsEvidence.Box;

/**
 * Independent, bounded Plot/World clearance scan. R2/R4's 32-cell target cap
 * must never be used to inspect the space occupied by an entire ship.
 * All calls are on the server thread. Pending scans never authorize credit.
 * A changed pose, owner generation or newly observed contact discards progress.
 */
public final class WorldClearanceVerifier {
    public static final int MAX_CELLS = 8192;
    private static final int MAX_PENDING = 4096;
    private static final long IDLE_TICKS = 600;
    public static final int MAX_CHECKS_PER_PAIR_TICK = 256;
    /** Rare final single-tick recheck prevents stale World reads from granting Q. */
    public static final int MAX_CONFIRM_PER_TICK = 8192;
    public enum Status { CLEAR, BLOCKED, IN_PROGRESS, INVALIDATED, UNAVAILABLE, TOO_BROAD }
    public enum Cell { EMPTY, COLLIDER, UNAVAILABLE }
    @FunctionalInterface public interface Inspector { Cell inspect(int x, int y, int z); }
    public record Result(Status status, long checked, long total, String reason) {
        public boolean proved() { return status == Status.CLEAR; }
    }
    private static final class Scan {
        int x0, y0, z0, nx, ny, nz;
        long cursor;
        long startTick = -1;
        long lastTick = -1;
        Result lastResult;
        Scan(int x0, int y0, int z0, int nx, int ny, int nz) {
            this.x0=x0;this.y0=y0;this.z0=z0;
            this.nx=nx;this.ny=ny;this.nz=nz;
        }
        long total() { return (long)nx*ny*nz; }
    }
    private final Map<Pair,Scan> scans=new HashMap<>();
    public void clear() { scans.clear(); }
    public void invalidateOwner(String owner) {
        scans.keySet().removeIf(p->p.a().stableKey().equals(owner)||p.b().stableKey().equals(owner));
    }
    public void contact(Pair pair) { scans.remove(pair); }
    public int pending() {
        return (int)scans.values().stream().filter(s->s.lastResult==null||!s.lastResult.proved()).count();
    }
    public void prune(long tick) {
        scans.entrySet().removeIf(e->e.getValue().lastTick>=0
                && tick-e.getValue().lastTick>IDLE_TICKS);
    }

    /** Test convenience: live runtime shares a separate server-wide final-confirmation budget. */
    public Result inspect(Pair pair, Box box, long tick, int[] remaining, Inspector inspector) {
        return inspect(pair,box,tick,remaining,new int[]{MAX_CONFIRM_PER_TICK},inspector);
    }
    /** Each complete CLEAR is a new observation, never recycled across ticks. */
    public Result inspect(Pair pair, Box box, long tick, int[] remaining,
                          int[] confirmationRemaining, Inspector inspector) {
        Objects.requireNonNull(pair);Objects.requireNonNull(remaining);
        Objects.requireNonNull(confirmationRemaining);Objects.requireNonNull(inspector);
        if(box==null || tick<0 || remaining.length!=1 || confirmationRemaining.length!=1) {
            scans.remove(pair);
            return new Result(Status.UNAVAILABLE,0,0,"NO_VALID_WORLD_BOX");
        }
        // The extra 1/32 grid tolerance matches the existing broad-phase policy.
        double lowX=Math.floor(box.minX()-0.25-1.0/32.0);
        double lowY=Math.floor(box.minY()-0.25-1.0/32.0);
        double lowZ=Math.floor(box.minZ()-0.25-1.0/32.0);
        double highX=Math.floor(box.maxX()+0.25+1.0/32.0);
        double highY=Math.floor(box.maxY()+0.25+1.0/32.0);
        double highZ=Math.floor(box.maxZ()+0.25+1.0/32.0);
        if(lowX<Integer.MIN_VALUE+2 || lowY<Integer.MIN_VALUE+2 || lowZ<Integer.MIN_VALUE+2
                || highX>Integer.MAX_VALUE-2 || highY>Integer.MAX_VALUE-2 || highZ>Integer.MAX_VALUE-2) {
            scans.remove(pair);
            return new Result(Status.TOO_BROAD,0,0,"OUT_OF_GRID");
        }
        long nx=(long)(highX-lowX+1),ny=(long)(highY-lowY+1),nz=(long)(highZ-lowZ+1);
        if(nx<1||ny<1||nz<1||nx>MAX_CELLS||ny>MAX_CELLS||nz>MAX_CELLS
                || nx*ny>MAX_CELLS || nx*ny*nz>MAX_CELLS) {
            scans.remove(pair);
            return new Result(Status.TOO_BROAD,0,0,"CELL_LIMIT");
        }
        Scan scan=scans.get(pair);
        // Only a changed World grid footprint invalidates: sub-block pose jitter
        // within the same conservative set of cells cannot omit a collider.
        boolean changed=scan!=null && (scan.nx!=nx || scan.ny!=ny
                || scan.nz!=nz || scan.x0!=(int)lowX || scan.y0!=(int)lowY || scan.z0!=(int)lowZ);
        if(changed) {scans.remove(pair);scan=null;}
        if(scan==null) {
            if(scans.size()>=MAX_PENDING)
                return new Result(Status.UNAVAILABLE,0,nx*ny*nz,"STATE_CAPACITY");
            scan=new Scan((int)lowX,(int)lowY,(int)lowZ,(int)nx,(int)ny,(int)nz);
            scans.put(pair,scan);
        }
        if(scan.lastTick==tick) return scan.lastResult;
        if(scan.startTick<0)scan.startTick=tick;
        int work=Math.min(MAX_CHECKS_PER_PAIR_TICK,Math.max(0,remaining[0]));
        int performed=0;
        while(performed<work && scan.cursor<scan.total()) {
            long ordinal=scan.cursor;
            int x=scan.x0+(int)(ordinal%scan.nx);
            int y=scan.y0+(int)((ordinal/scan.nx)%scan.ny);
            int z=scan.z0+(int)(ordinal/((long)scan.nx*scan.ny));
            Cell cell=Objects.requireNonNull(inspector.inspect(x,y,z));
            performed++;remaining[0]--;scan.cursor++;
            if(cell!=Cell.EMPTY) {
                scans.remove(pair);
                return new Result(cell==Cell.COLLIDER?Status.BLOCKED:Status.UNAVAILABLE,
                        scan.cursor,scan.total(),cell==Cell.COLLIDER?"COLLIDER_FOUND":"CELL_UNAVAILABLE");
            }
        }
        if(scan.cursor==scan.total()) {
            // A multi-tick scan may have seen World blocks that subsequently
            // changed. NEVER combine those historical reads into proof.
            // Recheck the complete region on one server tick before rearming.
            if(scan.startTick!=tick) {
                if(confirmationRemaining[0]<scan.total()) {
                    Result waiting=new Result(Status.IN_PROGRESS,scan.cursor,scan.total(),
                            "WAIT_ATOMIC_CONFIRM_BUDGET");
                    scan.lastResult=waiting;scan.lastTick=tick;
                    return waiting;
                }
                for(long i=0;i<scan.total();i++) {
                    int x=scan.x0+(int)(i%scan.nx);
                    int y=scan.y0+(int)((i/scan.nx)%scan.ny);
                    int z=scan.z0+(int)(i/((long)scan.nx*scan.ny));
                    confirmationRemaining[0]--;
                    Cell cell=Objects.requireNonNull(inspector.inspect(x,y,z));
                    if(cell!=Cell.EMPTY) {
                        scans.remove(pair);
                        return new Result(cell==Cell.COLLIDER?Status.BLOCKED:Status.UNAVAILABLE,
                                i+1,scan.total(),cell==Cell.COLLIDER
                                        ?"CONFIRM_COLLIDER_FOUND":"CONFIRM_CELL_UNAVAILABLE");
                    }
                }
            }
            // Keep only this tiny footprint cursor for the next tick's
            // independent proof. Caller discards it when FSM reaches ARMED.
            scan.startTick=tick;
            Result proof=new Result(Status.CLEAR,scan.cursor,scan.total(),"COMPLETE_CURRENT_WORLD_PROOF");
            scan.lastResult=proof;scan.lastTick=tick;
            return proof;
        }
        Result result=new Result(changed?Status.INVALIDATED:Status.IN_PROGRESS,
                scan.cursor,scan.total(),changed?"GRID_CHANGED":"BUDGET_PENDING");
        scan.lastResult=result;scan.lastTick=tick;
        return result;
    }
}
