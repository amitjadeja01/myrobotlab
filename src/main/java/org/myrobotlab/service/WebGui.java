package org.myrobotlab.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.nettosphere.Config;
import org.atmosphere.nettosphere.Handler;
import org.atmosphere.nettosphere.Nettosphere;
import org.jboss.netty.handler.ssl.SslContext;
import org.jboss.netty.handler.ssl.util.SelfSignedCertificate;
import org.myrobotlab.codec.Api;
import org.myrobotlab.codec.CodecUtils;
import org.myrobotlab.framework.Message;
import org.myrobotlab.framework.MethodCache;
import org.myrobotlab.framework.Service;
import org.myrobotlab.framework.ServiceType;
import org.myrobotlab.framework.interfaces.ServiceInterface;
import org.myrobotlab.image.Util;
import org.myrobotlab.io.FileIO;
import org.myrobotlab.logging.Level;
import org.myrobotlab.logging.LoggerFactory;
import org.myrobotlab.logging.LoggingFactory;
import org.myrobotlab.net.BareBonesBrowserLaunch;
import org.myrobotlab.service.interfaces.AuthorizationProvider;
import org.myrobotlab.service.interfaces.Gateway;
import org.slf4j.Logger;

/**
 * 
 * WebGui - This service is the AngularJS based GUI TODO - messages &amp;
 * services are already APIs - perhaps a data API - same as service without the
 * message wrapper
 */
public class WebGui extends Service implements AuthorizationProvider, Gateway, Handler {

  /**
   * Static list of third party dependencies for this service. The list will be
   * consumed by Ivy to download and manage the appropriate resources
   */

  public static class LiveVideoStreamHandler implements Handler {

    @Override
    public void handle(AtmosphereResource r) {
      // TODO Auto-generated method stub
      try {

        AtmosphereResponse response = r.getResponse();
        // response.setContentType("video/mp4");
        // response.setContentType("video/x-flv");
        response.setContentType("video/avi");
        // FIXME - mime type of avi ??

        ServletOutputStream out = response.getOutputStream();

        byte[] data = FileIO.toByteArray(new File("test.avi.h264.mp4"));

        log.info("bytes {}", data.length);
        out.write(data);
        out.flush();
      } catch (Exception e) {
        log.error("stream handler threw", e);
      }
    }
  }

  public static class Panel {

    int height = 400;
    boolean hide = false;
    String name;
    int posX = 40;
    int posY = 20;
    int preferredHeight = 600;
    int preferredWidth = 800;
    String simpleName;
    int width = 400;
    int zIndex = 1;

    public Panel(String panelName) {
      this.name = panelName;
    }

    public Panel(String name, int x, int y, int z) {
      this.name = name;
      this.posX = x;
      this.posY = y;
      this.zIndex = z;
    }
  }

