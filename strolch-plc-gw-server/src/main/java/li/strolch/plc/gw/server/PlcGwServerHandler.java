package li.strolch.plc.gw.server;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static li.strolch.plc.model.PlcConstants.*;
import static li.strolch.utils.helper.ExceptionHelper.getExceptionMessageWithCauses;
import static li.strolch.websocket.WebSocketRemoteIp.*;

import javax.websocket.CloseReason;
import javax.websocket.PongMessage;
import javax.websocket.RemoteEndpoint.Basic;
import javax.websocket.Session;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import li.strolch.agent.api.ComponentContainer;
import li.strolch.agent.api.StrolchComponent;
import li.strolch.handler.operationslog.OperationsLog;
import li.strolch.model.Locator;
import li.strolch.model.log.LogMessage;
import li.strolch.model.log.LogMessageState;
import li.strolch.plc.model.*;
import li.strolch.privilege.base.NotAuthenticatedException;
import li.strolch.privilege.base.PrivilegeException;
import li.strolch.privilege.model.Certificate;
import li.strolch.privilege.model.Usage;
import li.strolch.privilege.model.UserRep;
import li.strolch.rest.StrolchSessionHandler;
import li.strolch.runtime.configuration.ComponentConfiguration;
import li.strolch.runtime.privilege.PrivilegedRunnable;
import li.strolch.runtime.privilege.PrivilegedRunnableWithResult;
import li.strolch.utils.collections.MapOfLists;
import li.strolch.utils.dbc.DBC;
import li.strolch.websocket.WebSocketRemoteIp;

public class PlcGwServerHandler extends StrolchComponent {

	public static final String MSG_DISCONNECTED_TIMED_OUT = "Disconnected / Timed out";
	public static final String THREAD_POOL = "PlcRequests";

	private String runAsUser;
	private Set<String> plcIds;
	private PlcStateHandler plcStateHandler;

	private Map<String, PlcSession> plcSessionsBySessionId;
	private Map<String, PlcSession> plcSessionsByPlcId;

	private Map<String, MapOfLists<PlcAddressKey, PlcNotificationListener>> plcAddressListenersByPlcId;
	private Map<Long, PlcResponse> plcResponses;

	public PlcGwServerHandler(ComponentContainer container, String componentName) {
		super(container, componentName);
	}

	public Set<String> getPlcIds() {
		return this.plcIds;
	}

	@Override
	public void initialize(ComponentConfiguration configuration) throws Exception {

		this.runAsUser = configuration.getString("runAsUser", "plc-server");

		this.plcIds = runAsAgentWithResult(
				ctx -> getContainer().getPrivilegeHandler().getPrivilegeHandler().getUsers(ctx.getCertificate())
						.stream() //
						.filter(user -> user.hasRole(ROLE_PLC)).map(UserRep::getUsername) //
						.collect(toSet()));

		this.plcStateHandler = new PlcStateHandler(getContainer());
		this.plcSessionsBySessionId = new ConcurrentHashMap<>();
		this.plcSessionsByPlcId = new ConcurrentHashMap<>();
		this.plcAddressListenersByPlcId = new ConcurrentHashMap<>();
		this.plcResponses = new ConcurrentHashMap<>();
		super.initialize(configuration);
	}

	public boolean isPlcConnected(String plcId) {
		DBC.PRE.assertNotEmpty("plcId must not be empty", plcId);
		return this.plcSessionsByPlcId.containsKey(plcId);
	}

	public void register(String plcId, PlcAddressKey addressKey, PlcNotificationListener listener) {
		DBC.PRE.assertNotNull("addressKey must not be null", addressKey);
		DBC.PRE.assertNotEmpty("plcId must not be empty", plcId);
		MapOfLists<PlcAddressKey, PlcNotificationListener> plcListeners = this.plcAddressListenersByPlcId.get(plcId);
		if (plcListeners == null) {
			plcListeners = new MapOfLists<>();
			this.plcAddressListenersByPlcId.put(plcId, plcListeners);
		}

		//noinspection SynchronizationOnLocalVariableOrMethodParameter
		synchronized (plcListeners) {
			plcListeners.addElement(addressKey, listener);
		}

		logger.info("Registered listener on plc " + plcId + " key " + addressKey + ": " + listener);
	}

