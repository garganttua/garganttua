package com.garganttua.api.e2e.nativemongo;

import com.garganttua.api.starter.GarganttuaApplication;

/**
 * The native proof's entry point. Everything else is the starter's: {@code application.yaml} names
 * the package to scan and the database ({@code mongodb.uri}, overridable with
 * {@code GARGANTTUA_MONGODB_URI}).
 */
public final class NativeMongoApp {

	private NativeMongoApp() {
	}

	/**
	 * Boots the API and serves until the process is stopped.
	 *
	 * @param args command-line arguments, passed to the starter
	 */
	public static void main(String[] args) {
		GarganttuaApplication.runAndWait(NativeMongoApp.class, args);
	}
}
