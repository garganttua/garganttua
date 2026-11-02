package com.garganttua.api.e2e.nativepg;

import com.garganttua.api.starter.GarganttuaApplication;

/**
 * The native proof's entry point. Everything else is the starter's: {@code application.yaml} names
 * the package to scan and the database ({@code postgresql.url}, overridable with
 * {@code GARGANTTUA_POSTGRESQL_URL}).
 */
public final class NativePostgresApp {

	private NativePostgresApp() {
	}

	/**
	 * Boots the API and serves until the process is stopped.
	 *
	 * @param args command-line arguments, passed to the starter
	 */
	public static void main(String[] args) {
		GarganttuaApplication.runAndWait(NativePostgresApp.class, args);
	}
}
