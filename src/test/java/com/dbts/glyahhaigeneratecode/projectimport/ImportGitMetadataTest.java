package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ImportGitMetadataTest {
    final String hash="a".repeat(40);
    @Test void branchBytesRequireStrictUtf8RatherThanReplacingNames() {
        assertEquals("功能",ImportGitMetadata.branches((hash+"\trefs/heads/功能\n").getBytes(StandardCharsets.UTF_8)).getFirst().name());
        assertThrows(RuntimeException.class,()->ImportGitMetadata.branches(new byte[]{(byte)0xff}));
    }
    @Test void branchListingRetainsAllHeadsAndRejectsDuplicateOrForeignRefs() {
        var branches=ImportGitMetadata.branches(hash+"\trefs/heads/main\n"+hash+"\trefs/heads/feature/customer\n");
        assertEquals(2,branches.size()); assertEquals("feature/customer",branches.get(1).name());
        assertEquals(1,ImportGitMetadata.branches(hash+"\trefs/heads/main\r\n").size());
        assertThrows(RuntimeException.class,()->ImportGitMetadata.branches(hash+"\trefs/tags/main\n"));
        assertThrows(RuntimeException.class,()->ImportGitMetadata.branches(hash+"\trefs/heads/main\n"+hash+"\trefs/heads/main\n"));
        assertThrows(RuntimeException.class,()->ImportGitMetadata.branches("bad\trefs/heads/main\n"));
    }
    @Test void parsesNulFramedTreePreservingUnicodeSpacesZeroBytesAndExecutableMode() {
        byte[] input=("100644 blob "+hash+"       0\t工程/空 文件\0"+"100755 blob "+hash+"       3\tbin/start.sh\0").getBytes(StandardCharsets.UTF_8);
        var blobs=ImportGitMetadata.tree(input,10,100);
        assertEquals("工程/空 文件",blobs.get(0).path()); assertEquals(0,blobs.get(0).size()); assertTrue(blobs.get(1).executable());
    }
    @Test void blocksLinksSubmodulesUnknownModesUnsafePathsTruncatedFramingAndLimits() {
        for(String row:new String[]{"120000 blob "+hash+" 3\tlink\0","160000 commit "+hash+" -\tsubmodule\0","100640 blob "+hash+" 3\tx\0","100644 blob "+hash+" 3\t../x\0","100644 blob "+hash+" 3\tx"}) {
            assertThrows(RuntimeException.class,()->ImportGitMetadata.tree(row.getBytes(StandardCharsets.UTF_8),10,100));
        }
        byte[] valid=("100644 blob "+hash+" 3\tx\0").getBytes(StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class,()->ImportGitMetadata.tree(valid,0,100));
        assertThrows(RuntimeException.class,()->ImportGitMetadata.tree(valid,10,2));
        assertThrows(RuntimeException.class,()->ImportGitMetadata.tree(new byte[]{(byte)0xff,0},10,100));
    }
    @Test void lfsPointerIsExplicitGapNotRealAsset() {
        byte[] pointer=("version https://git-lfs.github.com/spec/v1\noid sha256:"+"a".repeat(64)+"\nsize 3\n").getBytes(StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class,()->ImportGitMetadata.requireMaterializedContent(pointer));
        assertDoesNotThrow(()->ImportGitMetadata.requireMaterializedContent(new byte[]{0,1,2}));
    }
}
