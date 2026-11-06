package com.physicaldestroydeform.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Non-authoritative validation artifact. Never used by World/Plot commit or damage gameplay. */
public final class PreviewCheckpoint {
    private static final String NAME="pdd-0.4.0colFix3-preview-only.bin";
    private PreviewCheckpoint() {}
    public static boolean enabled(){return Boolean.parseBoolean(
            System.getProperty("pdd.preview.checkpoint","true"));}
    public static Path file(Path worldRoot){return worldRoot.resolve(NAME);}
    /** Preview state is only comparable within the same material and collision policy. */
    public static Path file(Path worldRoot,String policyId) {
        if(policyId==null||!policyId.matches("[a-f0-9-]{36}"))
            throw new IllegalArgumentException("invalid preview policy identity");
        return worldRoot.resolve(NAME.substring(0,NAME.length()-4)+"-"+policyId+".bin");
    }
    public static byte[] load(Path file) throws IOException {
        if(!Files.exists(file))return null;
        long size=Files.size(file);
        if(size<16||size>SparseStateCodec.MAX_BYTES)throw new IOException("invalid preview checkpoint size");
        byte[] data=Files.readAllBytes(file);
        SparseStateCodec.decode(data); // complete CRC, schema and duplicate-key validation
        return data;
    }
    /** Atomic replace in same directory. Unsupported atomic fs never overwrites a valid checkpoint. */
    public static void save(Path file,byte[] bytes) throws IOException {
        if(bytes==null||bytes.length>SparseStateCodec.MAX_BYTES)throw new IOException("invalid preview size");
        SparseStateCodec.decode(bytes);
        Path temp=Files.createTempFile(file.getParent(),"pdd-preview-", ".tmp");
        try {
            Files.write(temp,bytes);
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
}
