package org.myrobotlab.net;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Iterator;

import org.myrobotlab.codec.Encoder;
import org.myrobotlab.framework.MRLListener;
import org.myrobotlab.framework.Message;
import org.myrobotlab.framework.ServiceEnvironment;
import org.myrobotlab.logging.LoggerFactory;
import org.myrobotlab.logging.Logging;
import org.myrobotlab.service.RemoteAdapter;
import org.myrobotlab.service.Runtime;
import org.myrobotlab.service.interfaces.CommunicationInterface;
import org.myrobotlab.service.interfaces.ServiceInterface;
import org.slf4j.Logger;

public class TCPThread2 extends Thread {

	public final static Logger log = LoggerFactory.getLogger(TCPThread2.class);

	// FIXME - should be Gateway not Service
	// FIXME - Communication interface - with logMsgs(bool)
	RemoteAdapter myService;
	public Socket socket;
	public Connection data;
	ObjectInputStream in;
	ObjectOutputStream out;
	boolean isRunning = false;
	URI protocolKey;
	URI uri; // mrl uri

	// debug / logging
	private transient FileOutputStream msgLog = null;

	public TCPThread2(RemoteAdapter service, URI uri, Socket socket) throws UnknownHostException, IOException {
		super(String.format("%s:%s", service.getName(), uri));

		this.myService = service;
		this.data = new Connection(service.getName(), uri);
		if (socket == null) {
			socket = new Socket(uri.getHost(), uri.getPort());
		}
		this.socket = socket;
		out = new ObjectOutputStream((socket.getOutputStream()));
		out.flush();
		in = new ObjectInputStream(socket.getInputStream());
		this.start();

		msgLog = new FileOutputStream(String.format("%s.%d.json", service.getName(), System.currentTimeMillis()));
	}

	// FIXME - prepare for re-init / or completely de-init
	// and have RA re-establish connection
	public void releaseConnect() {
		try {
			data.state = Connection.DISCONNECTED;
			String instanceID = String.format("mrl://%s/%s", myService.getName(), data.protocolKey);
			log.error("removing {} from registry", instanceID);
			// FIXME - not working - are you sure you want to do this?
			// just because the connection is broken
			Runtime.release(new URI(instanceID));
			log.error("shutting down thread");
			isRunning = false;
			log.error("attempting to close streams");
			in.close();
			out.close();
			log.error("attempting to close socket");
			socket.close();
		} catch (Exception dontCare) {
		}
	}

