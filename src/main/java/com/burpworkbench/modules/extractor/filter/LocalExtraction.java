package com.burpworkbench.modules.extractor.filter;

import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** Optional local-file test adapter; normal Burp extraction uses ExportService. */
public final class LocalExtraction {
    public record Source(Path file,Documents.Input sample) {
        public String name(){return file!=null?file.getFileName().toString():sample.name();}
        Documents.Input input()throws Exception{return file!=null?Documents.file(file,Documents.Format.AUTO,"UTF-8"):sample;}
    }
    public record Result(Path directory,int saved,int failed,boolean cancelled){}
    public static Result extract(List<Source> sources,Path root,FilterSession filter,BooleanSupplier cancelled,Consumer<String> progress)throws Exception{
        Files.createDirectories(root);Path directory=Files.createDirectory(root.resolve("extract-file-test-"+UUID.randomUUID()));
        List<Map<String,Object>> manifest=new ArrayList<>();int saved=0,failed=0,index=0;boolean stopped=false;
        for(Source source:sources){
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted()){stopped=true;break;}
            int item=++index;
            try{
                byte[] output=filter==null?null:filter.filter(source.input()).bytes();
                String name=filter==null?source.name():filter.filename(source.name());
                name=name.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]","_").replaceAll("[. ]+$","");
                Path target=directory.resolve(String.format("%03d-",item)+name).normalize();
                if(!target.getParent().equals(directory))throw new IllegalArgumentException("PATH");
                if(filter!=null)Files.write(target,output,StandardOpenOption.CREATE_NEW);
                else if(source.file()!=null)Files.copy(source.file(),target);
                else Files.write(target,source.sample().bytes(),StandardOpenOption.CREATE_NEW);
                saved++;manifest.add(Map.of("item",item,"saved",target.getFileName().toString(),"status","saved"));
            }catch(java.util.concurrent.CancellationException e){stopped=true;break;}
            catch(Exception e){failed++;manifest.add(Map.of("item",item,"status","failed","reason","Input unsupported or extraction/filter failed; no raw fallback"));}
            progress.accept(index+" / "+sources.size()+" · Saved "+saved+" · Failed "+failed);
        }
        if(filter!=null)filter.writeAttributes(directory);
        Documents.JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("file_test_manifest.json").toFile(),Map.of("files",manifest,"filterEnabled",filter!=null,"cancelled",stopped));
        return new Result(directory,saved,failed,stopped);
    }
    private LocalExtraction(){}
}