	public void unregister(String plcId, PlcAddressKey addressKey, PlcNotificationListener listener) {
		DBC.PRE.assertNotNull("addressKey must not be null", addressKey);
		DBC.PRE.assertNotEmpty("plcId must not be empty", plcId);
		MapOfLists<PlcAddressKey, PlcNotificationListener> plcListeners = this.plcAddressListenersByPlcId.get(plcId);
		if (plcListeners == null)
			return;

		//noinspection SynchronizationOnLocalVariableOrMethodParameter
		synchronized (plcListeners) {
			plcListeners.removeElement(addressKey, listener);
		}

		logger.info("Unregistered listener from plc " + plcId + " key " + addressKey + ": " + listener);
	}

	public void run(PrivilegedRunnable runnable) throws Exception {
		super.runAs(this.runAsUser, runnable);
	}

	public <T> T runWithResult(PrivilegedRunnableWithResult<T> runnable) throws Exception {
		return super.runAsWithResult(this.runAsUser, runnable);
	}

	public void sendMessage(PlcAddressKey addressKey, String plcId, boolean value,
			PlcAddressResponseListener listener) {
		sendMessage(addressKey, plcId, new JsonPrimitive(value), listener);
	}

	public void sendMessage(PlcAddressKey addressKey, String plcId, int value, PlcAddressResponseListener listener) {
		sendMessage(addressKey, plcId, new JsonPrimitive(value), listener);
	}

	public void sendMessage(PlcAddressKey addressKey, String plcId, double value, PlcAddressResponseListener listener) {
		sendMessage(addressKey, plcId, new JsonPrimitive(value), listener);
	}

	public void sendMessage(PlcAddressKey addressKey, String plcId, String value, PlcAddressResponseListener listener) {
		sendMessage(addressKey, plcId, new JsonPrimitive(value), listener);
	}

	public void sendMessage(PlcAddressKey addressKey, String plcId, PlcAddressResponseListener listener) {
		sendMessage(addressKey, plcId, (JsonPrimitive) null, listener);
	}

	public PlcAddressResponse sendMessageSync(PlcAddressKey addressKey, String plcId, boolean value) {
		return sendMessageSync(addressKey, plcId, new JsonPrimitive(value));
	}

	public PlcAddressResponse sendMessage(PlcAddressKey addressKey, String plcId, int value) {
		return sendMessageSync(addressKey, plcId, new JsonPrimitive(value));
	}

	public PlcAddressResponse sendMessage(PlcAddressKey addressKey, String plcId, double value) {
		return sendMessageSync(addressKey, plcId, new JsonPrimitive(value));
	}

	public PlcAddressResponse sendMessage(PlcAddressKey addressKey, String plcId, String value) {
		return sendMessageSync(addressKey, plcId, new JsonPrimitive(value));
	}

	public PlcAddressResponse sendMessage(PlcAddressKey addressKey, String plcId) {
		return sendMessageSync(addressKey, plcId, (JsonPrimitive) null);
	}

	public PlcAddressResponse sendMessageSync(PlcAddressKey addressKey, String plcId) {
		return sendMessageSync(addressKey, plcId, null);
	}