  // FIXME - move to security !
  transient private static final TrustManager DUMMY_TRUST_MANAGER = new X509TrustManager() {
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
    }

    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
      if (!TRUST_SERVER_CERT.get()) {
        throw new CertificateException("Server certificate not trusted.");
      }
    }

    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[0];
    }
  };

  public final static Logger log = LoggerFactory.getLogger(WebGui.class);

  private static final long serialVersionUID = 1L;

  transient private static final AtomicBoolean TRUST_SERVER_CERT = new AtomicBoolean(true);

  /**
   * needed to get the api key to select the appropriate api processor
   * 
   * @param r
   * @return
   */
  static public String getApiKey(String uri) {
    int pos = uri.indexOf(Api.PARAMETER_API);
    if (pos > -1) {
      pos += Api.PARAMETER_API.length();
      int pos2 = uri.indexOf("/", pos);
      if (pos2 > -1) {
        return uri.substring(pos, pos2);
      } else {
        return uri.substring(pos);
      }
    }
    return null;
  }

  public static class ApiDescription {
    String key;
    String path; // {scheme}://{host}:{port}/api/messages
    String exampleUri;
    String description;

    public ApiDescription(String key, String uriDescription, String exampleUri, String description) {
      this.key = key;
      this.path = uriDescription;
      this.exampleUri = exampleUri;
      this.description = description;
    }
  }

  // FIXME - move to security
  private static SSLContext createSSLContext2() {
    try {
      InputStream keyStoreStream = new FileInputStream((Util.getResourceDir() + "/keys/selfsigned.jks"));
      char[] keyStorePassword = "changeit".toCharArray();
      KeyStore ks = KeyStore.getInstance("JKS");
      ks.load(keyStoreStream, keyStorePassword);

      // Set up key manager factory to use our key store
      char[] certificatePassword = "changeit".toCharArray();
      KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
      kmf.init(ks, certificatePassword);

      // Initialize the SSLContext to work with our key managers.
      KeyManager[] keyManagers = kmf.getKeyManagers();
      TrustManager[] trustManagers = new TrustManager[] { DUMMY_TRUST_MANAGER };
      SecureRandom secureRandom = new SecureRandom();

      SSLContext sslContext = SSLContext.getInstance("TLS");
      sslContext.init(keyManagers, trustManagers, secureRandom);
      return sslContext;
    } catch (Exception e) {
      throw new Error("Failed to initialize SSLContext", e);
    }
  }

  /**
   * This static method returns all the details of the class without it having
   * to be constructed. It has description, categories, dependencies, and peer
   * definitions.
   * 
   * @return ServiceType - returns all the data
   * 
   */
  static public ServiceType getMetaData() {

    ServiceType meta = new ServiceType(WebGui.class.getCanonicalName());
    meta.addDescription("web display");
    meta.addCategory("connectivity", "display");

    meta.includeServiceInOneJar(true);
    meta.addDependency("org.atmosphere", "nettosphere", "3.0.13");
    meta.addDependency("javax.annotation", "javax.annotation-api", "1.3.2");

    // MAKE NOTE !!! - we currently distribute myrobotlab.jar with a webgui
    // hence these following dependencies are zipped with myrobotlab.jar !
    // and are NOT listed as dependencies, because they are already included

    // Its now part of myrobotlab.jar - unzipped in
    // build.xml (part of myrobotlab.jar now)

    // meta.addDependency("io.netty", "3.10.0"); // netty-3.10.0.Final.jar
    // meta.addDependency("org.atmosphere.nettosphere", "2.3.0"); //
    // nettosphere-assembly-2.3.0.jar
    // meta.addDependency("org.atmosphere.nettosphere", "2.3.0");//
    // geronimo-servlet_3.0_spec-1.0.jar
    return meta;
  }

  String address = "0.0.0.0";

  boolean autoStartBrowser = true;

  transient Broadcaster broadcaster;

  transient BroadcasterFactory broadcasterFactory;

  String currentDesktop = "default";

  transient Map<String, Map<String, Panel>> desktops;

  transient Nettosphere nettosphere;

  // SHOW INTERFACE
  // FIXME - allowAPI1(true|false)
  // FIXME - allowAPI2(true|false)
  // FIXME - allow Protobuf/Thrift/Avro

  transient AtmosphereResourceEventListenerAdapter onDisconnect;

  // FIXME might need to change to HashMap<String, HashMap<String,String>> to
  // add client session
  // TODO - probably should have getters - to publish - currently
  // just marking as transient to remove some of the data load 10240 max frame
  transient Map<String, Panel> panels;

  public Integer port;

  @Deprecated
  Map<String, List<String>> relays = new HashMap<>();

  public String root = "root";

  public Integer sslPort = null;

  public String startURL = "http://localhost:%d/#/main";

  transient LiveVideoStreamHandler stream = new LiveVideoStreamHandler();

  boolean useLocalResources = false;

  public WebGui(String n) {
    super(n);
    // api = ApiFactory.getInstance(this);
    if (desktops == null) {
      desktops = new HashMap<String, Map<String, Panel>>();
    }
    if (!desktops.containsKey(currentDesktop)) {
      panels = new HashMap<String, Panel>();
      desktops.put(currentDesktop, panels);
    } else {
      panels = desktops.get(currentDesktop);
    }

    subscribe("runtime", "registered");
    // FIXME - "unregistered" / "released"

    onDisconnect = new AtmosphereResourceEventListenerAdapter() {

      @Override
      public void onDisconnect(AtmosphereResourceEvent event) {
        String uuid = event.getResource().uuid();
        log.info("onDisconnect - {} {}", event, uuid);
        Runtime.removeConnection(uuid);
        Runtime.removeRoute(uuid);
        // sessions.remove(uuid);
        if (event.isCancelled()) {
          log.info("{} is canceled", uuid);
          // Unexpected closing. The client didn't send the close message when
          // request.enableProtocol
        } else if (event.isClosedByClient()) {
          // atmosphere.js has send the close message.
          // This API is only with 1.1 and up
          log.info("{} is closed by client", uuid);
        }
      }
    };
  }

  @Override // FIXME - implement
  public boolean allowExport(String serviceName) {
    // TODO Auto-generated method stub
    return false;
  }

  public void attach(String from, String to) throws IOException {
    attach(from, to, null);
  }

  // FIXME - relays to be done at a system level of Runtime.connections - not
  // here
  @Deprecated
  public void attach(String from, String to, String uri) throws IOException {

    // from --to--> to
    List<String> listOfUuid = null;
    if (relays.containsKey(from)) {
      listOfUuid = relays.get(from);
    } else {
      listOfUuid = new ArrayList<>();
      relays.put(from, listOfUuid);
    }

    if (!listOfUuid.contains(to)) {
      listOfUuid.add(to);
    }

    // to --to--> from
    if (relays.containsKey(to)) {
      listOfUuid = relays.get(to);
    } else {
      listOfUuid = new ArrayList<>();
      relays.put(to, listOfUuid);
    }

    if (!listOfUuid.contains(from)) {
      listOfUuid.add(from);
    }

    // Pipe pipe = new Pipe(from, to);
    // pipes.put(String.format("%s-%s", from, to), pipe);
  }

  public void autoStartBrowser(boolean autoStartBrowser) {
    this.autoStartBrowser = autoStartBrowser;
  }

  // FIXME - only broadcast to clients who have subscribed
  public void broadcast(Message msg) {
    try {
      if (broadcaster != null) {
        // single encoding ? should be double ?
        broadcaster.broadcast(CodecUtils.toJson(msg));
      }
    } catch (Exception e) {
      StringBuilder sb = new StringBuilder();
      if (msg.data != null) {
        for (Object o : msg.data) {
          sb.append(o.getClass().getCanonicalName());
        }
      }
      log.error("broadcast threw for data types {}", sb, e);
    }
  }

  public void broadcast(String str) {
    try {
      if (broadcaster != null) {
        broadcaster.broadcast(str); // wtf
      }
    } catch (Exception e) {
      log.error("broadcast threw", e);
    }
  }

  // broadcast to specific uuid
  public void broadcast(String uuid, String str) {
    Broadcaster broadcaster = getBroadcasterFactory().lookup(uuid);
    broadcaster.broadcast(str);
  }

  @Override
  public void connect(String uri) throws URISyntaxException {
    // TODO Auto-generated method stub

  }

  SSLContext createSSLContext() {
    try {
      if (sslPort != null) {
        return SSLContext.getInstance("TLS");
      }
    } catch (Exception e) {
      log.warn("can not make ssl context", e);
    }
    return null;
  }

  public void detach(String from, String to, String uri) {
    log.error("IMPLEMENT ME !");
  }

  public void extract() throws IOException {
    extract(false);
  }

  public void extract(boolean overwrite) throws IOException {

    // FIXME - check resource version vs self version
    // overwrite if different ? - would be in manifest

    FileIO.extractResources(overwrite);
  }

  public Broadcaster getBroadcaster() {
    return broadcaster;
  }

  public BroadcasterFactory getBroadcasterFactory() {
    return broadcasterFactory;
  }

  @Override
  public List<String> getClientIds() {
    return Runtime.getConnectionIds(getName());
  }

  @Override
  public Map<String, Map<String, Object>> getClients() {
    return Runtime.getConnections(getName());
  }

  public Config.Builder getConfig() {

    Config.Builder configBuilder = new Config.Builder();
    try {
      if (sslPort != null) {
        SelfSignedCertificate ssc = new SelfSignedCertificate();
        SslContext sslCtx = SslContext.newServerContext(ssc.certificate(), ssc.privateKey());
        configBuilder.sslContext(createSSLContext2());// .sslContext(sslCtx);
      }
    } catch (Exception e) {
      log.error("certificate creation threw", e);
    }

    configBuilder.resource("/stream", stream);
    // .resource("/video/ffmpeg.1443989700495.mp4", test)

    // FIRST DEFINED HAS HIGHER PRIORITY !! no virtual mapping of resources
    // for access after extracting :(
    configBuilder.resource("./resource/WebGui/app");
    configBuilder.resource("./resource");

    // for debugging
    // v- this makes http://localhost:8888/#/main worky
    configBuilder.resource("./src/main/resources/resource/WebGui/app");
    // v- this makes http://localhost:8888/react/index.html worky
    configBuilder.resource("./src/main/resources/resource/WebGui");
    // v- this makes http://localhost:8888/Runtime.png worky
    configBuilder.resource("./src/main/resources/resource");

    // for future references of resource - keep the html/js reference to
    // "resource/x" not "/resource/x" which breaks moving the app
    // FUTURE !!!
    configBuilder.resource("./src/main/resources");

    // can't seem to make this work .mappingPath("resource/")

    // TO SUPPORT LEGACY - BEGIN
    // for debugging
    /*
     * .resource("./src/main/resources/resource/WebGui/app")
     * .resource("./resource/WebGui/app")
     */

    // Support 2 APIs
    // REST - http://host/object/method/param0/param1/...
    // synchronous DO NOT SUSPEND
    configBuilder.resource("/api", this);

    // if Jetty is in the classpath it will use it by default - we
    // want to use Netty
    /* org.atmosphere.websocket.maxTextMessageSize */
    /*
     * noWorky :( .initParam("org.atmosphere.websocket.maxTextMessageSize","-1")
     * .initParam("org.atmosphere.websocket.maxBinaryMessageSize","-1")
     * 
     * .initParam(ApplicationConfig.WEBSOCKET_MAXTEXTSIZE,"-1")
     * .initParam(ApplicationConfig.WEBSOCKET_MAXBINARYSIZE,"-1")
     * .initParam(ApplicationConfig.WEBSOCKET_BUFFER_SIZE,"1000000")
     */

    configBuilder.initParam("org.atmosphere.cpr.asyncSupport", "org.atmosphere.container.NettyCometSupport");
    configBuilder.initParam(ApplicationConfig.SCAN_CLASSPATH, "false");
    configBuilder.initParam(ApplicationConfig.PROPERTY_SESSION_SUPPORT, "true").port(port).host(address); // all
    // ips

    SSLContext sslContext = createSSLContext();

    if (sslContext != null) {
      configBuilder.sslContext(sslContext);
    }
    // SessionSupport ss = new SessionSupport();

    configBuilder.build();
    return configBuilder;
  }

  public Map<String, String> getHeadersInfo(HttpServletRequest request) {

    Map<String, String> map = new HashMap<String, String>();

    /**
     * Atmosphere (nearly) always gives a ConcurrentModificationException its
     * supposed to be fixed in later versions - but later version have proven
     * very unstable
     */
    Enumeration<String> headerNames = request.getHeaderNames();
    while (headerNames.hasMoreElements()) {
      String key = (String) headerNames.nextElement();
      String value = request.getHeader(key);
      map.put(key.toLowerCase(), value);
    }
    return map;
  }

  /*
   * public Map<String, HttpSession> getSessions() { return sessions; }
   */

  // FIXME - mappings for ease of use
  /*
   * public String getUuid(String simpleName) { for (HttpSession client :
   * sessions.values()) { if (String.format("%s@%s",
   * client.getAttribute("user"),
   * client.getAttribute("host")).equals(simpleName)) { return
   * client.getAttribute("uuid").toString(); } } return null; }
   */

  public Integer getPort() {
    return port;
  }

  @Deprecated
  public Map<String, List<String>> getRelays() {
    return relays;
  }

  protected void setBroadcaster(String apiKey, AtmosphereResource r) {
    // FIXME - maintain single broadcaster for each session ?
    String uuid = r.uuid();

    Broadcaster uuiBroadcaster = getBroadcasterFactory().lookup(uuid);
    // create a unique broadcaster in the framework for this uuid
    if (uuiBroadcaster == null) {
      uuiBroadcaster = getBroadcasterFactory().get(uuid);
    }
    uuiBroadcaster.addAtmosphereResource(r);
    uuiBroadcaster.getAtmosphereResources();
    r.addBroadcaster(uuiBroadcaster);
    log.debug("resource {}", r);
    // log.debug("resource {}", StringUtil.toString(r.broadcasters()));
  }

  /**
   * This method handles all http:// and ws:// requests. Depending on apiKey
   * which is part of initial GET
   * 
   * messages api attempts to promote the connection to websocket and suspends
   * the connection for a 2 way channel
   */
  @Override
  public void handle(AtmosphereResource r) {

    boolean newConnection = false;

    try {

      String apiKey = getApiKey(r.getRequest().getRequestURI());

      // warning - r can change through the ws:// life-cycle
      // we upsert it to keep it fresh ;)
      newConnection = upsertConnection(r);

      if (apiKey.equals("messages")) {
        r.suspend();
        // FIXME - needed ?? - we use BroadcastFactory now !
        setBroadcaster(apiKey, r);
      }

      // default return encoding
      r.getResponse().addHeader("Content-Type", CodecUtils.MIME_TYPE_JSON);

      String uuid = r.uuid();
      AtmosphereRequest request = r.getRequest();

      String bodyData = request.body().asString();
      String logData = null;

      if ((bodyData != null) && log.isInfoEnabled()) {
        if (bodyData.length() > 180) {
          logData = String.format("%s ...", bodyData.substring(0, 179));
        } else {
          logData = bodyData;
        }
      } else if ((bodyData != null) && log.isDebugEnabled()) {
        logData = bodyData;
      }

      log.info(">>{} {} {} - [{}] from connection {}", (newConnection == true) ? "new" : "", request.getMethod(), request.getRequestURI(), logData, uuid);

      // get api - decode msg - process it
      Map<String, Object> connection = Runtime.getConnection(uuid);
      if (connection == null) {
        error("no connection with uuid %s", uuid);
        return;
      }

      MethodCache cache = MethodCache.getInstance();

      if (newConnection && apiKey.equals("messages")) {
        // new connection with messages api means we want to send a
        // getHelloResponse(hello) to the "NEW" client - we can do it because
        // its only 1 hop away and the outstream is connected to it
        // - we don't need to wait for a message from them with the outstream
        OutputStream out = r.getResponse().getOutputStream();
        Message msg = getDefaultMsg(uuid); // SEND BACK getHelloResponse(hello)
        out.write(CodecUtils.toJson(msg).getBytes());
        return;

      } else if (apiKey.equals("service")) {
        Message msg = CodecUtils.cliToMsg(getName(), null, r.getRequest().getPathInfo());

        if (isLocal(msg)) {
          String serviceName = msg.getName();
          Class<?> clazz = Runtime.getClass(serviceName);
          Object[] params = cache.getDecodedJsonParameters(clazz, msg.method, msg.data);
          msg.data = params;
          Object ret = invoke(msg);
          OutputStream out = r.getResponse().getOutputStream();
          out.write(CodecUtils.toJson(ret).getBytes());
        } else {
          // TODO - send it on its way - possibly do not decode the parameters
          // this would allow it to traverse mrl instances which did not
          // have the class definition !
          send(msg);
        }
        // FIXME - remove connection ! AND/OR figure out session
        return;
      }

      // ================= begin messages2 api =======================

      if (bodyData != null) {

        // decoding 1st pass - decodes the containers
        Message msg = CodecUtils.fromJson(bodyData, Message.class);
        msg.setProperty("uuid", uuid);

        // if were blocking -
        Message retMsg = null;
        Object ret = null;

        // its local if name does not have an "@" in it
        if (isLocal(msg)) {
          log.info("INVOKE {}", msg.toString());
          // process the msg...
          // to decode fully we need class name, method name, and an array of
          // json
          // encoded parameters

          String serviceName = msg.getName();
          Class<?> clazz = Runtime.getClass(serviceName);

          Object[] params = cache.getDecodedJsonParameters(clazz, msg.method, msg.data);

          Method method = cache.getMethod(clazz, msg.method, params);
          ServiceInterface si = Runtime.getService(serviceName);
          // higher level protocol - ordered steps to establish routing
          // must add meta data of connection to system
          if (msg.getName().equals(serviceName) && "getHelloResponse".equals(msg.method)) {
            // "fill-uuid" - FILLING UUID !!!! FOR THE FUNCTION - WOULDN'T IT BE
            // COOL IF FROM WITHIN A METHOD
            // YOU COULD GET msg.annoations ! - in the interim we have to do it
            // like this
            // thread data looked like a possibility ... it "could" be done with
            // using a msg key, or perhaps
            // msg.id = uuid + "-" + incremented
            params[0] = uuid;
          }
          ret = method.invoke(si, params);

          // propagate return data to subscribers
          si.out(msg.method, ret);

          // sender is important - this "might" be right ;)
          String sender = String.format("%s@%s", getName(), getId());

          // Tri-Input broadcast
          // if it was a blocking call return a serialized message back - must
          // switch return address original sender
          // with name
          // TODO - at some point we want the option of not trusting the
          // sender's return address
          retMsg = Message.createMessage(sender, msg.sender, CodecUtils.getCallbackTopicName(method.getName()), ret);

          // Invoked Locally with a "Blocking/Return" -
          // FIXME 2 different concepts - blocking vs return
          if (msg.isBlocking()) {
            retMsg.msgId = msg.msgId;
            send(retMsg);
          }
        } else {
          // msg came is and is NOT local - we will attempt to route it on its
          // way by sending it to send(msg)
          // RELAY !!!
          log.info("RELAY {}", msg);
          send(msg);
        }
      }
    } catch (Exception e) {
      log.error("handle threw", e);
    }
  }

  public boolean isLocal(Message msg) {
    return Runtime.getInstance().isLocal(msg);
  }

  private boolean upsertConnection(AtmosphereResource r) {
    Map<String, Object> attributes = new HashMap<>();
    String uuid = r.uuid();
    if (!Runtime.connectionExists(r.uuid())) {
      r.addEventListener(onDisconnect);
      AtmosphereRequest request = r.getRequest();
      Enumeration<String> headerNames = request.getHeaderNames();

      // required attributes - id ???/
      attributes.put("uuid", r.uuid());
      // so this is an interesting one .. getRequestURI is less descriptive than
      // getRequestURL
      // yet by RFC definition URL is a subset of URI .. wtf ?
      attributes.put("uri", r.getRequest().getRequestURL().toString());
      // attributes.put("url", r.getRequest().getRequestURL());
      attributes.put("host", r.getRequest().getRemoteAddr());
      attributes.put("gateway", getName());

      // connection specific
      attributes.put("c-r", r);
      attributes.put("c-type", "nettosphere");

      // cli specific
      attributes.put("cwd", "/");

      // addendum
      attributes.put("user", "root");

      while (headerNames.hasMoreElements()) {
        String headerName = headerNames.nextElement();
        Enumeration<String> headers = request.getHeaders(headerName);
        while (headers.hasMoreElements()) {
          String headerValue = headers.nextElement();
          attributes.put(String.format("header-%s", headerName), headerValue);
        }
      }
      Runtime.getInstance().addConnection(uuid, attributes);
      return true;
    } else {
      // keeping it "fresh" - the resource changes every request ..
      // it switches on
      Runtime.getConnection(uuid).put("c-r", r);
      return false;
    }
  }

  /**
   * handleMessagesApi handles all requests sent to /api/messages It
   * suspends/upgrades the connection to a websocket It is a asynchronous
   * protocol - and all messages are wrapped in a message wrapper.
   * 
   * The ApiFactory will handle the details of de-serializing but its necessary
   * to setup some protocol details here for websockets and session management
   * which the ApiFactory should not be concerned with.
   * 
   * @param r
   *          - request and response objects from Atmosphere server
   */
  public void handleMessagesApi(AtmosphereResource r) {
    try {
      AtmosphereResponse response = r.getResponse();
      AtmosphereRequest request = r.getRequest();
      OutputStream out = response.getOutputStream();

      if (!r.isSuspended()) {
        r.suspend();
      }
      response.addHeader("Content-Type", CodecUtils.MIME_TYPE_JSON);
      // api.process(this, out, r.getRequest().getRequestURI(),
      // request.body().asString());

    } catch (Exception e) {
      log.error("handleMessagesApi -", e);
    }
  }

  public void handleMessagesBlockingApi(AtmosphereResource r) {
    try {
      AtmosphereResponse response = r.getResponse();
      AtmosphereRequest request = r.getRequest();
      OutputStream out = response.getOutputStream();

      if (!r.isSuspended()) {
        r.suspend();
      }
      response.addHeader("Content-Type", CodecUtils.MIME_TYPE_JSON);

      // api.process(this, out, r.getRequest().getRequestURI(),
      // request.body().asString());

    } catch (Exception e) {
      log.error("handleMessagesBlockingApi -", e);
    }
  }

  public void handleServiceApi(AtmosphereResource r) throws Exception {
    AtmosphereRequest request = r.getRequest();
    AtmosphereResponse response = r.getResponse();
    OutputStream out = response.getOutputStream();
    response.addHeader("Content-Type", CodecUtils.MIME_TYPE_JSON);

    String hack = null;
    byte[] data = null;
    if (request.body() != null) {
      hack = request.body().asString();
      // data = request.body().asBytes();
      if (hack != null) { // FIXME - hack because request.body().asBytes()
        // ALWAYS returns null !!
        data = hack.getBytes();
      }
    }
    // api.process(out, r.getRequest().getRequestURI(), data);
  }

  public void hide(String name) {
    invoke("publishHide", name);
  }

  @Override
  public boolean isAuthorized(Map<String, Object> security, String serviceName, String method) {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public boolean isAuthorized(Message msg) {
    // TODO Auto-generated method stub
    return false;
  }

  public boolean isStarted() {
    if (nettosphere != null && nettosphere.isStarted()) {
      // is running
      info("WebGui is started");
      return true;
    }
    return false;

  }

  public Map<String, Panel> loadPanels() {
    return panels;
  }

  /*
   * FIXME - needs to be LogListener interface with
   * LogListener.onLogEvent(String logEntry) !!!! THIS SHALL LOG NO ENTRIES OR
   * ABANDON ALL HOPE !!!
   * 
   * This is completely out of band - it does not use the regular queues inbox
   * or outbox
   * 
   * We want to broadcast this - but THERE CAN NOT BE ANY log.info/warn/error
   * etc !!!! or there will be an infinite loop and you will be at the gates of
   * hell !
   * 
   */
  public void onLogEvent(Message msg) {
    try {
      if (broadcaster != null) {
        broadcaster.broadcast(CodecUtils.toJson(msg));
      }
    } catch (Exception e) {
      System.out.print(e.getMessage());
    }
  }

  public void onRegistered(ServiceInterface si) {
    // new service
    // subscribe to the status events
    subscribe(si.getName(), "publishStatus");
    subscribe(si.getName(), "publishState");

    // for distributed Runtimes
    if (si.isRuntime()) {
      subscribe(si.getName(), "registered");
    }

    invoke("publishPanel", si.getName());

    // broadcast it too
    // repackage message
    /*
     * don't need to do this :) Message m = createMessage(getName(),
     * "onRegistered", si); m.sender = Runtime.getInstance().getName();
     * broadcast(m);
     */
  }

  @Override
  public boolean preProcessHook(Message m) {
    // FIXME - problem with collisions of this service's methods
    // and dialog methods ?!?!?

    // broadcast
    broadcast(m);

    // if the method name is == to a method in the WebGui
    // process it
    if (methodSet.contains(m.method)) {
      // process the message like a regular service
      return true;
    }

    // otherwise send the message to the dialog with the senders name
    // broadcast(m);
    return false;
  }

  public String publishHide(String name) {
    return name;
  }

  public Panel publishPanel(String panelName) {

    Panel panel = null;
    if (panels.containsKey(panelName)) {
      panel = panels.get(panelName);
    } else {
      panel = new Panel(panelName);
      panels.put(panelName, panel);
    }
    return panel;
  }
  // === end positioning panels plumbing ===

  /*
   * public void put(String uuid, HttpSession s) { sessions.put(uuid, s); }
   */

  public void publishPanels() {
    for (String key : panels.keySet()) {
      invoke("publishPanel", key);
    }
  }

  public String publishShow(String name) {
    return name;
  }

  public boolean publishShowAll(boolean b) {
    return b;
  }

  /*
   * redirects browser to new url
   */
  public String redirect(String url) {
    return url;
  }

  public void restart() {
    stop();
    WebGui _self = this;

    // done so a thread "from" webgui can restart itself
    // similar thread within stop
    // From a web request you cannot block on a request to stop/start self
    new Thread() {
      public void run() {
        try {
          while (nettosphere != null) {
            Thread.sleep(500);
          }
        } catch (InterruptedException e) {
        }
        _self.start();
      }
    }.start();
    start();
  }

  public boolean save() {
    return super.save();
  }

  /**
   * From UI events --to--&gt; MRL request to save panel data typically done
   * after user has changed or updated the UI in position, height, width, zIndex
   * etc.
   * 
   * If you need MRL changes of position or UI changes use publishPanel to
   * remotely control UI
   * 
   * @param panel
   *          - the panel which has been moved or resized
   */
  public void savePanel(Panel panel) {
    if (panel.name == null) {
      log.error("panel name is null!");
      return;
    }
    panels.put(panel.name, panel);
    save();
  }

  @Override
  public void sendRemote(Message msg) {
    String uuid = Runtime.getRoute(msg.getId());
    Broadcaster broadcaster = getBroadcasterFactory().lookup(uuid);
    broadcaster.broadcast(CodecUtils.toJson(msg));
  }

  // === begin positioning panels plumbing ===
  public void set(String name, int x, int y) {
    set(name, x, y, 0); // or is z -1 ?
  }

  public void set(String name, int x, int y, int z) {
    Panel panel = null;
    if (panels.containsKey(name)) {
      panel = panels.get(name);
    } else {
      panel = new Panel(name, x, y, z);
    }
    invoke("publishPanel", panel);
  }

  public void setAddress(String address) {
    if (address != null) {
      this.address = address;
    }
  }

  public void setPort(Integer port) {
    this.port = port; // restart service ?
  }

  public void show(String name) {
    invoke("publishShow", name);
  }

  // TODO - refactor next 6+ methods to only us publishPanel
  public void showAll(boolean b) {
    invoke("publishShowAll", b);
  }

  public void start() {
    try {

      if (port == null) {
        port = 8888;
      }

      // Broadcaster b = broadcasterFactory.get();
      // a session "might" be nice - but for now we are stateless
      // SessionSupport ss = new SessionSupport();

      if (nettosphere != null && nettosphere.isStarted()) {
        // is running
        info("currently running on port %s - stop first, then start", port);
        return;
      }

      nettosphere = new Nettosphere.Builder().config(getConfig().build()).build();
      sleep(1000); // needed ?

      try {
        nettosphere.start();
      } catch (Exception e) {
        log.error("starting nettosphere failed", e);
      }

      broadcasterFactory = nettosphere.framework().getBroadcasterFactory();
      // get default boadcaster
      // GLOBAL - doesnt work because all come in with /api !
      // broadcaster = broadcasterFactory.get("/*");
      broadcaster = broadcasterFactory.lookup("/api"); // get("/api") throws
                                                       // because already
                                                       // created !

      log.info("WebGui {} started on port {}", getName(), port);
      // get all instances

      // we want all onState & onStatus events from all services
      for (ServiceInterface si : Runtime.getLocalServices().values()) {
        onRegistered(si);
      }

      // additionally we will want onState & onStatus events from all
      // services
      // from all new services which were created "after" the webgui
      // so susbcribe to our Runtimes methods of interest
      Runtime runtime = Runtime.getInstance();
      subscribe(runtime.getName(), "registered");
      subscribe(runtime.getName(), "released");

      if (autoStartBrowser) {
        log.info("auto starting default browser");
        BareBonesBrowserLaunch.openURL(String.format(startURL, port));
      }

    } catch (Exception e) {
      log.error("start threw", e);
    }
  }

  public void startBrowser(String URL) {
    BareBonesBrowserLaunch.openURL(String.format(URL, port));
  }

  public void startService() {
    super.startService();
    // extract all resources
    // if resource directory exists - do not overwrite !
    // could whipe out user mods
    try {
      extract();
    } catch (Exception e) {
      log.error("webgui start service threw", e);
    }

    start();
  }

  public void stop() {
    if (nettosphere != null) {
      log.warn("==== nettosphere STOPPING ====");
      // done so a thread "from" webgui can stop itself :P
      // Must not be called from a I/O-Thread to prevent deadlocks!
      new Thread() {
        public void run() {
          nettosphere.stop();
          nettosphere = null;
          log.warn("==== nettosphere STOPPED ====");
        }
      }.start();
    }
  }

  public void stopService() {
    super.stopService();
    stop();
  }

  /**
   * UseLocalResources determines if references to JQuery JavaScript library are
   * local or if the library is linked to using content delivery network.
   * Default (false) is to use the CDN
   *
   * @param useLocalResources
   *          - true uses local resources fals uses cdn
   */
  public void useLocalResources(boolean useLocalResources) {
    this.useLocalResources = useLocalResources;
  }

  @Override
  public Message getDefaultMsg(String connId) {
    return Runtime.getInstance().getDefaultMsg(connId);
  }

  public static void main(String[] args) {
    LoggingFactory.init(Level.INFO);

    try {

      Runtime.main(new String[] { "--interactive", "--id", "admin" });
      Runtime.start("python", "Python");
      
      // Arduino arduino = (Arduino)Runtime.start("arduino", "Arduino");
      WebGui webgui = (WebGui) Runtime.create("webgui", "WebGui");
      webgui.autoStartBrowser(false);
      webgui.setPort(8887);
      webgui.startService();
      

      log.info("leaving main");

    } catch (Exception e) {
      log.error("main threw", e);
    }
  }

  @Override
  public Object sendBlockingRemote(Message msg, Integer timeout) {
    String remoteId = msg.getId();
    Gateway gateway = Runtime.getGatway(remoteId);
    if (!gateway.equals(this)) {
      log.error("gateway for this msg is {} but its come to me {}", gateway.getName(), getName());
      return null;
    }

    // getRoute
    String toUuid = Runtime.getRoute(msg.getId());
    if (toUuid == null) {
      log.error("could not get uuid from this msg id {}", msg.getId());
      return null;
    }

    // get remote connection
    Map<String, Object> conn = Runtime.getConnection(toUuid);
    if (conn == null) {
      log.error("could not get connection for this uuid {}", toUuid);
      return null;
    }

    broadcast(toUuid, CodecUtils.toJson(msg));

    // FIXME !!! implement !!
    return null;
  }

}
