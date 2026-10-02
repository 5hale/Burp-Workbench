package com.burpworkbench.modules.compare;

final class CompareItem {
    final long id;
    final byte[] bytes;
    final String kind, source;
    final RequestSnapshot request;
    CompareItem(long id, MessageCapture input) {
        this.id = id; bytes = input.bytes().clone(); kind = input.kind(); source = input.source();
        request=input.request();
    }
    CompareItem workingCopy(byte[] content) {
        return new CompareItem(id,new MessageCapture(content,kind,source,request));
    }
    long retainedBytes(){return (long)bytes.length+(request==null?0:request.length());}
    @Override public String toString() {
        String label = source.length() > 95 ? source.substring(0, 95) + "…" : source;
        return "#" + id + " · " + kind + " · " + label;
    }
}
