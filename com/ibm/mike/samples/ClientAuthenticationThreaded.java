package com.ibm.mike.samples;
/*
 * TODO: Once this works, threadIze it with context
 * TODO: Threadizing almost works, but need Session gorp
 * TODO: Go to poolable client
 * TODO: Once that works, look at cache benefits
 * TODO: Then move to URIs instead of strings
 * TODO: Then retryable part
 */
import org.apache.http.Header;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

/**
 * A simple example that uses HttpClient to execute an HTTP request against
 * a target site that requires user authentication.
 */
public class ClientAuthenticationThreaded {

	// List of URIs that I will hit
	private final static String [] URIs = { "/CTLBankWeb/welcome.jsp", 
		"/CTLBankWeb/App?action=login&uid=XXX&passwd=password", "/CTLBankWeb/App?action=logout" } ;
	private static CloseableHttpClient httpClient;

	private class OneUser extends Thread {
		private HttpClientContext httpClientContext = null ;
		private String urlUid ;
		private String tThd = null ;
		private AuthScope authScope = null ;

		OneUser(String sUid, String sPw, String urlUid, String host, int port) {
			httpClientContext = HttpClientContext.create() ;		// Unique clientContext per thread
	        CredentialsProvider credsProvider = new BasicCredentialsProvider();
	        authScope = new AuthScope(host, port) ;
	        credsProvider.setCredentials(authScope, new UsernamePasswordCredentials(sUid, sPw));
	        System.out.println("CredPrincipal: "+credsProvider.getCredentials(authScope).getUserPrincipal()) ;
			httpClientContext.setCredentialsProvider(credsProvider) ;
			this.urlUid = urlUid ;
		}

		public void run() {
			if (tThd == null) tThd = Thread.currentThread().getName() ;
			try {
				String hostSpec = "http://192.168.1.175:9084" ;
				String sessionId = null ;
				for (String curURIStr : URIs) {
					if (curURIStr.indexOf("XXX") > -1)
						curURIStr = curURIStr.replace("XXX", urlUid) ;
	        		HttpGet httpGet = new HttpGet(hostSpec+curURIStr);
					if (sessionId != null)  httpGet.addHeader("Cookie", sessionId);
					httpGet.setHeader("Connection", "keep-alive");					
	        		System.out.println(tThd+" Executing request " + httpGet.getRequestLine()+" Sess: "+sessionId+
	        			" Ctx: "+httpClientContext+" Cred: "+httpClientContext.getCredentialsProvider().getCredentials(authScope));
	        		CloseableHttpResponse httpResponse = httpClient.execute(httpGet, httpClientContext);
	        		if (sessionId == null) {
	        			for (Header cookieHdr : httpResponse.getHeaders("Set-Cookie")) {
	        				sessionId = cookieHdr.getValue() ;
	        				if (sessionId.indexOf("JSESSIONID") < 0) sessionId = null ;
	        				else {
	        					int spacePos = sessionId.indexOf(" ") ;
	        					if (spacePos > 0) sessionId = sessionId.substring(0,  spacePos) ;
	        				}
	        			}
	        		}
	        		try {
	        			System.out.println("----------------------------------------");
	        			System.out.println(tThd+": "+httpResponse.getStatusLine());
	        			EntityUtils.consume(httpResponse.getEntity());
	        		} finally {
	        			httpResponse.close();
	        		}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

	}
	
    public static void main(String[] args) throws Exception {
    	ClientAuthenticationThreaded me = new ClientAuthenticationThreaded() ;
        httpClient = HttpClients.custom().build();
/*		cm = new PoolingHttpClientConnectionManager();
		cm.setMaxTotal(256);
		cm.setDefaultMaxPerRoute(128) ;	
		httpClient = HttpClients.createMinimal(cm) ; */


        OneUser firstUser = me.new OneUser("mobusr1", "mobusr1", "2", "192.168.1.175", 9084) ;
        firstUser.start();
        OneUser secondUser = me.new OneUser("webusr1", "webusr1", "4", "192.168.1.175", 9084) ;
        secondUser.start();
        secondUser.join();
    }
}