	public PlcAddressResponse sendMessageSync(PlcAddressKey addressKey, String plcId, JsonPrimitive valueJ) {

		PlcAddressResponse[] response = new PlcAddressResponse[1];

		CountDownLatch latch = new CountDownLatch(1);
		sendMessage(addressKey, plcId, valueJ, r -> {
			response[0] = r;
			latch.countDown();
		});

		try {
			if (!latch.await(30, TimeUnit.SECONDS))
				return new PlcAddressResponse(plcId, addressKey).state(PlcResponseState.Failed, "Timeout after 30s!");
		} catch (InterruptedException e) {
			logger.error("Interrupted!");
			return new PlcAddressResponse(plcId, addressKey).state(PlcResponseState.Failed, "Interrupted!");
		}

		return response[0];
	}

	private void sendMessage(PlcAddressKey addressKey, String plcId, JsonPrimitive valueJ,
			PlcAddressResponseListener listener) {

		PlcSession plcSession = this.plcSessionsByPlcId.get(plcId);
		if (plcSession == null)
			throw new IllegalStateException("PLC " + plcId + " is not connected!");

		assertPlcAuthed(plcId, plcSession.session.getId());

		getExecutorService(THREAD_POOL).submit(() -> send(plcSession, addressKey, valueJ, listener));
	}

	private void send(PlcSession plcSession, PlcAddressKey plcAddressKey, JsonPrimitive valueJ,
			PlcAddressResponseListener listener) {

		if (valueJ == null)
			logger.info("Sending " + plcAddressKey + " to " + plcSession.plcId + "...");
		else
			logger.info("Sending " + plcAddressKey + " with value " + valueJ + " to " + plcSession.plcId + "...");

		PlcAddressResponse plcResponse = new PlcAddressResponse(plcSession.plcId, plcAddressKey);
		plcResponse.setListener(() -> handleResponse(listener, plcResponse));

		try {

			JsonObject jsonObject = new JsonObject();
			jsonObject.addProperty(PARAM_SEQUENCE_ID, plcResponse.getSequenceId());
			jsonObject.addProperty(PARAM_MESSAGE_TYPE, MSG_TYPE_PLC_TELEGRAM);
			jsonObject.addProperty(PARAM_PLC_ID, plcSession.plcId);
			jsonObject.addProperty(PARAM_RESOURCE, plcAddressKey.resource);
			jsonObject.addProperty(PARAM_ACTION, plcAddressKey.action);
			if (valueJ != null)
				jsonObject.add(PARAM_VALUE, valueJ);

			String data = jsonObject.toString();
			this.plcResponses.put(plcResponse.getSequenceId(), plcResponse);

			synchronized (plcSession.session) {
				sendDataToClient(data, plcSession.session.getBasicRemote());
			}

		} catch (Exception e) {
			logger.error("Failed to send " + plcAddressKey + " to PLC " + plcSession.plcId, e);
			plcResponse.setState(PlcResponseState.Failed);
			plcResponse.setStateMsg("Failed to send " + plcAddressKey + " to PLC " + plcSession.plcId + ": "
					+ getExceptionMessageWithCauses(e));

			try {
				listener.handleResponse(plcResponse);
			} catch (Exception ex) {
				logger.error("Failed to notify listener " + listener, ex);
			}
		}
	}

	private void handleResponse(PlcAddressResponseListener listener, PlcAddressResponse response) {
		try {
			listener.handleResponse(response);
		} catch (Exception e) {
			logger.error("Failed to notify listener " + listener + " for response of " + response, e);
		}
	}

	private PlcSession assertPlcAuthed(String plcId, String sessionId) throws NotAuthenticatedException {
		PlcSession plcSession = this.plcSessionsBySessionId.get(sessionId);
		if (plcSession.certificate == null)
			throw new NotAuthenticatedException(sessionId + ": PLC Not yet authenticated!");
		if (!plcId.equals(plcSession.plcId))
			throw new IllegalStateException(
					sessionId + ": PLC ID " + plcId + " not same as SessionId's PLC ID " + plcSession.plcId);

		try {
			StrolchSessionHandler sessionHandler = getContainer().getComponent(StrolchSessionHandler.class);
			sessionHandler.validate(plcSession.certificate);
		} catch (RuntimeException e) {
			this.plcStateHandler.handlePlcState(plcSession, ConnectionState.Failed,
					"Message received although not yet authed!", null);
			throw new NotAuthenticatedException(sessionId + ": Certificate not valid!", e);
		}

		return plcSession;
	}

