package com.physicaldestroydeform.plot;

import net.minecraft.core.BlockPos;
import java.util.*;
import java.util.function.Function;

/** A bounded sufficient proof, not a recreation of Sable's topology engine.
 * Preserve every Sable-connected route through the removed cell, and connect
 * the new cell to those routes. Never infer absence from an unavailable read. */
final class PlotMoveTopology {
    enum Result { CONNECTED, DISCONNECTED, UNKNOWN }
    private PlotMoveTopology(){}
    // Sable 2.0.6 SubLevelHeatMapManager.DIRECTION_OFFSETS: 6 faces + 12 edges,
    // not the 8 three-axis corners. This is NOT the stress propagation graph.
    private static final List<BlockPos> OFFSETS=offsets();
    private static List<BlockPos> offsets(){
        List<BlockPos> result=new ArrayList<>(18);
        for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++){
            int axes=Math.abs(x)+Math.abs(y)+Math.abs(z);
            if(axes==1||axes==2)result.add(new BlockPos(x,y,z));
        }
        return List.copyOf(result);
    }
    static Result check(BlockPos source,BlockPos destination,Function<BlockPos,Boolean> loadedSolid){
        // One read per bounded cell, including unavailable reads.
        Map<BlockPos,Boolean> cache=new HashMap<>();
        Function<BlockPos,Boolean> solidAt=p->{
            if(!cache.containsKey(p))cache.put(p,loadedSolid.apply(p));
            return cache.get(p);
        };
        Set<BlockPos> required=new HashSet<>();
        for(BlockPos offset:OFFSETS){
            BlockPos neighbor=source.offset(offset);
            if(neighbor.equals(destination))continue;
            Boolean solid=solidAt.apply(neighbor);
            if(solid==null)return Result.UNKNOWN;
            if(solid)required.add(neighbor);
        }
        if(required.isEmpty())return Result.DISCONNECTED;
        Set<BlockPos> visited=new HashSet<>();
        Deque<BlockPos> queue=new ArrayDeque<>();
        visited.add(destination);queue.add(destination);
        boolean attached=false,unknown=false;
        while(!queue.isEmpty()&&visited.size()<=125){
            BlockPos from=queue.removeFirst();
            for(BlockPos offset:OFFSETS){
                BlockPos next=from.offset(offset);
                if(next.equals(source)||visited.contains(next)||Math.abs((long)source.getX()-next.getX())>2
                        ||Math.abs((long)source.getY()-next.getY())>2||Math.abs((long)source.getZ()-next.getZ())>2)continue;
                Boolean solid=solidAt.apply(next);
                if(solid==null){unknown=true;continue;}
                if(solid){visited.add(next);queue.addLast(next);attached=true;}
            }
            if(visited.containsAll(required))return Result.CONNECTED;
        }
        // Only the complete immediate-neighbor proof can establish isolation.
        // A route outside this local bound might exist, so do not charge it as a proven obstruction.
        return !attached&&!unknown?Result.DISCONNECTED:Result.UNKNOWN;
    }
}
