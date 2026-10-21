package com.garganttua.events.api;

import java.util.Map;

import com.garganttua.events.api.exceptions.ConnectorException;

/**
 * A connector whose configuration can change while the engine runs.
 *
 * <p>{@link IConnector#configure} is called once, when the engine initialises the topology. A connector that
 * implements this interface also accepts {@link #reconfigure} afterwards: the new configuration applies to the
 * producers and consumers it ALREADY created, from their next message on, without restarting the engine. Reached
 * through {@link IEvents#reconfigureConnector}.</p>
 *
 * <p>Semantics every implementation keeps:</p>
 * <ul>
 *   <li><b>merge</b> — the keys present in {@code configuration} replace the current values, the absent ones are
 *       kept; a key present with an empty value clears it;</li>
 *   <li><b>atomic</b> — a message sees either the whole previous configuration or the whole new one, never a mix;
 *       a message already being processed finishes with the configuration it started with;</li>
 *   <li><b>all or nothing</b> — an invalid configuration is refused with a {@link ConnectorException} and the
 *       current one stays in force.</li>
 * </ul>
 */
public interface IReconfigurableConnector extends IConnector {

	/**
	 * Merges {@code configuration} into the connector's current configuration and applies it.
	 *
	 * @param configuration the keys to change (same keys as {@link IConnector#configure}); never {@code null}
	 * @throws ConnectorException when the resulting configuration is invalid — the current one is then kept
	 */
	void reconfigure(Map<String, String> configuration) throws ConnectorException;
}
