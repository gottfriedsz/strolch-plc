package li.strolch.plc.core;

import java.util.Collection;

import li.strolch.model.StrolchValueType;
import li.strolch.persistence.api.StrolchTransaction;
import li.strolch.plc.core.hw.Plc;
import li.strolch.plc.model.PlcAddress;
import li.strolch.plc.core.hw.PlcListener;
import li.strolch.plc.model.PlcState;
import li.strolch.privilege.model.Certificate;

public interface PlcHandler {

	PlcState getPlcState();

	String getPlcStateMsg();

	boolean reconfigurePlc();

	void startPlc();

	void stopPlc();

	Plc getPlc();

	PlcAddress getPlcAddress(String resource, String action);

	String getPlcAddressId(String resource, String action);

	void setGlobalListener(PlcListener listener);

	void registerListener(String resource, String action, PlcListener listener);

	void unregisterListener(String resource, String action, PlcListener listener);

	void registerVirtualListener(String resource, String action, PlcListener listener, StrolchValueType valueType,
			Object defaultValue);

	void unregisterVirtualListener(String resource, String action);

	Collection<PlcAddress> getVirtualAddresses();

	void send(String resource, String action);

	void send(String resource, String action, Object value);

	void notify(String resource, String action, Object value);

	StrolchTransaction openTx(Certificate cert, boolean readOnly);

}
