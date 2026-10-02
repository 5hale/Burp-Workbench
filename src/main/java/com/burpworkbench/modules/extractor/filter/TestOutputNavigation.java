package com.burpworkbench.modules.extractor.filter;

import java.util.*;
import java.util.regex.Pattern;

/** Output-local tag positions; source hit offsets must never be used as output offsets. */
final class TestOutputNavigation {
    private static final Pattern TAG=Pattern.compile("§[^§\\r\\n]{1,128}§");
    record Change(int start,int end,int hitIndex){}
    static List<Change> locate(String original,String output,List<String> tags){
        Set<String> originalTags=new HashSet<>();var source=TAG.matcher(original);while(source.find())originalTags.add(source.group());
        Map<String,ArrayDeque<Integer>> rows=new HashMap<>();List<Change> changes=new ArrayList<>();
        for(int i=0;i<tags.size();i++){
            String tag=tags.get(i);if(!tag.isEmpty()&&!originalTags.contains(tag))rows.computeIfAbsent(tag,k->new ArrayDeque<>()).add(i);
        }
        var result=TAG.matcher(output);while(result.find()){var queue=rows.get(result.group());if(queue!=null&&!queue.isEmpty())changes.add(new Change(result.start(),result.end(),queue.removeFirst()));}
        return List.copyOf(changes);
    }
}
