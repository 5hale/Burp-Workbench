package com.burpworkbench.modules.replace;

import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.net.URI;
import java.util.Locale;
import java.util.function.Consumer;

/** Original-URL matching in list order; no request is sent or duplicated here. */
final class ForwardEngine {
    record Destination(String host,int port,boolean secure,String path) {
        Destination(String host,int port,boolean secure){this(host,port,secure,"");}
        String authority(){
            String shown=host.contains(":")?"["+host+"]":host;
            return shown+(port==(secure?443:80)?"":":"+port);
        }
        String origin(){return (secure?"https://":"http://")+authority();}
    }
    static Destination destination(String value) {
        if(value==null||value.length()>4096)throw new IllegalArgumentException("Destination is too long");
        URI uri;
        try{uri=URI.create(value.trim());}catch(IllegalArgumentException error){throw new IllegalArgumentException("Use an http(s) destination origin");}
        String scheme=uri.getScheme(),host=uri.getHost();
        if(scheme==null||host==null||!(scheme.equalsIgnoreCase("http")||scheme.equalsIgnoreCase("https"))
                ||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null
                ||uri.getRawPath()!=null&&!uri.getRawPath().isEmpty()&&!uri.getRawPath().equals("/"))
            throw new IllegalArgumentException("Enter the server in Destination URL and the path in Destination Path");
        boolean secure=scheme.equalsIgnoreCase("https");int port=uri.getPort()<0?(secure?443:80):uri.getPort();
        if(port<1||port>65535)throw new IllegalArgumentException("Invalid destination port");
        if(host.startsWith("["))host=host.substring(1,host.length()-1);
        return new Destination(host.toLowerCase(Locale.ROOT),port,secure);
    }
    static void validate(ForwardRule rule) {
        Destination target=destination(rule.destination());
        destinationPath(rule.destinationPath());
        // Validate conditions even when no live request is available.
        ScopeMatcher.matches(rule.stored(),target.origin()+"/");
        if(!rule.path().isBlank())ScopeMatcher.pathMatches(rule.path(),"/");
    }
    static String destinationPath(String path) {
        if(path.isBlank())return "";
        if(path.length()>16*1024||!path.startsWith("/")||path.startsWith("//"))
            throw new IllegalArgumentException("Destination Path must start with a single /");
        URI uri;
        try{uri=URI.create(path);}catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Invalid Destination Path; encode spaces as %20");}
        if(uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getRawAuthority()!=null)
            throw new IllegalArgumentException("Destination Path accepts a path only; existing query is preserved");
        return path;
    }
    static Destination destination(ForwardRule rule) {
        Destination target=destination(rule.destination());
        return new Destination(target.host(),target.port(),target.secure(),destinationPath(rule.destinationPath()));
    }
    static String mappedPath(Destination target,String currentPath) {
        if(target.path().isEmpty())return currentPath;
        int query=currentPath.indexOf('?');
        return target.path()+(query<0?"":currentPath.substring(query));
    }
    static String preview(ForwardRule rule,String sourceUrl) {
        validate(rule);
        if(!ScopeMatcher.matches(rule.stored(),sourceUrl))return "No match";
        URI uri=URI.create(sourceUrl);String path=uri.getRawPath();
        if(path==null||path.isEmpty())path="/";
        Destination target=destination(rule);
        return target.origin()+mappedPath(target,path+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery()));
    }
    static Destination select(ForwardSession.State state,String originalUrl,Consumer<String> issues) {
        if(state==null||!state.enabled())return null;
        Destination result=null;long deadline=System.nanoTime()+250_000_000L;int applied=0;
        for(ForwardRule rule:state.rules()){
            if(!rule.enabled())continue;
            if(System.nanoTime()-deadline>=0){issues.accept("FORWARD_SCOPE_LIMIT");break;}
            try{
                if(!ScopeMatcher.matches(rule.stored(),originalUrl))continue;
                if(++applied>1000){issues.accept("FORWARD_RULE_LIMIT");break;}
                result=destination(rule);
            }catch(RuntimeException invalid){issues.accept("FORWARD_INVALID_RULE");}
        }
        return result;
    }
    static HttpRequest apply(HttpRequest request,Destination destination) {
        // withService controls the real connection; Host alone is insufficient.
        return apply(request,destination,HttpService.httpService(destination.host(),destination.port(),destination.secure()));
    }
    static HttpRequest apply(HttpRequest request,Destination destination,HttpService service) {
        HttpRequest result=request.withService(service).withHeader("Host",destination.authority());
        if(!destination.path().isEmpty())result=result.withPath(mappedPath(destination,request.path()));
        return result;
    }
    private ForwardEngine(){}
}
