package com.physicaldestroydeform.plot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import java.util.*;
import java.util.function.Function;

/** A bounded sufficient proof, not a recreation of Sable's topology engine.
 * Preserve every face-connected route through the removed cell, and connect
 * the new cell to those routes. Never infer absence from an unavailable read. */
final class PlotMoveTopology {
    enum Result { CONNECTED, DISCONNECTED, UNKNOWN }
    private PlotMoveTopology(){}
    static Result check(BlockPos source,BlockPos destination,Function<BlockPos,Boolean> loadedSolid){
        Set<BlockPos> required=new HashSet<>();
        for(Direction face:Direction.values()){
            BlockPos neighbor=source.relative(face);
            if(neighbor.equals(destination))continue;
            Boolean solid=loadedSolid.apply(neighbor);
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
            for(Direction face:Direction.values()){
                BlockPos next=from.relative(face);
                if(next.equals(source)||visited.contains(next)||Math.abs((long)source.getX()-next.getX())>2
                        ||Math.abs((long)source.getY()-next.getY())>2||Math.abs((long)source.getZ()-next.getZ())>2)continue;
                Boolean solid=loadedSolid.apply(next);
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
