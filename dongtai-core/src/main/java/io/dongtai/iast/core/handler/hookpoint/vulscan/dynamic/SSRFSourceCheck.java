package io.dongtai.iast.core.handler.hookpoint.vulscan.dynamic;

import io.dongtai.iast.core.handler.hookpoint.models.IastSinkModel;
import io.dongtai.iast.core.handler.hookpoint.models.MethodEvent;
import io.dongtai.iast.core.utils.TaintPoolUtils;
import io.dongtai.log.DongTaiLog;

import java.net.URI;
import java.net.URL;
import java.util.*;

public class SSRFSourceCheck {
    private static final String JAVA_NET_URL_OPEN_CONNECTION = "java.net.URL.openConnection()";
    private static final String JAVA_NET_URL_OPEN_CONNECTION_PROXY = "java.net.URL.openConnection(java.net.Proxy)";
    private static final String JAVA_NET_URL_OPEN_STREAM = "java.net.URL.openStream()";

    private static final String APACHE_HTTP_CLIENT_REQUEST_SET_URI = " org.apache.http.client.methods.HttpRequestBase.setURI(java.net.URI)".substring(1);


    private static final Set<String> SSRF_SINK_METHODS = new HashSet<>(Arrays.asList(
            JAVA_NET_URL_OPEN_CONNECTION,
            JAVA_NET_URL_OPEN_CONNECTION_PROXY,
            JAVA_NET_URL_OPEN_STREAM,
            APACHE_HTTP_CLIENT_REQUEST_SET_URI
    ));

    public static boolean isSinkMethod(IastSinkModel sink) {
        return SSRF_SINK_METHODS.contains(sink.getSignature());
    }

    public static boolean sourceHitTaintPool(MethodEvent event, IastSinkModel sink) {
        boolean hitTaintPool = false;
        if (JAVA_NET_URL_OPEN_CONNECTION.equals(sink.getSignature())
                || JAVA_NET_URL_OPEN_CONNECTION_PROXY.equals(sink.getSignature())
                || JAVA_NET_URL_OPEN_STREAM.equals(sink.getSignature())) {
            return javaNetURLSourceHit(event, sink);
        } else if (APACHE_HTTP_CLIENT_REQUEST_SET_URI.equals(sink.getSignature())) {
            return apacheHttpClientRequestSetURISourceHit(event, sink);
        }
        return hitTaintPool;
    }

    private static boolean javaNetURLSourceHit(MethodEvent event, IastSinkModel sink) {
        try {
            if (!(event.object instanceof URL)) {
                return false;
            }

            URL url = (URL) event.object;
            String protocol = url.getProtocol();
            String userInfo = url.getUserInfo();
            String host = url.getHost();
            String path = url.getPath();
            String query = url.getQuery();

            event.inValue = url.toString();
            return addSourceType(event, protocol, userInfo, host, path, query);
        } catch (Exception e) {
            DongTaiLog.warn("java.net.URL get protocol and host failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean apacheHttpClientRequestSetURISourceHit(MethodEvent event, IastSinkModel sink) {
        try {
            if (event.argumentArray.length < 1 || !(event.argumentArray[0] instanceof URI)) {
                return false;
            }

            URI uri = (URI) event.argumentArray[0];

            String protocol = uri.getScheme();
            String authority = uri.getUserInfo();
            String host = uri.getHost();
            String path = uri.getPath();
            String query = uri.getPath();

            event.inValue = uri.toString();
            return addSourceType(event, protocol, authority, host, path, query);
        } catch (Exception e) {
            DongTaiLog.warn("apache http client get protocol and host failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean addSourceType(MethodEvent event, String protocol, String userInfo, String host, String path, String query) {
        boolean hit1 = !"".equals(protocol) && TaintPoolUtils.poolContains(protocol, event);
        boolean hit2 = !"".equals(userInfo) && TaintPoolUtils.poolContains(userInfo, event);
        boolean hit3 = !"".equals(host) && TaintPoolUtils.poolContains(host, event);
        boolean hit4 = !"".equals(path) && TaintPoolUtils.poolContains(path, event);
        boolean hit5 = !"".equals(query) && TaintPoolUtils.poolContains(query, event);
        event.inValue = event.object.toString();
        boolean hit = hit1 || hit2 || hit3 || hit4 || hit5;
        if (hit && event.sourceTypes == null) {
            event.sourceTypes = new ArrayList<MethodEvent.MethodEventSourceType>();
            if (hit1) {
                event.sourceTypes.add(new MethodEvent.MethodEventSourceType(System.identityHashCode(protocol), "PROTOCOL"));
            }
            if (hit2) {
                event.sourceTypes.add(new MethodEvent.MethodEventSourceType(System.identityHashCode(userInfo), "USERINFO"));
            }
            if (hit3) {
                event.sourceTypes.add(new MethodEvent.MethodEventSourceType(System.identityHashCode(host), "HOST"));
            }
            if (hit4) {
                event.sourceTypes.add(new MethodEvent.MethodEventSourceType(System.identityHashCode(path), "PATH"));
            }
            if (hit5) {
                event.sourceTypes.add(new MethodEvent.MethodEventSourceType(System.identityHashCode(query), "QUERY"));
            }
        }
        return hit;
    }
}
