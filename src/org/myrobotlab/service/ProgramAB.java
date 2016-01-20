package org.myrobotlab.service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.alicebot.ab.AIMLMap;
import org.alicebot.ab.AIMLSet;
import org.alicebot.ab.Bot;
import org.alicebot.ab.Category;
import org.alicebot.ab.Chat;
import org.alicebot.ab.Predicates;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.myrobotlab.framework.Service;
import org.myrobotlab.logging.LoggingFactory;
import org.myrobotlab.programab.OOBPayload;
import org.myrobotlab.service.interfaces.ServiceInterface;
import org.myrobotlab.service.interfaces.TextListener;
import org.myrobotlab.service.interfaces.TextPublisher;

/**
 * Program AB service for MyRobotLab Uses AIML 2.0 to create a ChatBot This is a
 * reboot of the Old AIML spec to be more 21st century.
 *
 * More Info at http://aitools.org/ProgramAB
 * 
 * @author kwatters
 *
 */
public class ProgramAB extends Service implements TextListener, TextPublisher {

	public static class Response {
		public String session;
		public String msg;
		public List<OOBPayload> payloads;
		public Date timestamp;

		public Response(String session, String msg, List<OOBPayload> payloads, Date timestamp) {
			this.session = session;
			this.msg = msg;
			this.payloads = payloads;
			this.timestamp = timestamp;
		}
	}

	private transient Bot bot = null;

	private String path = "ProgramAB";
	private String botName = "alice2";
	private String currentUser = "default"; // this 'becomes' the session name

