/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.appointmentnotifier.client;

/**
 * Immutable snapshot of all provider credential global properties, passed to a
 * {@link ProviderHeaderStrategy} so strategies remain free of OpenMRS API dependencies.
 */
public final class ProviderCredentials {
	
	private final String apiKey;
	
	private final String username;
	
	private final String password;
	
	private final String clientId;
	
	private final String clientSecret;
	
	public ProviderCredentials(String apiKey, String username, String password, String clientId, String clientSecret) {
		this.apiKey = apiKey;
		this.username = username;
		this.password = password;
		this.clientId = clientId;
		this.clientSecret = clientSecret;
	}
	
	public String getApiKey() {
		return apiKey;
	}
	
	public String getUsername() {
		return username;
	}
	
	public String getPassword() {
		return password;
	}
	
	public String getClientId() {
		return clientId;
	}
	
	public String getClientSecret() {
		return clientSecret;
	}
}