	private void sendDataToClient(String data, Basic basic) throws IOException {
		int pos = 0;
		while (pos + 8192 < data.length()) {
			basic.sendText(data.substring(pos, pos + 8192), false);
			pos += 8192;
		}
		basic.sendText(data.substring(pos), true);
	}

	public void onWsMessage(String message, Session session) throws IOException {

		JsonObject jsonObject = JsonParser.parseString(message).getAsJsonObject();
		if (!jsonObject.has(PARAM_MESSAGE_TYPE))
			throw new IllegalStateException("Message is missing " + PARAM_MESSAGE_TYPE);
		if (!jsonObject.has(PARAM_PLC_ID))
			throw new IllegalStateException("Message is missing " + PARAM_PLC_ID);

		String plcId = jsonObject.get(PARAM_PLC_ID).getAsString();

		String messageType = jsonObject.get(PARAM_MESSAGE_TYPE).getAsString();
		switch (messageType) {
		case MSG_TYPE_AUTHENTICATION -> handleAuth(session, jsonObject);
		case MSG_TYPE_PLC_NOTIFICATION -> handleNotification(assertPlcAuthed(plcId, session.getId()), jsonObject);
		case MSG_TYPE_PLC_TELEGRAM -> handleTelegramResponse(assertPlcAuthed(plcId, session.getId()), jsonObject);
		case MSG_TYPE_STATE_NOTIFICATION -> handleStateMsg(assertPlcAuthed(plcId, session.getId()), jsonObject);
		case MSG_TYPE_MESSAGE -> {
			assertPlcAuthed(plcId, session.getId());
			handleMessage(jsonObject);
		}
		case MSG_TYPE_DISABLE_MESSAGE -> {
			assertPlcAuthed(plcId, session.getId());
			handleDisableMessage(jsonObject);
		}
		default -> logger.error(plcId + ": Unhandled message type " + messageType);
		}
	}

	private void handleNotification(PlcSession plcSession, JsonObject notificationJ) {
		String resource = notificationJ.get(PARAM_RESOURCE).getAsString();
		String action = notificationJ.get(PARAM_ACTION).getAsString();
		PlcAddressKey addressKey = PlcAddressKey.keyFor(resource, action);

		JsonPrimitive valueJ = notificationJ.get(PARAM_VALUE).getAsJsonPrimitive();
		Object value;
		if (valueJ.isBoolean())
			value = valueJ.getAsBoolean();
		else if (valueJ.isNumber())
			value = valueJ.getAsNumber();
		else if (valueJ.isString())
			value = valueJ.getAsString();
		else
			value = valueJ.getAsString();

		logger.info(plcSession.plcId + ": Received notification for " + addressKey.toKey() + ": " + value);

		MapOfLists<PlcAddressKey, PlcNotificationListener> plcListeners = this.plcAddressListenersByPlcId.get(
				plcSession.plcId);
		if (plcListeners == null) {
			logger.warn(plcSession.plcId + ": No listeners for PLC " + plcSession.plcId);
			return;
		}

		List<PlcNotificationListener> listeners;
		//noinspection SynchronizationOnLocalVariableOrMethodParameter
		synchronized (plcListeners) {
			listeners = plcListeners.getList(addressKey);
			if (listeners == null) {
				logger.warn(plcSession.plcId + ": No listeners for " + addressKey.toKey());
				return;
			}
		}

		listeners = new ArrayList<>(listeners);
		for (PlcNotificationListener listener : listeners) {
			try {
				listener.handleNotification(addressKey, value);
			} catch (Exception e) {
				logger.error(
						plcSession.plcId + ": Failed to notify listener " + listener + " for " + addressKey.toKey(), e);
			}
		}
	}

