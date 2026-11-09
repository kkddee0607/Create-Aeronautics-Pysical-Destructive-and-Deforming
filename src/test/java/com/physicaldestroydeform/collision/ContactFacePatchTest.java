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
    @Test void noisySolverCornerNormalsStillResolveSingleAxisFaceAndSpreadOneEvent() {
        // Four native extremal witnesses differ by up to ~20 degrees, but
        // all locate the same Y=65 world face. Shape checks still gate cells.
        var contacts=new ArrayList<NativeCollisionTelemetry.Contact>();
        int i=0;
        for(int x:new int[]{0,4})for(int z:new int[]{0,4}){
            double dx=(i%2==0?0.30:-0.24); i++;
            contacts.add(new NativeCollisionTelemetry.Contact(7,-1,3500,
                    0,-1,0,dx,1,0.15,0,0,0,x+0.5,65,z+0.5,DIM,5));
        }
        var four=List.of(target(0,0),target(0,4),target(4,0),target(4,4));
        var verified=ContactFacePatch.reconstruct(DIM,OWNER,BlockPos.ZERO,four,contacts,
                (pos,point)->target(pos.getX(),pos.getZ()));
        assertEquals(21,verified.added().size(),verified.reason());
        var all=new HashSet<>(four);all.addAll(verified.added());
        assertEquals(25,all.size());
    }
    @Test void fourCornerContactWitnessesRecoverAllTwentyFiveVerifiedCells() {
        var result=ContactFacePatch.reconstruct(DIM,OWNER,BlockPos.ZERO,cornerIslands(),corners(),
                (pos,point)->target(pos.getX(),pos.getZ()));
        assertTrue(result.reason().startsWith("SHAPE_VERIFIED_SURFACES"),result.reason());
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
        assertTrue(patch.reason().startsWith("SHAPE_VERIFIED_SURFACES"),patch.reason());
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
        assertTrue(result.reason().startsWith("SHAPE_VERIFIED_SURFACES"),result.reason());
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
        assertTrue(result.reason().startsWith("SHAPE_VERIFIED_SURFACES"),result.reason());
        assertEquals(21,result.added().size());
    }
    @Test void diagonalWitnessesCannotFabricateARectangle() {
        var diagonal=new ArrayList<NativeCollisionTelemetry.Contact>();
        for(int p=0;p<5;p++)diagonal.add(new NativeCollisionTelemetry.Contact(7,-1,3500,
                0,-1,0,0,1,0,0,0,0,p+0.5,65,p+0.5,DIM,5));
        var result=ContactFacePatch.reconstruct(DIM,OWNER,BlockPos.ZERO,cornerIslands(),diagonal,
                (pos,point)->target(pos.getX(),pos.getZ()));
        assertTrue(result.reason().startsWith("SHAPE_VERIFIED_SURFACES"));
        assertTrue(result.added().stream().allMatch(t -> {
            String[] c=t.localBlock().split(",");
            return c[0].equals(c[2]);
        }),"A diagonal native contact may recover a line, never a whole rectangle");
    }
    @Test void onlyIndividuallyProvenFaceBlocksReceiveRecoveredTargets() {
        var four=List.of(target(0,0),target(0,4),target(4,0),target(4,4));
        var result=ContactFacePatch.reconstruct(DIM,OWNER,BlockPos.ZERO,four,corners(),
                (pos,point)->pos.getX()==2&&pos.getZ()==2?null:target(pos.getX(),pos.getZ()));
        assertTrue(result.reason().startsWith("SHAPE_VERIFIED_SURFACES"),result.reason());
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
        assertTrue(result.reason().startsWith("SHAPE_VERIFIED_SURFACES"),result.reason());
    }
    @Test void missingInnerShapeNeverBecomesASyntheticContact() {
        var result=ContactFacePatch.reconstruct(DIM,OWNER,BlockPos.ZERO,cornerIslands(),corners(),
                (pos,point)->pos.getX()==2&&pos.getZ()==2?null:target(pos.getX(),pos.getZ()));
        assertEquals(8,result.added().size());
        assertTrue(result.added().stream().noneMatch(t->t.localBlock().equals("2,64,2")));
    }
    @Test void oneSparseWitnessCanOnlyVerifyOneCellHaloNotAnUnboundedFace() {
        var limited=ContactFacePatch.reconstruct(DIM,OWNER,BlockPos.ZERO,cornerIslands(),
                corners().subList(0,1),(pos,point)->target(pos.getX(),pos.getZ()));
        assertTrue(limited.reason().contains("sparseLocalHalos=1"),limited.reason());
        assertTrue(limited.added().size()<=8);
        assertTrue(limited.added().stream().allMatch(t->{
            String[] xyz=t.localBlock().split(",");
            return Math.abs(Integer.parseInt(xyz[0]))<=1
                    && Math.abs(Integer.parseInt(xyz[2]))<=1;
        }));
        var oblique=corners().stream().map(c->new NativeCollisionTelemetry.Contact(7,-1,3500,
                0,-1,0,0,1,1,0,0,0,c.contactBX(),65,c.contactBZ(),DIM,5)).toList();
        // Oblique local normals now retain their own (potentially sloped)
        // source planes; they are not rejected as non-axis-aligned up front.
        assertNotNull(ContactFacePatch.reconstruct(DIM,OWNER,BlockPos.ZERO,
                cornerIslands(),oblique,(pos,point)->target(pos.getX(),pos.getZ())));
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
        assertTrue(result.reason().startsWith("SHAPE_VERIFIED_SURFACES"),result.reason());
        assertEquals(24,result.added().size());
        assertTrue(result.added().stream().anyMatch(t->t.localBlock().equals("2,64,2")));
    }

    @Test void twoIndependentPerpendicularFacesRecoverEveryVerifiedCellWithoutDoubleForce() {
        var records=new ArrayList<NativeCollisionTelemetry.Contact>();
        for(int x:new int[]{0,3})for(int z:new int[]{0,3})
            records.add(new NativeCollisionTelemetry.Contact(7,-1,4000,
                    0,-1,0,0,1,0,0,0,0,x+0.5,65,z+0.5,DIM,42));
        for(int y:new int[]{64,67})for(int z:new int[]{0,3})
            records.add(new NativeCollisionTelemetry.Contact(7,-1,4000,
                    1,0,0,-1,0,0,0,0,0,5,y+0.5,z+0.5,DIM,42));
        var horizontal=new GameImpactCredit.Target(OWNER,"0,64,0","minecraft:stone");
        var vertical=new GameImpactCredit.Target(OWNER,"5,64,0","minecraft:stone");
        var patch=ContactFacePatch.reconstruct(DIM,OWNER,BlockPos.ZERO,
                List.of(horizontal,vertical),records,(pos,point)->
                    (pos.getY()==64&&pos.getX()>=0&&pos.getX()<4 ||
                     pos.getX()==5&&pos.getY()>=64&&pos.getY()<68)
                      ?new GameImpactCredit.Target(OWNER,
                        pos.getX()+","+pos.getY()+","+pos.getZ(),"minecraft:stone"):null);
        assertTrue(patch.reason().contains("faces=2"),patch.reason());
        var all=new HashSet<GameImpactCredit.Target>(List.of(horizontal,vertical));
        all.addAll(patch.added());
        assertEquals(32,all.size());
        assertTrue(all.contains(new GameImpactCredit.Target(OWNER,"2,64,2","minecraft:stone")));
        assertTrue(all.contains(new GameImpactCredit.Target(OWNER,"5,66,2","minecraft:stone")));
    }

    @Test void nativeTriangleDoesNotFabricateBoundingRectangleCorners() {
        var contacts=new ArrayList<NativeCollisionTelemetry.Contact>();
        for(var c:new double[][]{{0.5,0.5},{4.5,0.5},{0.5,4.5}})
            contacts.add(new NativeCollisionTelemetry.Contact(7,-1,3500,
                    0,-1,0,0,1,0,0,0,0,c[0],65,c[1],DIM,5));
        var patch=ContactFacePatch.reconstruct(DIM,OWNER,BlockPos.ZERO,
                List.of(target(0,0)),contacts,(pos,point)->target(pos.getX(),pos.getZ()));
        assertTrue(patch.reason().startsWith("SHAPE_VERIFIED_SURFACES"),patch.reason());
        assertTrue(patch.added().stream().anyMatch(t->t.localBlock().equals("1,64,1")));
        assertFalse(patch.added().stream().anyMatch(t->t.localBlock().equals("4,64,4")));
    }

    @Test void oversizedUnverifiedRectangleIsBudgetedWithoutFabricatedTargets() {
        var contacts=new ArrayList<NativeCollisionTelemetry.Contact>();
        for(int x:new int[]{0,199})for(int z:new int[]{0,199})
            contacts.add(new NativeCollisionTelemetry.Contact(7,-1,3500,
                    0,-1,0,0,1,0,0,0,0,x+0.5,65,z+0.5,DIM,5));
        var patch=ContactFacePatch.reconstruct(DIM,OWNER,BlockPos.ZERO,
                List.of(target(0,0)),contacts,(pos,point)->target(pos.getX(),pos.getZ()));
        assertEquals("PATCH_BUDGET",patch.reason());
        assertTrue(patch.added().isEmpty());
    }
    @Test void rotatedPlaneReconstructsOwnerVerifiedCellsWithoutAxisAlignedSpecialCase() {
        var contacts=new ArrayList<NativeCollisionTelemetry.Contact>();
        for(double y:new double[]{5.5,7.5})for(double z:new double[]{0.5,3.5}) {
            double x=10.0-y;
            contacts.add(new NativeCollisionTelemetry.Contact(7,-1,3000,
                    0,-1,0,1,1,0,0,0,0,x,y,z,DIM,77));
        }
        var seed=new GameImpactCredit.Target(OWNER,"4,5,0","minecraft:stone");
        var result=ContactFacePatch.reconstruct(DIM,OWNER,BlockPos.ZERO,
                List.of(seed),contacts,(pos,point)->new GameImpactCredit.Target(OWNER,
                        pos.getX()+","+pos.getY()+","+pos.getZ(),"minecraft:stone"));
        assertTrue(result.reason().contains("faces=1"),result.reason());
        assertEquals(11,result.added().size());
        assertTrue(result.added().stream().anyMatch(t->t.localBlock().equals("3,6,2")));
    }

}
