package com.burpworkbench.modules.compare;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;

/** Bounded immutable request data, with no retained Burp message or project object. */
final class RequestSnapshot {
    private final byte[] bytes;
    final String host;
    final int port;
    final boolean secure;
    RequestSnapshot(byte[] bytes,String host,int port,boolean secure){
        if(bytes==null||bytes.length>MessageCapture.MAX_ITEM)throw new IllegalArgumentException("Request snapshot exceeds 1 MiB.");
        if(host==null||host.isBlank()||port<1||port>65535)throw new IllegalArgumentException("Request service unavailable.");
        this.bytes=bytes.clone();this.host=host;this.port=port;this.secure=secure;
    }
    static RequestSnapshot capture(HttpRequest request){
        if(request==null)return null;HttpService service=request.httpService();if(service==null)return null;
        ByteArray raw=request.toByteArray();if(raw==null||raw.length()>MessageCapture.MAX_ITEM)return null;
        return new RequestSnapshot(raw.getBytes(),service.host(),service.port(),service.secure());
    }
    int length(){return bytes.length;}
    byte[] bytes(){return bytes.clone();}
    HttpRequest toRequest(){return HttpRequest.httpRequest(HttpService.httpService(host,port,secure),ByteArray.byteArray(bytes));}
}
