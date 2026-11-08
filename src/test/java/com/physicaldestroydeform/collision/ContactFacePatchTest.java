package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContactFacePatchTest {
    static final String DIM="minecraft:overworld", OWNER="WORLD:"+DIM;
    static GameImpactCredit.Target target(int x,int z) {
        return new GameImpactCredit.Target(OWNER,x+",64,"+z,"minecraft:grass_block");
    }
    static List<NativeCollisionTelemetry.Contact> corners() {
        var contacts=new ArrayList<NativeCollisionTelemetry.Contact>();
        for(int x:new int[]{0,4})for(int z:new int[]{0,4})
            contacts.add(new NativeCollisionTelemetry.Contact(7,-1,3500,
                    0,-1,0,0,1,0,0,0,0,x+0.5,65,z+0.5,DIM,5));
        return contacts;
    }
    static List<GameImpactCredit.Target> cornerIslands() {
        var seeds=new ArrayList<GameImpactCredit.Target>();
        for(int x=0;x<5;x++)for(int z=0;z<5;z++)if(x!=2&&z!=2)
            seeds.add(target(x,z));
        return seeds;
    }
    @Test void fourCornerContactWitnessesRecoverAllTwentyFiveVerifiedCells() {
        var result=ContactFacePatch.reconstruct(DIM,OWNER,BlockPos.ZERO,cornerIslands(),corners(),
                (pos,point)->target(pos.getX(),pos.getZ()));
        assertEquals("SHAPE_VERIFIED_FACE",result.reason());
        assertEquals(9,result.added().size());
        assertEquals(9,result.attempted());
        assertEquals(25,new HashSet<>(result.added()).size()+cornerIslands().size());
    }
    @Test void hundredByHundredFaceIsBoundedAndStillUsesOneSourceEvent() {
        var seeds=new ArrayList<GameImpactCredit.Target>();
        for(int x:new int[]{0,99})for(int z:new int[]{0,99})seeds.add(target(x,z));
        var witnesses=new ArrayList<NativeCollisionTelemetry.Contact>();
        for(int x:new int[]{0,99})for(int z:new int[]{0,99})
            witnesses.add(new NativeCollisionTelemetry.Contact(7,-1,4000,
                    0,-1,0,0,1,0,0,0,0,x+0.5,65,z+0.5,DIM,5));
        var result=ContactFacePatch.reconstruct(DIM,OWNER,BlockPos.ZERO,seeds,witnesses,
                (pos,point)->target(pos.getX(),pos.getZ()));
        assertEquals(10000-4,result.added().size());
        assertEquals(10000-4,result.attempted());
        assertEquals("SHAPE_VERIFIED_FACE",result.reason());
    }
    @Test void missingInnerShapeNeverBecomesASyntheticContact() {
        var result=ContactFacePatch.reconstruct(DIM,OWNER,BlockPos.ZERO,cornerIslands(),corners(),
                (pos,point)->pos.getX()==2&&pos.getZ()==2?null:target(pos.getX(),pos.getZ()));
        assertEquals(8,result.added().size());
        assertTrue(result.added().stream().noneMatch(t->t.localBlock().equals("2,64,2")));
    }
    @Test void aSinglePointOrObliqueWitnessNeverFillsAFace() {
        var invalid=ContactFacePatch.reconstruct(DIM,OWNER,BlockPos.ZERO,cornerIslands(),
                corners().subList(0,1),(pos,point)->target(pos.getX(),pos.getZ()));
        assertTrue(invalid.added().isEmpty());
        var oblique=corners().stream().map(c->new NativeCollisionTelemetry.Contact(7,-1,3500,
                0,-1,0,0,1,1,0,0,0,c.contactBX(),65,c.contactBZ(),DIM,5)).toList();
        assertEquals("NOT_AXIS_ALIGNED",ContactFacePatch.reconstruct(DIM,OWNER,BlockPos.ZERO,
                cornerIslands(),oblique,(pos,point)->target(pos.getX(),pos.getZ())).reason());
    }
    @Test void plotStorageOffsetMustNotBeConfusedWithRelativeCoordinates() {
        var off=new BlockPos(20_480_000,64,20_480_000);
        var points=corners().stream().map(c->new NativeCollisionTelemetry.Contact(7,-1,3500,
                0,-1,0,0,1,0,0,0,0,c.contactBX()+off.getX(),c.contactBY()+off.getY(),
                c.contactBZ()+off.getZ(),DIM,5)).toList();
        var result=ContactFacePatch.reconstruct(DIM,OWNER,off,cornerIslands(),points,
                (pos,point)->target(pos.getX(),pos.getZ()));
        assertEquals(9,result.added().size());
    }
}
