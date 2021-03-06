package com.blade.embedd;

import com.blade.Blade;
import com.blade.Const;
import com.blade.context.DynamicContext;
import com.blade.context.WebContextListener;
import com.blade.exception.EmbedServerException;
import com.blade.kit.CollectionKit;
import com.blade.kit.StringKit;
import com.blade.kit.base.Config;
import com.blade.mvc.DispatcherServlet;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.webapp.WebAppContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.http.HttpServlet;
import java.net.URL;
import java.util.EnumSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import static com.blade.Blade.$;

/**
 * Blade Jetty Server
 *
 * @author <a href="mailto:biezhi.me@gmail.com" target="_blank">biezhi</a>
 * @since 0.0.8
 */
public class EmbedJettyServer implements EmbedServer {

	private static final Logger LOGGER = LoggerFactory.getLogger(EmbedJettyServer.class);
	
    private int port = Const.DEFAULT_PORT;
	
	private Server server;

	private String classPath;

	private WebAppContext webAppContext;

	private Set<String> staticFolders;

	private Config config = null;
	
	public EmbedJettyServer() {
		System.setProperty("org.apache.jasper.compiler.disablejsr199", "true");

		config = Config.load("classpath:jetty.properties");
		config.add($().config());

		staticFolders = $().configuration().getResources();

		if(DynamicContext.isJarContext()){
			URL url = EmbedJettyServer.class.getResource("/");
			this.classPath = url.getPath();
			LOGGER.info("add classpath: {}", classPath);
		}

		$().enableServer(true);
	}
	
	@Override
	public void startup(int port) throws EmbedServerException {
		this.startup(port, "/", null);
	}

	@Override
	public void startup(int port, String contextPath) throws EmbedServerException {
		this.startup(port, contextPath, null);
	}
	
	@Override
	public void setWebRoot(String webRoot) {
		webAppContext.setResourceBase(webRoot);
	}
	
	@Override
	public void startup(int port, String contextPath, String webRoot) throws EmbedServerException {
		this.port = port;
		
		// Setup Threadpool
        QueuedThreadPool threadPool = new QueuedThreadPool();
        
        int minThreads = config.getInt("server.jetty.min-threads", 10);
        int maxThreads = config.getInt("server.jetty.max-threads", 100);
        
        threadPool.setMinThreads(minThreads);
        threadPool.setMaxThreads(maxThreads);
		threadPool.setName("blade-pool");
        
		server = new org.eclipse.jetty.server.Server(threadPool);
		
		// 设置在JVM退出时关闭Jetty的钩子。
        server.setStopAtShutdown(true);
        
        webAppContext = new WebAppContext();
        webAppContext.setContextPath(contextPath);
        webAppContext.setResourceBase("");
        
	    int securePort = config.getInt("server.jetty.http.secure-port", 8443);
	    int outputBufferSize = config.getInt("server.jetty.http.output-buffersize", 32768);
	    int requestHeaderSize = config.getInt("server.jetty.http.request-headersize", 8192);
	    int responseHeaderSize = config.getInt("server.jetty.http.response-headersize", 8192);
	    
	    // HTTP Configuration
        HttpConfiguration http_config = new HttpConfiguration();
        http_config.setSecurePort(securePort);
        http_config.setOutputBufferSize(outputBufferSize);
        http_config.setRequestHeaderSize(requestHeaderSize);
        http_config.setResponseHeaderSize(responseHeaderSize);
        http_config.setSendServerVersion(true);
        http_config.setSendDateHeader(false);
        
        long idleTimeout = config.getLong("server.jetty.http.idle-timeout", 30000L);
        
        ServerConnector http = new ServerConnector(server, new HttpConnectionFactory(http_config));
        http.setPort(this.port);
        http.setIdleTimeout(idleTimeout);
        server.addConnector(http);
	    
	    ServletHolder servletHolder = new ServletHolder(DispatcherServlet.class);
	    servletHolder.setAsyncSupported(false);
	    servletHolder.setInitOrder(1);

		webAppContext.addEventListener(new WebContextListener());
	    webAppContext.addServlet(servletHolder, "/");

		ServletHolder defaultHolder = new ServletHolder(DefaultServlet.class);
		if(StringKit.isNotBlank(classPath)){
			LOGGER.info("add classpath : {}", classPath);
			defaultHolder.setInitParameter("resourceBase", classPath);
		}

		for(String s : staticFolders){
			webAppContext.addServlet(defaultHolder, s);
		}

	    try {
	    	
	    	loadServlets(webAppContext);
		    loadFilters(webAppContext);
		    
		    HandlerList handlers = new HandlerList();
		    handlers.setHandlers(new Handler[] { webAppContext, new DefaultHandler() });
		    server.setHandler(handlers);
	    	server.start();
		    LOGGER.info("Blade Server Listen on 0.0.0.0:{}", this.port);
		} catch (Exception e) {
			throw new EmbedServerException(e);
		}
	}
	
	public void loadFilters(WebAppContext webAppContext) throws Exception{
		Map<Class<? extends Filter>, String[]> filters = Blade.$().filters();
		if(CollectionKit.isNotEmpty(filters)){
			Set<Entry<Class<? extends Filter>, String[]>> entrySet = filters.entrySet();
			for(Entry<Class<? extends Filter>, String[]> entry : entrySet){
				Class<? extends Filter> filterClazz = entry.getKey();
				String[] pathSpecs = entry.getValue();
				for(String pathSpec : pathSpecs){
					webAppContext.addFilter(filterClazz, pathSpec, EnumSet.of(DispatcherType.REQUEST));
				}
			}
		}
	}
	
	public void loadServlets(WebAppContext webAppContext) throws Exception{
		Map<Class<? extends HttpServlet>, String[]> servlets = Blade.$().servlets();
		if(CollectionKit.isNotEmpty(servlets)){
			Set<Entry<Class<? extends HttpServlet>, String[]>> entrySet = servlets.entrySet();
			for(Entry<Class<? extends HttpServlet>, String[]> entry : entrySet){
				Class<? extends HttpServlet> servletClazz = entry.getKey();
				String[] pathSpecs = entry.getValue();
				for(String pathSpec : pathSpecs){
					webAppContext.addServlet(servletClazz, pathSpec);
				}
			}
		}
	}
	
    public void shutdown() throws EmbedServerException {
        try {
			server.stop();
		} catch (Exception e) {
			throw new EmbedServerException(e);
		}
    }
    
    @Override
	public void join() throws EmbedServerException {
		try {
			server.join();
		} catch (InterruptedException e) {
			throw new EmbedServerException(e);
		}
	}
    
}