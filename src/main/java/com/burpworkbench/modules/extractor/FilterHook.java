package com.burpworkbench.modules.extractor;

import com.burpworkbench.modules.extractor.filter.*;
import java.io.IOException;
import java.nio.file.*;

/** Optional export filter. No raw body is written to disk in filter mode. */
final class FilterHook {
    final FilterSession session;
    FilterHook(FilterSession session){this.session=session;}
    String metadata(String value){return session.metadata(value);}
    Path path(Path value){return session.path(value);}
    void writeAttributes(Path directory)throws IOException{session.writeAttributes(directory);}
    FileDecodeResult body(byte[] raw,String ct,String ce,Path directory)throws IOException{
        try{
            byte[] output=session.filter(new Documents.Input("response",raw,ct,ce,Documents.Format.AUTO)).bytes();
            Path temp=Files.createTempFile(directory,".filtered-",".tmp");
            Files.write(temp,output);
            return new FileDecodeResult(temp,true,"filter applied; UTF-8 analysis copy",raw.length,output.length,Hashes.sha256Hex(output));
        }catch(java.util.concurrent.CancellationException ex){throw ex;}
        catch(Exception ex){throw new IOException("FILTER_FAILED_NO_RAW_FALLBACK");}
    }
}