	private void handleTelegramResponse(PlcSession plcSession, JsonObject responseJ) {

		long sequenceId = responseJ.get(PARAM_SEQUENCE_ID).getAsLong();
		PlcResponse plcResponse = this.plcResponses.remove(sequenceId);
		if (plcResponse == null) {
			logger.error(plcSession.plcId + ": PlcResponse does not exist for sequenceId " + sequenceId);
			return;
		}

		String state = responseJ.get(PARAM_STATE).getAsString();
		String stateMsg = responseJ.get(PARAM_STATE_MSG).getAsString();
		plcResponse.setState(PlcResponseState.valueOf(state));
		plcResponse.setStateMsg(stateMsg);

		plcResponse.getListener().run();
	}

	private void handleMessage(JsonObject jsonObject) {
		JsonObject msgJ = jsonObject.get(PARAM_MESSAGE).getAsJsonObject();
		LogMessage logMessage = LogMessage.fromJson(msgJ);
		logger.info("Received message " + logMessage.getLocator());
		OperationsLog log = getComponent(OperationsLog.class);
		log.updateState(logMessage.getRealm(), logMessage.getLocator(), LogMessageState.Inactive);
		log.addMessage(logMessage);
	}

	private void handleDisableMessage(JsonObject jsonObject) {
		String realm = jsonObject.get(PARAM_REALM).getAsString();
		Locator locator = Locator.valueOf(jsonObject.get(PARAM_LOCATOR).getAsString());
		logger.info("Received disable for messages with locator " + locator);

		OperationsLog operationsLog = getComponent(OperationsLog.class);
		operationsLog.updateState(realm, locator, LogMessageState.Inactive);
	}

	private void handleAuth(Session session, JsonObject authJ) throws IOException {
		String sessionId = session.getId();
		if (!authJ.has(PARAM_PLC_ID) || !authJ.has(PARAM_USERNAME) || !authJ.has(PARAM_PASSWORD))
			throw new IllegalStateException(
					sessionId + ": Auth Json is missing one of " + PARAM_PLC_ID + ", " + PARAM_USERNAME + ", "
							+ PARAM_PASSWORD + ": " + authJ);

		String plcId = authJ.get(PARAM_PLC_ID).getAsString();
		String username = authJ.get(PARAM_USERNAME).getAsString();
		String password = authJ.get(PARAM_PASSWORD).getAsString();

		PlcSession plcSession = this.plcSessionsBySessionId.get(sessionId);
		if (plcSession.certificate != null)
			throw new IllegalStateException(sessionId + ": Session already authenticated for PLC " + plcSession.plcId);
		if (!plcId.equals(plcSession.plcId))
			throw new IllegalStateException(
					sessionId + ": Auth PlcId " + plcId + " not same as Session's PlcID " + plcSession.plcId);

		StrolchSessionHandler sessionHandler = getContainer().getComponent(StrolchSessionHandler.class);
		Certificate certificate;
		try {
			char[] passwordChars = password.toCharArray();
			certificate = sessionHandler.authenticate(username, passwordChars, get(), Usage.ANY, false);
		} catch (PrivilegeException e) {
			session.close(new CloseReason(CloseReason.CloseCodes.PROTOCOL_ERROR,
					"Authentication failed for given credentials!"));
			throw e;
		}

		plcSession.certificate = certificate;

		JsonObject authResponseJ = new JsonObject();
		authResponseJ.addProperty(PARAM_MESSAGE_TYPE, MSG_TYPE_AUTHENTICATION);
		authResponseJ.addProperty(PARAM_STATE, PlcResponseState.Sent.name());
		authResponseJ.addProperty(PARAM_STATE_MSG, "");
		authResponseJ.addProperty(PARAM_AUTH_TOKEN, certificate.getAuthToken());
		getExecutorService(THREAD_POOL).submit(() -> sendAuthResponse(plcSession, authResponseJ));

		this.plcStateHandler.handlePlcState(plcSession, ConnectionState.Connected, "", authJ);
	}

