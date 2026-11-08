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
    @Test void actualFourNativeCornerRegionsRecoverEntireFiveByFiveNetheriteFace() {
        // Regression: a 5x5x4 Plot has 25 face blocks, but Sable's Rapier
        // feed may report only the four extremal contact neighborhoods.
        var onlyFour=new ArrayList<GameImpactCredit.Target>();
        for(int x:new int[]{0,4})for(int z:new int[]{0,4})onlyFour.add(target(x,z));
        var visited=new HashSet<String>();
        var patch=ContactFacePatch.reconstruct(DIM,OWNER,BlockPos.ZERO,onlyFour,corners(),
                (pos,point)->{
                    visited.add(pos.getX()+","+pos.getZ());
                    return target(pos.getX(),pos.getZ());
                });
        assertEquals("SHAPE_VERIFIED_FACE",patch.reason());
        assertEquals(21,patch.added().size());
        assertEquals(21,patch.attempted());
        assertTrue(visited.contains("2,2"));
        var all=new HashSet<>(onlyFour);all.addAll(patch.added());
        assertEquals(25,all.size());
        assertTrue(all.stream().anyMatch(t->t.localBlock().equals("2,64,2")));
    }
    @Test void oneR2VerifiedCornerAndFourNativeWitnessesStillRecoverTheFace() {
        // Previously INSUFFICIENT_WITNESSES: four corner contacts need not
        // produce four distinct already-verified R2 block keys.
        var one=List.of(target(0,0));
        var result=ContactFacePatch.reconstruct(DIM,OWNER,BlockPos.ZERO,one,corners(),
                (pos,point)->target(pos.getX(),pos.getZ()));
        assertEquals("SHAPE_VERIFIED_FACE",result.reason());
        assertEquals(24,result.added().size());
        assertEquals(25,result.added().size()+one.size());
    }
    @Test void unrelatedObliqueWitnessDoesNotVetoFourVerifiedFaceCorners() {
        var records=new ArrayList<>(corners());
        records.add(new NativeCollisionTelemetry.Contact(7,-1,3500,
                0,-1,0,0,1,1,0,0,0,2.5,65,2.5,DIM,5));
        var four=List.of(target(0,0),target(0,4),target(4,0),target(4,4));
        var result=ContactFacePatch.reconstruct(DIM,OWNER,BlockPos.ZERO,four,records,
                (pos,point)->target(pos.getX(),pos.getZ()));
        assertEquals("SHAPE_VERIFIED_FACE",result.reason());
        assertEquals(21,result.added().size());
    }
    @Test void diagonalWitnessesCannotFabricateARectangle() {
        var diagonal=new ArrayList<NativeCollisionTelemetry.Contact>();
        for(int p=0;p<5;p++)diagonal.add(new NativeCollisionTelemetry.Contact(7,-1,3500,
                0,-1,0,0,1,0,0,0,0,p+0.5,65,p+0.5,DIM,5));
        var result=ContactFacePatch.reconstruct(DIM,OWNER,BlockPos.ZERO,cornerIslands(),diagonal,
                (pos,point)->target(pos.getX(),pos.getZ()));
        assertEquals("NO_CORROBORATED_FACE",result.reason());
        assertTrue(result.added().isEmpty());
    }
    @Test void onlyIndividuallyProvenFaceBlocksReceiveRecoveredTargets() {
        var four=List.of(target(0,0),target(0,4),target(4,0),target(4,4));
        var result=ContactFacePatch.reconstruct(DIM,OWNER,BlockPos.ZERO,four,corners(),
                (pos,point)->pos.getX()==2&&pos.getZ()==2?null:target(pos.getX(),pos.getZ()));
        assertEquals("SHAPE_VERIFIED_FACE",result.reason());
        assertEquals(20,result.added().size());
        assertTrue(result.added().stream().noneMatch(t->t.localBlock().equals("2,64,2")));
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

    @Test void plotNativeCornerWitnessesWithStorageOffsetRecoverTwentyFiveNetheriteCells() {
        var id=java.util.UUID.fromString("12345678-1234-1234-1234-123456789abc");
        String plot="PLOT:"+id;
        var frame=new NativeCollisionTelemetry.OwnerFrame(id,new FrameMath.Pose(
                new FrameMath.Vec(0,0,0),new FrameMath.Vec(0,0,0),
                new FrameMath.Vec(1,1,1),0,0,0,1));
        var offset=new BlockPos(20_480_000,64,20_480_000);
        var contacts=new ArrayList<NativeCollisionTelemetry.Contact>();
        for(int x:new int[]{0,4})for(int z:new int[]{0,4})
            contacts.add(new NativeCollisionTelemetry.Contact(-1,7,3500,
                    0,-1,0,0,1,0,0,0,0,
                    x+0.5+offset.getX(),65+offset.getY(),z+0.5+offset.getZ(),
                    DIM,5,null,frame));
        var source=new GameImpactCredit.Target(plot,"0,64,0","minecraft:netherite_block");
        var result=ContactFacePatch.reconstruct(DIM,plot,offset,List.of(source),contacts,
                (pos,point)->new GameImpactCredit.Target(plot,
                        pos.getX()+","+pos.getY()+","+pos.getZ(),"minecraft:netherite_block"));
        assertEquals("SHAPE_VERIFIED_FACE",result.reason());
        assertEquals(24,result.added().size());
        assertTrue(result.added().stream().anyMatch(t->t.localBlock().equals("2,64,2")));
    }
}
