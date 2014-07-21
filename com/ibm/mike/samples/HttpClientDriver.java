package com.ibm.mike.samples;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Properties;
import java.util.Vector;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import org.apache.commons.codec.binary.Base64;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.auth.AuthScope;
// import org.apache.http.HttpHeaders;
// import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
// import org.apache.commons.httpclient.auth.AuthScope ;


import com.ibm.jvm.Dump;

/**
 *  Read parms and play poor man's RPT to generate workload in a threaded manner
 *  TODO: Look into special cookie considerations, verify multiThreading of httpClient (syntax/objects), find out why logging not on console
 */
public class HttpClientDriver {
	private static final String thisClass = HttpClientDriver.class.getName() ;
	private static Logger hcdLogger = Logger.getLogger(thisClass) ;
	
	private static final int URL_MAX_BYTES = 32000 ;
	private static final int MAX_UIDS = 50000 ;
	private static int numThdDumps = 0, delayMinutesForFirst = 0, intervalMinutesBetween = 0 ;
	private static int [] thdDumpMonIntervals = null ;
	private static PoolingHttpClientConnectionManager cm = null ;
	private static CloseableHttpClient httpClient = null ;
	private static boolean useAuth = false ;
	private static String [] authHeaders = null ;
	private static CredentialsProvider [] credentialsProvider = null ;
	private static AuthScope authScopeAll = new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT) ;

	/**
	 * thread that will go thru every n seconds (30 by dflt) and report on how many are thru based on each threads reporting.
	 * Threads report every 1000 sessions (7000 trans), so sometimes report does not show much change
	 */
	private class StatMan extends Thread {
		Vector<Integer>thdStatusVector ;
		long statIntervalMillis ;
		boolean stopWork = false ;
		ArrayList<Integer> totList = new ArrayList<Integer>(32) ;
		
		StatMan(long statIntervalSeconds, Vector<Integer>thdStatusVector) {
			this.thdStatusVector = thdStatusVector ;
			statIntervalMillis = statIntervalSeconds*1000 ;
		}
		
		public void stopCollection() {
			stopWork = true ;
		}
		
		public void run() {
			final String METHOD = "StatMan::run" ;
			int statIter = 0 ;
			while (!stopWork) {					// When last thread finishes, all reporting stops (even if others are not done)
				try {Thread.sleep(statIntervalMillis) ; } catch (Exception e) {}		// Sleep for reporting interval
				if (!stopWork) {		// Make sure not stopped while sleeping)
					statIter++ ;		// See if we need to take a javaCore/threadDump this interval
					boolean tookJCore = false ;					//  This thread also handles javaCores in the client if desired
					for (int i = 0; i < thdDumpMonIntervals.length; i++) {
						if (thdDumpMonIntervals[i] == statIter) {
							try {
								tookJCore = true ;
								Dump.JavaDump();
							} catch (Exception e) {}	// Throw out exception
						}
					}
		
					int tot = 0 ;
					for (int i = 0; i < thdStatusVector.size(); i++) {		// For each thread, get the total reported session completions
						tot += thdStatusVector.get(i).intValue() ;
					}
					if (totList.size() > 0) {
						int oldTot = totList.get(totList.size()-1) ;		// Report on it relative to prior iteration (unless this is first iter)
						System.out.println(statIter+": Tot Loops Reported: "+tot+" Last intvl: "+oldTot+" Delta: "+(tot-oldTot)+" JavaCore: "+tookJCore);
						hcdLogger.logp(Level.INFO, thisClass, METHOD, statIter+": Tot Loops Reported: "+tot+" Last intvl: "+oldTot+" Delta: "+(tot-oldTot)+" JavaCore: "+tookJCore);
					} else {
						System.out.println("First interval tot: "+tot) ;
						hcdLogger.logp(Level.INFO, thisClass, METHOD, "First interval tot: "+tot) ;
					}
					totList.add(tot) ;
				}
			}
		}
	}

	/**
	 * This is the thread started for each virtual user
	 */
	private class OneUser extends Thread {
		int thdIdx, lowUid, highUid, numCycles, reqPerSecond ;
		String [][] urlInfo ;
		URI [] baseURIs ;
		Vector<Integer>thdStatusVector ;
		HttpClientContext httpClientContext = null ;

		/**
		 * Save off all key information for this thread/user
		 * @param lowUid Lowest uid to be used by this thread
		 * @param highUid Highest uid to be used by this thread
		 * @param numCycles Number of cycles thru the session info
		 * @param urlInfo Double String Array w/URI info (not used for requests which have no var subst
		 * @param baseURIs For requests w/no variable substitution, URI is built once only
		 */
		OneUser(int thdIdx, int lowUid, int highUid, int numCycles, int reqPerSecond,
			String [][] urlInfo, URI [] baseURIs, Vector<Integer>thdStatusVector) { 
			this.thdIdx = thdIdx ;
			this.lowUid = lowUid ;
			this.highUid = highUid ;
			this.numCycles = numCycles ;
			this.reqPerSecond = reqPerSecond ;
			this.urlInfo = urlInfo ;
			this.baseURIs = baseURIs ;
			this.thdStatusVector = thdStatusVector ;
			if (useAuth) {
				int credProIdx = thdIdx % credentialsProvider.length ;
				httpClientContext = HttpClientContext.create() ;
				httpClientContext.setCredentialsProvider(credentialsProvider[credProIdx]) ;
				if (hcdLogger.isLoggable(Level.FINER))
					hcdLogger.logp(Level.FINER, thisClass+"OneUser", "constructor", 
						"Auth providerIdx: {0}  provider: {1}  httpCliCtx {2}",
						new Object [] { credProIdx, credentialsProvider[credProIdx].getCredentials(authScopeAll).getUserPrincipal(),
						httpClientContext.getCredentialsProvider().getCredentials(AuthScope.ANY).getUserPrincipal()});
			}
		}
		
		public void run() {
			final String METHOD = this.getName()+"run" ;
			int reqsThisSecond = 0, curUid = lowUid - 1, curAmt = 49 ;
			if (hcdLogger.isLoggable(Level.FINE)) {
				hcdLogger.logp(Level.FINE, thisClass, METHOD, "Start Thd: {0}  lowUid: {1}",
					new Object [] { this.getName(), lowUid});
			}
			long startSecond = System.currentTimeMillis() ;

			String curAuthStr = null  ;
    	    for (int i = 0; i < numCycles; i++) {		// One loop thru for each session/cycle
				if (i % 1000 == 0)  thdStatusVector.set(thdIdx, i);
				curAmt++ ;
				if (++reqsThisSecond > reqPerSecond) {
					long curSecond = System.currentTimeMillis() ;
					long millisPassed = curSecond - startSecond ;
					if (millisPassed < 1000) {
						try { Thread.sleep(1000-millisPassed); } catch (Exception e) {} ;
						if (hcdLogger.isLoggable(Level.FINE))
							hcdLogger.logp(Level.FINE, thisClass, METHOD, "Slept for {0} millis",
								(1000 - millisPassed)) ;
						startSecond = System.currentTimeMillis() ;
					} else  startSecond = curSecond ;		// Save sysClock capture if no sleep
					reqsThisSecond = 0 ;
				}
				if (++curUid > highUid) {
					curUid = lowUid ;  curAmt = 50 ;
				}
/*				if (useAuth) {
					if (curAuth >= authHeaders.length)  curUid = 0 ;
					curAuthStr = authHeaders[curAuth++] ;
				} */
//				sessionLoop1(curUid, curAmt) ;
				sessionLoop(curUid, curAmt, curAuthStr) ;
			}
			thdStatusVector.set(thdIdx, numCycles);
		}

		private void sessionLoop(int curUid, int curAmt, String authStr) {		
			final String METHOD = this.getName()+"sessionLoop" ;
			String sessionId = null ;
			for (int j = 0; j < baseURIs.length; j++) {		// In each cycle, go thru the URIs
				if (urlInfo[j] == null)  continue ;			// Could be last-line relic
				URI curURI = (baseURIs[j] != null) ? baseURIs[j] : buildURI(urlInfo[j], curUid, curAmt) ;
				// TODO: I should not have to mess so much w/session, will try to resolve later
       			// TODO: See if I can use reUse CloseableHttpResponse object
       			// TODO: Key point is to split out real request to be generic and retriable (retryCnt)
       			CloseableHttpResponse httpResponse = handleRetryableRequest(sessionId, curURI, 0, authStr) ;
       			if (httpResponse == null)  continue ;		// Exception/error logged in called method
       			HttpEntity htEntity = null ;
       			try {
					if (sessionId == null) {
						Header rspHdr = httpResponse.getFirstHeader("Set-Cookie") ;
						if (rspHdr != null)	sessionId = rspHdr.getValue() ;
					}
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
		}

		private CloseableHttpResponse handleRetryableRequest(String sessionId, URI curURI, int retryCnt, String authStr) {
			final String METHOD = this.getName()+"handleRetryableRequest" ;
			try {
				HttpGet httpGet = new HttpGet(curURI) ;
				if (sessionId != null)  httpGet.addHeader("Cookie", sessionId);
				httpGet.setHeader("Connection", "keep-alive");					
/*				if (useAuth) {
					httpGet.setHeader(HttpHeaders.AUTHORIZATION, authStr) ;
				}   This is out because preFilling headers caught other issues */
				if (hcdLogger.isLoggable(Level.FINER)) {
					for (Header curHdr : httpGet.getAllHeaders()) {
						hcdLogger.logp(Level.FINER, thisClass, METHOD, "httpGet header: ", curHdr.getValue()) ; 
					}
				}
				CloseableHttpResponse htResp = (useAuth) ? httpClient.execute(httpGet, httpClientContext) :
					httpClient.execute(httpGet) ;
				if (useAuth && hcdLogger.isLoggable(Level.FINER))
					hcdLogger.logp(Level.FINER, thisClass+"OneUser", METHOD, "statusCd: {0} httpCliCtx {1}",
						new Object [] { htResp.getStatusLine().getStatusCode(), 
						httpClientContext.getCredentialsProvider().getCredentials(AuthScope.ANY).getUserPrincipal()});

/*				if (htResp.getStatusLine().getStatusCode() == 401)
					throw new Exception("Got a 401, hopefully reTry will fix it") ; */
				return htResp ;
			} catch (Exception e) {
				if (retryCnt < 2) {	// Route exceptions, retry twice only
					hcdLogger.logp(Level.INFO, thisClass, METHOD, "RetryCnt: {0} curURI: {1}",
						new Object [] { retryCnt, curURI}) ;
					return handleRetryableRequest(sessionId, curURI, ++retryCnt, authStr) ;
				} else
					hcdLogger.logp(Level.WARNING, thisClass, METHOD, "Exception executing get request. URI: {0}  Exception: {1}",
						new Object [] { curURI, e }) ;
			}
			return null ;
		}

		private URI buildURI(String [] uriParts, int uid, int amt) {
			final String METHOD = "buildURI" ;
			StringBuilder sb = new StringBuilder(512) ;
			for (String curPart : uriParts) {
				if (curPart.startsWith("VarSub=")) {
					sb.append((curPart.substring(7).equals("amt")) ? amt : uid) ;
				} else  sb.append(curPart) ;
			}
			try {
				return new URIBuilder(sb.toString()).build() ;
			} catch (Exception e) {
				hcdLogger.logp(Level.WARNING, thisClass, METHOD, "Invalid URL string: {0}", sb.toString()) ;
				return null ;
			}
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
		HttpClientDriver me = new HttpClientDriver() ;
		if (args.length < 2) {
			hcdLogger.logp(Level.SEVERE, thisClass, METHOD, "Need 2 or more args, one for parm file name,"+
				"one for # clients. You spec'd {0}. Other args are number of javaCores (dflt 0), time to wait "+
				"before taking first javaCore (minutes), and time between javaCores (minutes)", args.length) ;
		} else {
			
			Properties parmProps = new Properties() ;
			try {
				parmProps.load(new FileInputStream(args[0]));
				// Now take host, urlfile, repeat, and rps and do the work (later debug and tocons)

				setupLogging(parmProps) ;		// Set up java logging based on

				setupAuth(parmProps);
				
				setupThreadDumps(args) ;

				BufferedReader br = new BufferedReader(new FileReader(parmProps.getProperty("urlFile"))) ;
				char [] cBuff = new char[URL_MAX_BYTES] ;
				int amtRead = br.read(cBuff, 0, URL_MAX_BYTES) ;
				if (amtRead < 100)
					hcdLogger.logp(Level.WARNING, thisClass, METHOD, "Fewer bytes than expected in URL file, bytes read: {0}", amtRead) ;
				br.close() ;
				String [] urlList = new String(cBuff).split("\\r?\\n") ;
				String [][] urlRepos = new String[urlList.length][] ;		// Split will always leave empty last
				URI [] urlContent = new URI [urlList.length] ;
				for (int i = 0; i < urlList.length; i++) {
					if (urlList[i].trim().length() < 8) continue ;
					if (hcdLogger.isLoggable(Level.FINER)) {
						hcdLogger.logp(Level.FINER, thisClass, METHOD, "  URL: {0}", urlList[i].trim());
					}
						// Could use setParameter on URIBuilder ... but easier at this point to keep URI for nonArgd and reBuild for argd
					
					urlRepos[i] = findVars(parmProps.getProperty("host")+urlList[i].trim()) ;
					urlContent[i] = (urlRepos[i].length > 1) ? null :
				       	new URIBuilder(urlRepos[i][0]).build() ;
				}
				
				int numThreads = Integer.parseInt(args[1]) ;
				int uidRange = MAX_UIDS / numThreads ;
				OneUser [] wrkThreads = new OneUser[numThreads] ; 
				int numCycles = Integer.parseInt(parmProps.getProperty("repeat")) ;
				int rps = Integer.parseInt(parmProps.getProperty("rps")) ;
				Vector<Integer> threadStatusVector = new Vector<Integer>(numThreads) ;
				threadStatusVector.setSize(numThreads);

				me.setupHttpClient() ;
				for (int i = 0; i < numThreads; i++) {
					wrkThreads[i] = me.new OneUser(i, uidRange*i+1, uidRange*i+uidRange, numCycles, rps,
						urlRepos, urlContent, threadStatusVector) ;
					wrkThreads[i].setName("WrkThread:"+i);
					wrkThreads[i].start();
				}
				StatMan statMan = me.new StatMan(30, threadStatusVector) ;
				statMan.start();
				wrkThreads[numThreads-1].join();					// Sleep until last thd completes
				statMan.stopCollection();
				System.out.println("Vector at end: "+threadStatusVector);
			} catch (Exception e) {
				hcdLogger.logp(Level.SEVERE, thisClass, METHOD, "Exception loading parms from file {0}  Exception was: {1}",
					new Object [] { args[0], e }) ;
				e.printStackTrace();
			}
		}
	}

	private static void setupLogging(Properties parmProps) {
		if (!"true".equalsIgnoreCase(parmProps.getProperty("tocons"))) {
			hcdLogger.setUseParentHandlers(false);
			for (java.util.logging.Handler curHandler : hcdLogger.getHandlers())
				hcdLogger.removeHandler(curHandler);
		}
		try {
			FileHandler fHandler = new FileHandler("/tmp/httpLogs/hcdLog%g.log", 2048*1024, 10);
			SimpleFormatter sFmt = new SimpleFormatter() ;
			hcdLogger.addHandler(fHandler);

			Level loggingLvl = Level.INFO ;
			if ("true".equalsIgnoreCase(parmProps.getProperty("debug"))) {
				loggingLvl = ("fine".equalsIgnoreCase(parmProps.getProperty("logLevel"))) ?	Level.FINE : Level.FINEST ;
			}
			hcdLogger.setLevel(loggingLvl);
			fHandler.setLevel(loggingLvl);  fHandler.setFormatter(sFmt);
		} catch (IOException e) {
			System.out.println("Exception setting up logging fileHandler: "+e);
		}
	}

	private static void setupThreadDumps(String [] args) {
		final String METHOD = "setupThreadDumps" ;
		numThdDumps = (args.length > 2) ? Integer.parseInt(args[2]) : 0 ;
		delayMinutesForFirst = (args.length > 3) ? Integer.parseInt(args[3]) : 2 ;
		intervalMinutesBetween = (args.length > 4) ? Integer.parseInt(args[4]) : 1 ;
			// For now, assuming monitoring interval is every 30 seconds, can add math later if needed
		if (numThdDumps > 0) {
			thdDumpMonIntervals = new int[numThdDumps] ;
			for (int i = 0; i < numThdDumps; i++) {
				if (i == 0)  thdDumpMonIntervals[i] = delayMinutesForFirst * 2 ;
				else thdDumpMonIntervals[i] = thdDumpMonIntervals[i-1] + (intervalMinutesBetween * 2) ;
			}
		}  else  thdDumpMonIntervals = new int [] { -1 } ;		// Show one entry array w/invalid interval
		hcdLogger.logp(Level.INFO, thisClass, METHOD, "Will take {0} javaCores. First wait is {1} and interval is {2}. Mon Intvls to dump on: {3}",
			new Object [] { numThdDumps, delayMinutesForFirst, intervalMinutesBetween, thdDumpMonIntervals }) ;
	}
	
	private static void setupAuth(Properties parmProps) {
		final String METHOD = "setupAuth" ;
		String uidParm = parmProps.getProperty("authIds") ;
		String pwParm = parmProps.getProperty("authPWs") ;
		if (hcdLogger.isLoggable(Level.FINE)) 
			hcdLogger.logp(Level.FINE, thisClass, METHOD, "Ids: {0}  Pws: {1}",
				new Object [] { uidParm, pwParm }) ;
		if (uidParm == null || pwParm == null) return ;		// Initial values assume no uid/pw
		useAuth = true ;
		String [] uidList = uidParm.split(",") ;
		String [] pwList = pwParm.split(",") ;
		authHeaders = new String[uidList.length] ;
		credentialsProvider = new CredentialsProvider[uidList.length] ;
		for (int i = 0 ; i < uidList.length; i++) {
			String authStr = uidList[i] + ":" + pwList[i] ;
			UsernamePasswordCredentials unpc = new UsernamePasswordCredentials(uidList[i], pwList[i]) ;
			credentialsProvider[i] = new BasicCredentialsProvider() ;
			credentialsProvider[i].setCredentials(AuthScope.ANY, unpc);
			if (hcdLogger.isLoggable(Level.FINER)) 
				hcdLogger.logp(Level.FINER, thisClass, METHOD, "Encode String {0}", authStr) ;
			byte [] encodedAuth = Base64.encodeBase64(authStr.getBytes(Charset.forName("US-ASCII"))) ;
			authHeaders[i] = "Basic "+ new String(encodedAuth) ;
		}
	}
	
	/**
	 * Not looking for totally optimal here since it is only done once at the beginning so that handling
	 * in the threads is optimal. Assumption is that url does not begin w/var substitution
	 * @param url
	 * @return
	 */
	private static String [] findVars(String url) {
		final String METHOD = "findVars" ;
		ArrayList<String> urlParts = new ArrayList<String>() ;
		String [] urlStrings = url.split("\\{") ;
		urlParts.add(urlStrings[0]) ;
		for (int i = 1 ; i < urlStrings.length; i++) {
			int varNmEnd = urlStrings[i].indexOf('}') ;
			if (varNmEnd < 1) {
				hcdLogger.logp(Level.WARNING, thisClass, METHOD, "Invalid URL for var format: {0}", url) ;
			} else {
				urlParts.add("VarSub="+urlStrings[i].substring(0, varNmEnd)) ;
				if (urlStrings[i].length() > varNmEnd+1)
					urlParts.add(urlStrings[i].substring(varNmEnd+1)) ;
			}
		}
		String [] urlStringReturns = new String [urlParts.size()] ;
		urlParts.toArray(urlStringReturns) ;
		return urlStringReturns ;
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