	private void handleStateMsg(PlcSession plcSession, JsonObject stateMsgJ) {
		this.plcStateHandler.handlePlcState(plcSession, ConnectionState.Connected, "", stateMsgJ);
	}

	private void sendAuthResponse(PlcSession plcSession, JsonObject jsonObject) {
		try {
			String data = jsonObject.toString();
			synchronized (plcSession.session) {
				sendDataToClient(data, plcSession.session.getBasicRemote());
			}
			logger.info(plcSession.plcId + ": Sent " + MSG_TYPE_AUTHENTICATION + " response on Session "
					+ plcSession.session.getId());
		} catch (Exception e) {
			logger.error(plcSession.plcId + ": Failed to send data to PLC", e);
			try {
				plcSession.session.close(
						new CloseReason(CloseReason.CloseCodes.CLOSED_ABNORMALLY, "Failed to send auth response"));
			} catch (IOException ex) {
				logger.error(plcSession.plcId + ": Faild to close session to PLC");
			}
		}
	}

	public void onWsOpen(Session session) {
		logger.info(session.getId() + ": New Session");
	}

	public void onWsPong(PongMessage message, Session session) {
		String plcId = new String(message.getApplicationData().array());

		PlcSession plcSession = this.plcSessionsBySessionId.get(session.getId());
		if (plcSession != null) {
			plcSession.lastUpdate = System.currentTimeMillis();
			logger.info(
					"PLC " + plcId + " with SessionId " + plcSession.session.getId() + " is still alive on certificate "
							+ (plcSession.certificate == null ? null : plcSession.certificate.getSessionId()));
		} else {
			plcSession = new PlcSession(plcId, session);
			plcSession.lastUpdate = System.currentTimeMillis();

			PlcSession existingPlcSession = this.plcSessionsByPlcId.put(plcId, plcSession);
			if (existingPlcSession != null) {
				logger.error("Old PLC session found for plc " + plcId + " under SessionId "
						+ existingPlcSession.session.getId() + ". Closing that session.");

				this.plcSessionsBySessionId.remove(existingPlcSession.session.getId());
				try {
					synchronized (existingPlcSession.session) {
						existingPlcSession.session.close(
								new CloseReason(CloseReason.CloseCodes.NOT_CONSISTENT, "Stale session"));
					}
				} catch (Exception e) {
					logger.error("Failed to close session " + existingPlcSession.session.getId(), e);
				}
			}

			this.plcSessionsBySessionId.put(session.getId(), plcSession);
			logger.info("PLC connected with ID " + plcId + " and SessionId " + plcSession.session.getId());
		}

		if (plcSession.certificate != null) {
			StrolchSessionHandler sessionHandler = getContainer().getComponent(StrolchSessionHandler.class);
			sessionHandler.validate(plcSession.certificate);

			this.plcStateHandler.handleStillConnected(plcSession);
		}
	}

	private void clearDeadConnections() {

		// find all sessions which are timed out
		List<PlcSession> expiredSessions = this.plcSessionsBySessionId.values().stream().filter(this::hasExpired)
				.toList();

		for (PlcSession plcSession : expiredSessions) {
			logger.warn("Session " + plcSession.session.getId() + " has expired for PLC " + plcSession.plcId
					+ ". Closing.");

			// close the session
			try {
				synchronized (plcSession.session) {
					plcSession.session.close(
							new CloseReason(CloseReason.CloseCodes.CLOSED_ABNORMALLY, "Session expired!"));
				}
			} catch (IOException e) {
				logger.error("Closing session lead to exception: " + getExceptionMessageWithCauses(e));
			}

			// invalidate the certificate
			if (plcSession.certificate != null) {
				logger.warn("Invalidating old Session " + plcSession.session.getId() + " for PLC " + plcSession.plcId
						+ " with certificate " + plcSession.certificate.getSessionId());
				StrolchSessionHandler sessionHandler = getContainer().getComponent(StrolchSessionHandler.class);
				sessionHandler.invalidate(plcSession.certificate);
			}

			this.plcSessionsBySessionId.remove(plcSession.session.getId());
			this.plcSessionsByPlcId.remove(plcSession.plcId);
		}
	}

