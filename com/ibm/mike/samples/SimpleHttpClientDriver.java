package com.ibm.mike.samples;

import java.io.IOException;
import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;

/**
 *  Simplified version of threaded to handle Basic Authentication
 */
public class SimpleHttpClientDriver {		// Logger, so logging.properties to control tracing
	private static final String thisClass = SimpleHttpClientDriver.class.getName() ;
	private static Logger hcdLogger = Logger.getLogger(thisClass) ;
	
	private static PoolingHttpClientConnectionManager cm = null ;
	private static CloseableHttpClient httpClient = null ;
	private static AuthScope authScope = null ;

	private static final String authHostDflt = "192.168.1.175" ;
	private static final int authPortDflt = 9085 ;
	private static String authHost ;
	private static int authPort ;
				// List of URIs that I will hit
	private final static String [] URIs = { "/CTLBankWeb/welcome.jsp", 
		"/CTLBankWeb/App?action=login&uid={uid}&passwd=password", "/CTLBankWeb/App?action=acctSummary",
	    "/CTLBankWeb/App?action=Deposit&amount={amt}&accID={uid}01", "/CTLBankWeb/App?action=Withdraw&amount={amt}&accID={uid}01",
	    "/CTLBankWeb/App?action=custProfile", "/CTLBankWeb/App?action=logout" } ;
			// Userids and passwords to create credentials
	private static final String [] uidList = {"mobusr1", "mobusr2", "webusr1", "webusr2", "webusr3", "webusr4", "webusr5" } ;
	private static final String [] pwList = {"mobusr1", "mobusr2", "webusr1", "webusr2", "webusr3", "webusr4", "webusr5" } ;

	/**
	 * This is the thread started for each virtual user
	 */
	private class OneUser extends Thread {
		String dbUid, dbAmt ;
		HttpClientContext httpClientContext = null ;

		/**
		 * Save off all key information for this thread/user
		 * @param lowUid Lowest uid to be used by this thread
		 * @param highUid Highest uid to be used by this thread
		 * @param numCycles Number of cycles thru the session info
		 * @param urlInfo Double String Array w/URI info (not used for requests which have no var subst
		 * @param baseURIs For requests w/no variable substitution, URI is built once only
		 */
		OneUser(int thdIdx, int iUid, int iAmt, String sUid, String sPw, AuthScope authScope) {
			dbUid = String.valueOf(iUid) ;
			dbAmt = String.valueOf(iAmt) ;
			httpClientContext = HttpClientContext.create() ;		// Unique clientContext per thread

			CredentialsProvider  credentialsProvider = new BasicCredentialsProvider() ;	// Credentials per thread
			credentialsProvider.setCredentials(authScope, new UsernamePasswordCredentials(sUid, sPw));
			
			httpClientContext.setCredentialsProvider(credentialsProvider) ;
			AuthCache authCache = new BasicAuthCache() ;		// Not sure if cache needed
			BasicScheme basicScheme = new BasicScheme() ;
			authCache.put(new HttpHost(authScope.getHost(), authScope.getPort()), basicScheme) ;
				
			httpClientContext.setAuthCache(authCache);
			if (hcdLogger.isLoggable(Level.FINER))
				hcdLogger.logp(Level.FINER, thisClass+"OneUser", "constructor",	"Provider: {0}  httpCliCtx {1}",
					new Object [] { credentialsProvider.getCredentials(authScope).getUserPrincipal(),
					httpClientContext.getCredentialsProvider().getCredentials(AuthScope.ANY).getUserPrincipal()});
		}
		
		public void run() {
			final String METHOD = this.getName()+"run" ;
			try {
				String hostSpec = "http://"+authHost+":"+authPort ;
				for (String curURIStr : URIs) {
					while ((curURIStr.indexOf("{uid}")) > -1)
						curURIStr = curURIStr.replace("{uid}", dbUid) ;
					while ((curURIStr.indexOf("{amt}")) > -1)
						curURIStr = curURIStr.replace("{amt}", dbAmt) ;
					URI curURI = new URIBuilder(hostSpec+curURIStr).build() ;
	       			CloseableHttpResponse httpResponse = handleRetryableRequest(curURI, 0) ;
	       			if (httpResponse == null)  continue ;		// Exception/error logged in called method
	       			HttpEntity htEntity = null ;
	       			try {
						htEntity = httpResponse.getEntity() ;
						if (hcdLogger.isLoggable(Level.FINE)) {
							hcdLogger.logp(Level.FINE, thisClass, METHOD, "URI: {0}  StatusCd: {1}", 
								new Object [] { curURI, httpResponse.getStatusLine().getStatusCode()}) ;
							if (hcdLogger.isLoggable(Level.FINER)) {
								for (Header curHdr : httpResponse.getAllHeaders()) {
									hcdLogger.logp(Level.FINER, thisClass, METHOD, "httpResponse header: ", curHdr.getElements()) ; 
								}
								hcdLogger.logp(Level.FINER, thisClass, METHOD, "Results: {0}", 
									(htEntity != null) ? EntityUtils.toString(htEntity) : "NoEntity") ;
							}
						}
						if (htEntity != null)  EntityUtils.consume(htEntity);
	       			} catch (Exception e) {
	    				hcdLogger.logp(Level.WARNING, thisClass, METHOD, "Exception wrappering get request. URI: {0}  Exception: {1}",
	    						new Object [] { curURI, e }) ;
	    				e.printStackTrace();
	       			} finally {
	    				try { 
	    					if (httpResponse != null)  {
	    						if (htEntity != null)  EntityUtils.consume(htEntity);
	    						httpResponse.close();	
	    					} 
	    				}catch (IOException e) { }
	       			}
				}
			} catch (Exception e) {
				System.out.println("Caught exception : "+e);
				e.printStackTrace() ;
			}
		}