	private transient HashMap<String, Chat> sessions = new HashMap<String, Chat>();
	// TODO: better parsing than a regex...
	private transient Pattern oobPattern = Pattern.compile("<oob>.*?</oob>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.MULTILINE);
	private transient Pattern mrlPattern = Pattern.compile("<mrl>.*?</mrl>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.MULTILINE);

	private boolean processOOB = true;

	// TODO: this should be per session, and probably not global
	private Date lastResponseTime = null;
	// Number of milliseconds before the robot starts talking on its own.
	private int maxConversationDelay = 5000;

	// boolean to turn on and off the auto conversation logic.
	private boolean enableAutoConversation = false;

	private static final long serialVersionUID = 1L;
	
	private static int savePredicatesInterval = 60 * 1000 * 5; // every 5 minutes

	public ProgramAB(String name) {
		super(name);
		// we started..
		
		// Tell programAB to persist it's learned predicates about people
		// every 30 seconds.
		addTask("savePredicates", savePredicatesInterval, "savePredicates");
		
	}

	public void addOOBTextListener(TextListener service) {
		addListener("publishOOBText", service.getName(), "onOOBText");
	}

	public void addResponseListener(Service service) {
		addListener("publishResponse", service.getName(), "onResponse");
	}

	public void addTextListener(TextListener service) {
		addListener("publishText", service.getName(), "onText");
	}

	public void addTextPublisher(TextPublisher service) {
		addListener("publishText", service.getName(), "onText");
	}

	private void cleanOutOfDateAimlIFFiles(String botName, String path) {
		String aimlPath = path + File.separator + "bots" + File.separator + botName + File.separator + "aiml";
		String aimlIFPath = path + File.separator + "bots" + File.separator + botName + File.separator + "aimlif";
		log.info("AIML FILES:");
		File folder = new File(aimlPath);
		if (!folder.exists()) {
			// TODO: what if the folder doesn't exist probably nothing to clean
			// TODO: should we create the folder if we can?
			// folder.mkdirs();
			return;
		}

		System.out.println(folder.getAbsolutePath());
		HashMap<String, Long> modifiedDates = new HashMap<String, Long>();
		for (File f : folder.listFiles()) {
			log.info(f.getAbsolutePath());
			// TODO: better stripping of the file extension
			String aiml = f.getName().replace(".aiml", "");
			modifiedDates.put(aiml, f.lastModified());
		}
		log.info("AIMLIF FILES:");
		folder = new File(aimlIFPath);
		if (!folder.exists()) {
			// TODO: throw an exception warn / log ?
			log.info("aimlif directory missing,creating it. " + folder.getAbsolutePath());
			folder.mkdirs();
			return;
		}
		for (File f : folder.listFiles()) {
			log.info(f.getAbsolutePath());
			// TODO: better stripping of the file extension
			String aimlIF = f.getName().replace(".aiml.csv", "");
			Long lastMod = modifiedDates.get(aimlIF);
			if (lastMod != null) {
				if (f.lastModified() < lastMod) {
					// the AIMLIF file is newer than the AIML file.
					// delete the AIMLIF file so ProgramAB recompiles it
					// properly.
					log.info("Deleteing AIMLIF file because the original AIML file was modified. {}", aimlIF);
					f.delete();
				}
			}
		}
	}

	private String createSessionPredicateFilename(String session) {
		// TODO: sanitize the session label so it can be safely used as a
		// filename
		String predicatePath = path + File.separator + "bots" + File.separator + botName + File.separator + "config";
		
		// just in case the directory doesn't exist.. make it.
		File predDir = new File(predicatePath);
		if (!predDir.exists()) {
			predDir.mkdirs();
		}
		
		predicatePath += File.separator + session + ".predicates.txt";
		return predicatePath;
	}

	@Override
	public String[] getCategories() {
		return new String[] { "intellegence" };
	}

	@Override
	public String getDescription() {
		return "AIML 2.0 Reference interpreter based on Program AB";
	}

	public int getMaxConversationDelay() {
		return maxConversationDelay;
	}

	public Response getResponse(String text) {
		return getResponse(null, text);
	}

	/**
	 * 
	 * @param text
	 *            - the query string to the bot brain
	 * @param userId
	 *            - the user that is sending the query
	 * @param robotName
	 *            - the name of the bot you which to get the response from
	 * @return
	 */
	public Response getResponse(String session, String text) {
		log.info("Get Response for : "  + text);
		if (session == null) {
			session = currentUser;
		}
		if (bot == null) {
			String error = "ERROR: Core not loaded, please load core before chatting.";
			error(error);
			return new Response(session, error, null, new Date());
		}
		if (!sessions.containsKey(session)) {
			startSession(path, session, botName);
		}
		String res = sessions.get(session).multisentenceRespond(text);
		// grab and update the time when this response came in.
		lastResponseTime = new Date();

		// Check the AIML response to see if there is OOB (out of band data)
		// If so, publish that data independent of the text response.
		List<OOBPayload> payloads = null;
		if (processOOB) {
			payloads = processOOB(res);
		}

		// OOB text should not be published as part of the response text.
		Matcher matcher = oobPattern.matcher(res);
		res = matcher.replaceAll("").trim();

		Response response = new Response(session, res, payloads, lastResponseTime);
		// Now that we've said something, lets create a timer task to wait for N
		// seconds
		// and if nothing has been said.. try say something else.
		// TODO: trigger a task to respond with something again
		// if the humans get bored
		if (enableAutoConversation) {
			// schedule one future reply. (always get the last word in..)
			// int numExecutions = 1;
			// TODO: we need a way for the task to just execute one time
			// it'd be good to have access to the timer here, but it's transient
			addTask("getResponse", maxConversationDelay, "getResponse", session, text);
		}

		// EEK! clean up the API!
		invoke("publishResponse", response);
		invoke("publishResponseText", response);
		invoke("publishText", response.msg);
		info("to: %s - %s", session, res);

		//		if (log.isDebugEnabled()) {
		//			for (String key : sessions.get(session).predicates.keySet()) {
		//				log.debug(session + " " + key + " " + sessions.get(session).predicates.get(key));
		//			}
		//		}

		// TODO: wire this in so the gui updates properly. ??
		// broadcastState();

		return response;
	}

	public void removePredicate(String session, String predicateName) {
		Predicates preds = sessions.get(session).predicates;
		preds.remove(predicateName);
	}

	public void addToSet(String setName, String setValue) {
		// add to the set for the bot.
		AIMLSet updateSet = bot.setMap.get(setName);
		if (updateSet != null) {
			setValue = setValue.toUpperCase().trim();
			updateSet.add(setValue);
			// persist to disk.
			updateSet.writeAIMLSet();
		} else {
			log.warn("Unknown AIML set: {} was attempted to be updated. ", setName);
			// TODO: should we create a new set ? or just log this warning?
		}
	}

	public void addToMap(String mapName, String mapKey, String mapValue) {
		// add an entry to the map.
		AIMLMap updateMap = bot.mapMap.get(mapName);
		if (updateMap != null) {
			mapKey = mapKey.toUpperCase().trim();
			updateMap.put(mapKey, mapValue);
			// persist to disk!
			updateMap.writeAIMLMap();
		} else {
			log.warn("Unknown AIML map: {} was attempted to be updated. ", mapName);
			// dynamically create new maps?!
		}
	}

	
	public void setPredicate(String session, String predicateName, String predicateValue) {
		Predicates preds = sessions.get(session).predicates;
		preds.put(predicateName, predicateValue);
	}

	public String getPredicate(String session, String predicateName) {
		Predicates preds = sessions.get(session).predicates;
		return preds.get(predicateName);
	}

	
	
	/**
	 * Only respond if the last response was longer than delay ms ago
	 * 
	 * @param session
	 *            - current session/username
	 * @param text
	 *            - text to get a response for
	 * @param delay
	 *            - min amount of time that must have transpired since the last
	 *            response.
	 * @return
	 */
	public Response getResponse(String session, String text, Long delay) {
		long delta = System.currentTimeMillis() - lastResponseTime.getTime();
		if (delta > delay) {
			return getResponse(session, text);
		} else {
			return null;
		}

	}

	public boolean isEnableAutoConversation() {
		return enableAutoConversation;
	}

	public boolean isProcessOOB() {
		return processOOB;
	}

	/**
	 * Return a list of all patterns that the AIML Bot knows to match against.
	 * 
	 * @param botName
	 * @return
	 */
	public ArrayList<String> listPatterns(String botName) {
		ArrayList<String> patterns = new ArrayList<String>();
		for (Category c : bot.brain.getCategories()) {
			patterns.add(c.getPattern());
		}
		return patterns;
	}

	/**
	 * Return the number of milliseconds since the last response was given -1 if
	 * a response has never been given.
	 * 
	 * @return
	 */
	public long millisecondsSinceLastResponse() {
		if (lastResponseTime == null) {
			return -1;
		}
		long delta = System.currentTimeMillis() - lastResponseTime.getTime();
		return delta;
	}

	@Override
	public void onText(String text) {
		// What else should we do here? seems reasonable to just do this.
		// this should actually call getResponse
		// on input, get the proper response
		Response resp = getResponse(text);
		// push that to the next end point.
		// invoke("publishText", resp.msg);
	}

	private OOBPayload parseOOB(String oobPayload) {

		// TODO: fix the damn double encoding issue.
		// we have user entered text in the service/method 
		// and params values.
		// grab the service
		Pattern servicePattern = Pattern.compile("<service>(.*?)</service>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.MULTILINE);
		Matcher serviceMatcher = servicePattern.matcher(oobPayload);
		serviceMatcher.find();
		String serviceName = serviceMatcher.group(1);
		
		Pattern methodPattern = Pattern.compile("<method>(.*?)</method>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.MULTILINE);
		Matcher methodMatcher = methodPattern.matcher(oobPayload);
		methodMatcher.find();
		String methodName = methodMatcher.group(1);

		
		Pattern paramPattern = Pattern.compile("<param>(.*?)</param>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.MULTILINE);
		Matcher paramMatcher = paramPattern.matcher(oobPayload);
		ArrayList<String> params = new ArrayList<String>();
		while (paramMatcher.find()) {
			// We found some OOB text.
			// assume only one OOB in the text?
			String param = paramMatcher.group(1);
			params.add(param);
		}
		OOBPayload payload = new OOBPayload(serviceName, methodName, params);
		// log.info(payload.toString());
		return payload;
		
		// JAXB stuff blows up because the response from program ab is already xml decoded!  
		//
//		JAXBContext jaxbContext;
//		try {
//			jaxbContext = JAXBContext.newInstance(OOBPayload.class);
//			Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
//			log.info("OOB PAYLOAD :" + oobPayload);
//			Reader r = new StringReader(oobPayload);
//			OOBPayload oobMsg = (OOBPayload) jaxbUnmarshaller.unmarshal(r);
//			return oobMsg;
//		} catch (JAXBException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}

//		log.info("OOB tag found, but it's not an MRL tag. {}", oobPayload);
//		return null;
	}

	private List<OOBPayload> processOOB(String text) {
		// Find any oob tags
		ArrayList<OOBPayload> payloads = new ArrayList<OOBPayload>();
		Matcher oobMatcher = oobPattern.matcher(text);
		while (oobMatcher.find()) {
			// We found some OOB text.
			// assume only one OOB in the text?
			String oobPayload = oobMatcher.group(0);
			Matcher mrlMatcher = mrlPattern.matcher(oobPayload);
			while (mrlMatcher.find()) {
				String mrlPayload = mrlMatcher.group(0);
				OOBPayload payload = parseOOB(mrlPayload);
				payloads.add(payload);
				// TODO: maybe we dont' want this?
				// Notifiy endpoints
				invoke("publishOOBText", mrlPayload);
				// grab service and invoke method.
				ServiceInterface s = Runtime.getService(payload.getServiceName());
				if (s == null) {
					log.warn("Service name in OOB/MRL tag unknown. {}", mrlPayload);
					return null;
				}
				// TODO: should you be able to be synchronous for this
				// execution?
				Object result = null;
				if (payload.getParams() != null) {
					result = s.invoke(payload.getMethodName(), payload.getParams().toArray());
				} else {
					result = s.invoke(payload.getMethodName());
				}
				log.info("OOB PROCESSING RESULT: {}", result);
			}
		}
		if (payloads.size() > 0) {
			return payloads;
		} else {
			return null;
		}
	}

	/**
	 * If a response comes back that has an OOB Message, publish that separately
	 * 
	 * @param response
	 * @return
	 */
	public String publishOOBText(String oobText) {
		return oobText;
	}

	/**
	 * publishing method of the pub sub pair - with addResponseListener allowing
	 * subscriptions pub/sub routines have the following pattern
	 * 
	 * publishing routine -> publishX - must be invoked to provide data to
	 * subscribers subscription routine -> addXListener - simply adds a Service
	 * listener to the notify framework any service which subscribes must
	 * implement -> onX(data) - this is where the data will be sent (the
	 * call-back)
	 * 
	 * @param response
	 * @return
	 */
	public Response publishResponse(Response response) {
		return response;
	}

	/**
	 * Test only publishing point - for simple consumers
	 * 
	 * @param response
	 * @return
	 */
	public String publishResponseText(Response response) {
		return response.msg;
	}

	@Override
	public String publishText(String text) {
		return text;
	}

	public void reloadSession(String path, String botName) {
		reloadSession(path, null, botName);
	}

	public void reloadSession(String path, String session, String botName) {
		if (session == null) {
			session = currentUser;
		}
		// kill the bot
		bot = null;
		// kill the session
		if (sessions.containsKey(session)) {
			// TODO: will garbage collection clean up the bot now ?
			// Or are there other handles to it?
			sessions.remove(session);
		}
		startSession(path, session, botName);
	}

	/**
	 * Persist the predicates for all known sessions in the robot.
	 * 
	 * @throws IOException
	 * 
	 */
	public void savePredicates() throws IOException {
		for (String session : sessions.keySet()) {
			String sessionPredicateFilename = createSessionPredicateFilename(session);
			File sessionPredFile = new File(sessionPredicateFilename);
			Chat chat = sessions.get(session);
			// overwrite the original file , this should always be a full set.
			log.info("Writing predicate file for session {}", session);
			FileWriter predWriter = new FileWriter(sessionPredFile, false);
			for (String predicate : chat.predicates.keySet()) {
				String value = chat.predicates.get(predicate);
				predWriter.write(predicate + ":" + value + "\n");
			}
			predWriter.close();
		}
		log.info("Done saving predicates.");
	}

	public void setEnableAutoConversation(boolean enableAutoConversation) {
		this.enableAutoConversation = enableAutoConversation;
	}

	public void setMaxConversationDelay(int maxConversationDelay) {
		this.maxConversationDelay = maxConversationDelay;
	}

	public void setProcessOOB(boolean processOOB) {
		this.processOOB = processOOB;
	}

	public void startSession() {
		startSession(null);
	}

	public void startSession(String session) {
		startSession(path, session, botName);
	}

	/**
	 * Load the AIML 2.0 Bot config and start a chat session. This must be
	 * called after the service is created.
	 * 
	 * @param path
	 *            - should be the full path to the ProgramAB root
	 * @param botName
	 *            - The name of the bot to load. (example: alice2)
	 */
	public void startSession(String progABPath, String botName) {
		startSession(progABPath, null, botName);
	}


	public void startSession(String path, String session, String botName) {
		
		// TODO: this is probably not the right thing to do.
		// means all sessions and bots are loaded from the same directory...
		this.path = path;
		this.botName = botName;
		if (session == null) {
			session = currentUser;
		} 
		// when we create a new session. lets assume that's the current
		// default user. (this can also be used from the ui.
		currentUser = session;
		
		if (sessions.containsKey(session)) {
			warn("session %s already created", session);
			return;
		}
		// TODO don't allow to specify a different path
		// it will be assumed to be ./ProgramAB
		cleanOutOfDateAimlIFFiles(botName, path);
		if (bot == null) {
			bot = new Bot(botName, path);
		}
		//if (log.isDebugEnabled()) {
			//for (Category c : bot.brain.getCategories()) {
			//	log.debug(c.getPattern());
			//}
		//}
		Chat chat = new Chat(bot);
		// load session specific predicates, these override the default ones.
		String sessionPredicateFilename = createSessionPredicateFilename(session);
		chat.predicates.getPredicateDefaults(sessionPredicateFilename);
		
		sessions.put(session, chat);
		// lets test if the robot knows the name of the person in the session
		String name = chat.predicates.get("name").trim();
		// TODO: this implies that the default value for "name" is default
		// "Friend"
		if (name == null || "Friend".equalsIgnoreCase(name) || "unknown".equalsIgnoreCase(name)) {
			// TODO: find another interface that's simpler to use for this
			// create a string that represents the predicates file
			String inputPredicateStream = "name:" + session;
			// load those predicates
			chat.predicates.getPredicateDefaultsFromInputStream(IOUtils.toInputStream(inputPredicateStream));

		}
		this.botName = botName;
		String userName = chat.predicates.get("name");
		log.info("Started session for {} , username {}", session, userName);
		// TODO: to make sure if the start session is updated, that the button
		// updates in the gui ?
		broadcastState();
	}

	public void writeAIML() {
		bot.writeAIMLFiles();
	}

	public void writeAIMLIF() {
		bot.writeAIMLIFFiles();
	}

	public void writeAndQuit() {
		bot.writeQuit();
	}

	public static void main(String s[]) throws IOException {
		LoggingFactory.getInstance().configure();
		LoggingFactory.getInstance().setLevel("INFO");
		Python py = (Python)Runtime.createAndStart("python", "Python");
		String script = FileUtils.readFileToString(new File("src/resource/Python/examples/Wordnet.py"));
		py.exec(script);
		String sessionName = null;
		if (false) {
			Runtime.createAndStart("gui", "GUIService");
			Runtime.createAndStart("webgui", "WebGui");
			if (true) {
				ProgramAB alice = (ProgramAB) Runtime.createAndStart("alice2", "ProgramAB");
				alice.setEnableAutoConversation(false);
				alice.startSession(sessionName);
				Response response = alice.getResponse(sessionName, "CONVERSATION_SEED_STRING");
				log.info("Alice " + response.msg);
			} else {
				ProgramAB lloyd = (ProgramAB) Runtime.createAndStart("lloyd", "ProgramAB");
				lloyd.startSession("ProgramAB", sessionName, "lloyd");
				Response response = lloyd.getResponse(sessionName, "Hello.");
				log.info("Lloyd " + response.msg);
			}
		}
		
		
	
	}
	
}