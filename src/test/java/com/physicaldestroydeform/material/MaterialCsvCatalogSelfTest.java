package com.physicaldestroydeform.material;

import com.physicaldestroydeform.MaterialProperties;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Runnable isolated regression, deliberately not labelled JUnit or game verification. */
public final class MaterialCsvCatalogSelfTest {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static final String BLOCK_HEAD="namespace,id,H,T,D,E,unbreakable,eligibility,profile_basis,confidence,source,notes\n";
    private static final String ITEM_HEAD="namespace,id,H,D,E,corresponding_block_id,profile_basis,confidence,source,notes\n";
    private static MaterialCsvCatalog.Snapshot parse(String blocks,String items) throws Exception {
        return MaterialCsvCatalog.parse(new StringReader(blocks),new StringReader(items),
                id->true,id->true,2,"test");
    }
    public static void main(String[] argv) throws Exception {
        var sample=parse(BLOCK_HEAD+"minecraft,minecraft:stone,59,91,10,9,0,COLLISION_CANDIDATE,profile,MEDIUM,vanilla,\"one, two\"\r\n",
                ITEM_HEAD+"minecraft,minecraft:stone,59,10,9,minecraft:stone,BLOCK_ITEM_INHERIT,MEDIUM,vanilla,\"multi\nline\"\n");
        check(sample.blocks().size()==1,"one block");
        check(sample.items().size()==1,"one item");
        check(sample.blocks().get("minecraft:stone").h()==59,"stone H");
        check(sample.blocks().get("minecraft:stone").t()==91,"stone T");
        check(sample.items().get("minecraft:stone").correspondingBlock().equals("minecraft:stone"),"block inherit id");
        var base=new MaterialProperties(10,50,30,10,false);
        var bundled=MaterialCsvCatalog.PartialBlock.from(sample.blocks().get("minecraft:stone"));
        var json=new MaterialCsvCatalog.PartialBlock(70,null,22,null,null,"json");
        var external=new MaterialCsvCatalog.PartialBlock(null,100,null,15,null,"user-csv");
        var merged=bundled.overlay(json).overlay(external).resolve(base);
        check(merged.hardness()==70,"JSON H takes precedence over JAR");
        check(merged.threshold()==100,"user CSV T outranks JAR");
        check(merged.ductilityPercent()==22,"JSON D");
        check(merged.elasticityPercent()==15,"CSV E");
        var duplicate=parse(BLOCK_HEAD+"minecraft,minecraft:stone,59,91,10,9,0,COLLISION_CANDIDATE,a,a,a,a\n"
                +"minecraft,minecraft:stone,60,92,11,10,0,COLLISION_CANDIDATE,b,b,b,b\n",ITEM_HEAD);
        check(duplicate.blocks().isEmpty(),"duplicates reject full ID");
        check(duplicate.rejectedDuplicates()==1,"duplicates counted");
        boolean illegal=false;
        try {parse(BLOCK_HEAD+"minecraft,minecraft:stone,256,91,10,9,0,COLLISION_CANDIDATE,a,a,a,a\n",ITEM_HEAD);}
        catch(IllegalArgumentException expected){illegal=true;}
        check(illegal,"out-of-range rejected");
        illegal=false;
        try {parse(BLOCK_HEAD+"minecraft,minecraft:stone,59,91,10,9,0,COLLISION_CANDIDATE,a,a,a,\"bad\n",ITEM_HEAD);}
        catch(IOException expected){illegal=true;}
        check(illegal,"unbalanced quote rejected");
        illegal=false;
        try {parse(BLOCK_HEAD+"minecraft,minecraft:stone,59,91,10,9,0,COLLISION_CANDIDATE,a,a,a,a\n",
                ITEM_HEAD+"minecraft,minecraft:stone,59,10,9,minecraft:stone,a,a,a,invalid\"quote\n");}
        catch(IOException expected){illegal=true;}
        check(illegal,"invalid quoted item rejected");
        if(argv.length==2){
            try(Reader blocks=Files.newBufferedReader(Path.of(argv[0]),StandardCharsets.UTF_8);
                Reader items=Files.newBufferedReader(Path.of(argv[1]),StandardCharsets.UTF_8)){
                var csv=MaterialCsvCatalog.parse(blocks,items,id->true,
                        id->true,3,"provided-csv");
                check(csv.blocks().size()>=1800,"provided blocks count");
                check(csv.items().size()>=2100,"provided items count");
                check(csv.blocks().get("minecraft:stone").h()==59,"real stone H59");
                check(csv.blocks().get("minecraft:stone").t()==91,"real stone T91");
                check(csv.items().get("minecraft:stone").d()==10,"real stone item D10");
                System.out.println("REAL_CSV blocks="+csv.blocks().size()+" items="+csv.items().size()+
                        " duplicateIds="+csv.rejectedDuplicates()+" unregistered="+csv.skippedUnregistered());
            }
        }
        System.out.println("MATERIAL_CSV_SELFTEST PASS checks="+checks);
    }
}