	/**
	 * listening for inbound messages
	 */
	@Override
	public void run() {
		try {
			isRunning = true;
			data.state = Connection.CONNECTED;
			while (socket != null && isRunning) {

				Message msg = null;
				Object o = null;

				o = in.readObject();
				msg = (Message) o;
				++data.rx;
				// nice for debugging
				if (msgLog != null) {
					msgLog.write(String.format("%s <-- %s - %s\n", myService.getName(), uri, Encoder.toJson(msg)).getBytes());
				}
				

				data.rxSender = msg.sender;
				data.rxSendingMethod = msg.sendingMethod;
				data.rxName = msg.name;
				data.rxMethod = msg.method;

				if (uri == null) {
					protocolKey = new URI(String.format("tcp://%s:%d", socket.getInetAddress().getHostAddress(), socket.getPort()));
					String mrlURI = String.format("mrl://%s/%s", myService.getName(), protocolKey.toString());
					uri = new URI(mrlURI);
				}

				/**
				 * mrl works similar to - router x-forwarded in that it
				 * re-writes names in order to provide an abstraction to a
				 * remote system. This can prevent name collision and add
				 * clarity to remote system names - msg sender / name re-write
				 * are trivial - the danger & difficulty comes when names are
				 * embedded in the data payload - such as register, addListener
				 * and other(?) methods - 
				 * for example - service names as parameters !
				 */
				String xForwardSender = String.format("%s%s", myService.getPrefix(protocolKey), msg.sender);

				// router x-forwarded inbound proxy begin
				msg.sender = xForwardSender;
				// router x-forwarded inbound proxy end

				// FIXME - SCARY ! - anywhere address (name) info is in the data
				// payload you will get errors & bugs :(
				// getName() would need to be there of couse... I can't imagine
				// how many other places ..
				// Not the best implementation - an Instance would

				// FIXME - HashSet of methods needed ?
				// FIXME - if Encode.getMethodSignature("publishState",
				// Service.class).equals(Encode.getMethodSignature(msg));
				if ("publishState".equals(msg.method)) {
					// FIXME - normalize
					// router x-forwarded inbound proxy begin
					Object[] msgData = msg.data;
					ServiceInterface si = null;

					if (msgData != null) {
						if (msg.data.length == 0) {
							log.error("*** a publishState was sent without a service - you probably want to send broadcastState ! {} {}**", msg.sender, msg.data.length);
							return;
						}

						si = (ServiceInterface) msg.data[0];
						si.setInstanceId(uri);
						String xForwardDataName = String.format("%s%s", myService.getPrefix(protocolKey), si.getName());
						si.setName(xForwardDataName);
					}
					// router x-forwarded inbound proxy end
				}
				
				if ("onState".equals(msg.method)) {
					// FIXME - normalize
					// router x-forwarded inbound proxy begin
					Object[] msgData = msg.data;
					ServiceInterface si = null;

					if (msgData != null) {
						if (msg.data.length == 0) { 
							log.error("*** a publishState was sent without a service - you probably want to send broadcastState ! {} {}**", msg.sender, msg.data.length);
							return;
						}

						si = (ServiceInterface) msg.data[0];
						si.setInstanceId(uri);
						String xForwardDataName = String.format("%s%s", myService.getPrefix(protocolKey), si.getName());
						si.setName(xForwardDataName);
					}
					// router x-forwarded inbound proxy end
				}


				if ("addListener".equals(msg.method)) {
					MRLListener listener = (MRLListener) msg.data[0];
					listener.callbackName = xForwardSender;
				}

				// FIXME - THIS NEEDS TO BE NORMALIZED - WILL BE THE SAME IN
				// XMPP & WEBGUI & REMOTEADAPTER
				// FIXME - normalize to single method - check for data
				// type too ? !!!
				if (msg.method.equals("onRegistered")){
					Object[] msgData = msg.data;
					ServiceInterface si = null;

					// ALLOWED TO BE NULL - establishes initial contact & a
					// ServiceEnvironment
					if (msgData != null) {
						si = (ServiceInterface) msg.data[0];
						si.setInstanceId(uri);
						String xForwardDataName = String.format("%s%s", myService.getPrefix(protocolKey), si.getName());
						si.setName(xForwardDataName);
						myService.send(Runtime.getInstance().getName(), "register", si, uri); // <!!! better register it in the right Service Environment !!!
					}
					
					
				}
				if (msg.method.equals("register")) {
					// create the URI key for foreign service environment

					// IMPORTANT - this is an optimization and probably
					// should be in the Comm interface defintion
					CommunicationInterface cm = myService.getComm();
					cm.addRemote(uri, protocolKey);

					// check if the URI is already defined - if not - we will
					// send back the services which we want to export -
					// Security will filter appropriately
					ServiceEnvironment foreignProcess = Runtime.getEnvironment(uri);

					// FIXME - normalize ...
					Object[] msgData = msg.data;
					ServiceInterface si = null;

					// ALLOWED TO BE NULL - establishes initial contact & a
					// ServiceEnvironment
					if (msgData != null) {
						si = (ServiceInterface) msg.data[0];
						si.setInstanceId(uri);
						String xForwardDataName = String.format("%s%s", myService.getPrefix(protocolKey), si.getName());
						si.setName(xForwardDataName);
						
					}

					// HMMM a vote for String vs URI here - since we need to
					// catch syntax !!!

					// if security ... msg within msg
					// getOutbox().add(createMessage(Runtime.getInstance().getName(),
					// "register", inboundMsg));
					// Runtime.register(si, uri);// <-- not an INVOKE !!! // -  RECENTLY REMOVED !!!!!!
					
					myService.send(Runtime.getInstance().getName(), "register", si, uri);
					// no security ! :P

					// if is a foreign process - send our registration
					if (foreignProcess == null) {

						// not defined we will send export
						// TODO - Security filters - default export (include
						// exclude) - mapset of name
						ServiceEnvironment localProcess = Runtime.getLocalServicesForExport();

						Iterator<String> it = localProcess.serviceDirectory.keySet().iterator();
						String name;
						ServiceInterface toRegister;
						while (it.hasNext()) {
							name = it.next();
							toRegister = localProcess.serviceDirectory.get(name);

							// the following will wrap a message within a
							// message and send it remotely
							// This Thread CANNOT Write on The
							// ObjectOutputStream directly -
							// IT SHOULD NEVER DO ANY METHOD WHICH CAN BLOCK
							// !!!! - 3 days of bug chasing when
							// it wrote to ObjectOutputStream and oos blocked
							// when the buffer was full - causing deadlock
							// putting it on the inbox will move it to a
							// different thread
							Message sendService = myService.createMessage("", "register", toRegister);
							Message outbound = myService.createMessage(myService.getName(), "sendRemote", new Object[] { protocolKey, sendService });
							myService.getInbox().add(outbound);

						}

					}

					// BEGIN ENCAPSULATION --- ENCODER END -------------
				} else {
					myService.getOutbox().add(msg);
					++data.rx;
				}
			} // while

			// closing connections TODO - why wouldn't you close the others?
			in.close();
			out.close();

		} catch (Exception e) {
			isRunning = false;
			socket = null;
			Logging.logError(e);
			data.state = Connection.DISCONNECTED;
		}

		releaseConnect();

		// connection has been broken
		// myService.invoke("connectionBroken", url); FIXME
	}

	// FIXME - merge with RemoteAdapter - this is just sendRemote
	public synchronized void send(Message msg) {
		try {

			/*
			 * debugging logging if (msg.data != null && msg.data[0] != null &&
			 * msg.data[0] instanceof Service) { String n = ((Service)
			 * msg.data[0]).getName(); log.info(String.format("serializing %s",
			 * n)); if (n.equals("r1")) { log.info("here"); // return; }
			 * 
			 * }
			 */

			// router x-forwarded outbound proxy begin
			// TODO - optimize - set once ! same with prefix .. +1 for the
			// String.format("%s.", n) period !
			// FIXME - broadcast "should" be null -
			if (!"".equals(msg.name)) 
			{
				msg.name = msg.name.substring(myService.getPrefix(protocolKey).length());
			}
			// router x-forwarded outbound proxy end

			// nice for debugging
			if (msgLog != null) {
				msgLog.write(String.format("%s --> %s - %s\n", myService.getName(), uri, Encoder.toJson(msg)).getBytes());
			}
			

			out.writeObject(msg);
			out.flush();
			// MAKE NOTE !!! : 
			// a reset is necessary after every object !
			out.reset(); 
			data.txSender = msg.sender;
			data.txSendingMethod = msg.sendingMethod;
			data.txName = msg.name;
			data.txMethod = msg.method;
			++data.tx;

		} catch (Exception e) {
			myService.error(e);
			releaseConnect();
		}
	}

}