	public void onWsClose(Session session, CloseReason closeReason) {

		PlcSession plcSession = this.plcSessionsBySessionId.remove(session.getId());
		if (plcSession == null) {
			logger.warn(session.getId() + ": Connection to session " + session.getId() + " is lost due to "
					+ closeReason.getCloseCode() + " " + closeReason.getReasonPhrase());
			return;
		}

		this.plcSessionsByPlcId.remove(plcSession.plcId);

		String reason = closeReason.getCloseCode() + " " + closeReason.getReasonPhrase();
		logger.warn(session.getId() + ": Connection to PLC " + plcSession.plcId + " is lost due to " + reason);

		if (plcSession.certificate != null) {
			StrolchSessionHandler sessionHandler = getContainer().getComponent(StrolchSessionHandler.class);
			try {
				sessionHandler.invalidate(plcSession.certificate);
			} catch (Exception e) {
				logger.error(session.getId() + ": Failed to invalidate session for plc " + plcSession.plcId, e);
			}

			this.plcStateHandler.handlePlcState(plcSession, ConnectionState.Disconnected, reason, null);
		}

		notifyObserversOfConnectionLost(plcSession.plcId);
	}

	private boolean hasExpired(PlcSession gwSession) {
		return (System.currentTimeMillis() - gwSession.lastUpdate) > TimeUnit.MINUTES.toMillis(2);
	}

	private void notifyObserversOfConnectionLost(String plcId) {

		logger.info("Notifying observers of connection lost to plc " + plcId + "...");

		// first notify and remove any response observers for disconnected PLCs
		List<PlcResponse> keySet = new ArrayList<>(this.plcResponses.values());
		for (PlcResponse plcResponse : keySet) {
			if (!plcResponse.getPlcId().equals(plcId))
				continue;

			this.plcResponses.remove(plcResponse.getSequenceId());
			plcResponse.setStateMsg(MSG_DISCONNECTED_TIMED_OUT);
			plcResponse.setState(PlcResponseState.Failed);
			try {
				logger.warn("Notifying PlcResponse listener " + plcResponse + " of connection lost!");
				plcResponse.getListener().run();
			} catch (Exception e) {
				logger.error("Failed to notify PlcResponse listener " + plcResponse);
			}
		}

		// then notify any notification observers for disconnected PLCs
		MapOfLists<PlcAddressKey, PlcNotificationListener> plcAddressListeners = this.plcAddressListenersByPlcId.get(
				plcId);
		if (plcAddressListeners == null)
			return;

		Set<PlcAddressKey> addressKeys = new HashSet<>(plcAddressListeners.keySet());
		for (PlcAddressKey addressKey : addressKeys) {
			List<PlcNotificationListener> listeners = plcAddressListeners.getList(addressKey);
			if (listeners == null)
				continue;

			List<PlcNotificationListener> listenersCopy = new ArrayList<>(listeners);
			for (PlcNotificationListener listener : listenersCopy) {
				logger.warn("Notifying PlcNotificationListener " + addressKey + " with " + listener
						+ " of connection lost!");
				listener.handleConnectionLost();
			}
		}
	}

	public void onWsError(Session session, Throwable throwable) {
		logger.error(session.getId() + ": Error: " + throwable.getMessage(), throwable);
	}

	public static class PlcSession {
		public final String plcId;
		public final Session session;
		public Certificate certificate;
		public long lastUpdate;

		private PlcSession(String plcId, Session session) {
			this.plcId = plcId;
			this.session = session;
		}
	}
}