		private CloseableHttpResponse handleRetryableRequest(URI curURI, int retryCnt) {
			final String METHOD = this.getName()+"handleRetryableRequest" ;
			try {
				HttpGet httpGet = new HttpGet(curURI) ;
				httpGet.setHeader("Connection", "keep-alive");					
				if (hcdLogger.isLoggable(Level.FINER)) {
					for (Header curHdr : httpGet.getAllHeaders()) {
						hcdLogger.logp(Level.FINER, thisClass, METHOD, "httpGet header: ", curHdr.getValue()) ; 
					}
				}			// execute w/context local to the thread
				CloseableHttpResponse htResp = httpClient.execute(httpGet, httpClientContext) ;
				if (hcdLogger.isLoggable(Level.FINER))
					hcdLogger.logp(Level.FINER, thisClass+"OneUser", METHOD, "statusCd: {0} httpCliCtx {1}",
						new Object [] { htResp.getStatusLine().getStatusCode(), 
						httpClientContext.getCredentialsProvider().getCredentials(authScope).getUserPrincipal()});

				return htResp ;
			} catch (Exception e) {
				if (retryCnt < 2) {	// Route exceptions, retry twice only
					hcdLogger.logp(Level.INFO, thisClass, METHOD, "RetryCnt: {0} curURI: {1}",
						new Object [] { retryCnt, curURI}) ;
					return handleRetryableRequest(curURI, ++retryCnt) ;
				} else
					hcdLogger.logp(Level.WARNING, thisClass, METHOD, "Exception executing get request. URI: {0}  Exception: {1}",
						new Object [] { curURI, e }) ;
			}
			return null ;
		}
	}
	
	/**
	 * Consume parms file and number of clients and get the party started
	 * @param args <parmFile> <NumberOfClients> [<numThdDumps> [<delayMinutesForFirst> [<intervalMinutesBetween]]]
	 * numThdDumps dflts to 0. delayMinutesForFirst dflts to 120. intervalMinutesBetween dflts to 60.
	 * So dflt behavior is no client ThdDumps. Dflt if you say 3 (the norm) is wait 2 minutes then take one
	 * then wait 1 minute then take a second, then wait 1 more minute then take a 3rd.  The initial wait
	 * and interval between can be tweaked w/the parms. Using minutes so monitoring thread can do it when awake
	 */
	public static void main(String [] args) {
		final String METHOD = "main" ;
		SimpleHttpClientDriver me = new SimpleHttpClientDriver() ;
		if (hcdLogger.isLoggable(Level.FINE))
			hcdLogger.logp(Level.FINE, thisClass, METHOD, "parms optional: <host> <port>") ;
		authHost = (args.length > 0) ? args[0] : authHostDflt ;
		authPort = (args.length > 1) ? Integer.parseInt(args[1]) : authPortDflt ;
		if (hcdLogger.isLoggable(Level.FINE))
			hcdLogger.logp(Level.FINE, thisClass, METHOD, "Starting w/host: {0} Port: {1}",
				new Object [] { authHost, authPort}) ;
		OneUser [] wrkThreads = new OneUser[uidList.length] ;
		try {
			authScope = new AuthScope(authHost, authPort) ;
			me.setupHttpClient() ;		// Set up client, then construct/init/start each thread
			for (int i = 0; i < uidList.length; i++) {
				wrkThreads[i] = me.new OneUser(i, i, i+10, uidList[i], pwList[i], authScope) ;
				wrkThreads[i].setName("WrkThread:"+i);
				wrkThreads[i].start();
			}
			wrkThreads[uidList.length-1].join();					// Sleep until last thd completes
		} catch (Exception e) {
			hcdLogger.logp(Level.SEVERE, thisClass, METHOD, "Exception in main processing: {0}", e) ;
			e.printStackTrace();
		}
	}

	
	public void setupHttpClient() {
		final String METHOD = "setupHttpClient" ;
		cm = new PoolingHttpClientConnectionManager();
		cm.setMaxTotal(256);
		cm.setDefaultMaxPerRoute(128) ;	
		httpClient = HttpClients.createMinimal(cm) ;
		if (hcdLogger.isLoggable(Level.FINER))
			hcdLogger.logp(Level.FINER, thisClass, METHOD, "Constructing cm/httpClient for cbc obj: {0}", this) ;
	}

	protected void finalize() {
		try {
			super.finalize() ;
		} catch (Throwable e) {
			hcdLogger.logp(Level.WARNING, thisClass, "finalize", "Exception in super.finalize(): {0}", e) ;
			e.printStackTrace();
		}
		if (httpClient != null)		try {	httpClient.close() ;  } catch (IOException e) { }
	}
}