/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.appointmentnotifier.client.strategy;

import java.net.HttpURLConnection;

import org.openmrs.module.appointmentnotifier.client.ProviderCredentials;
import org.openmrs.module.appointmentnotifier.client.ProviderHeaderStrategy;
import org.springframework.stereotype.Component;

/**
 * Handles SECUREPOST by emitting {@code X-Messaging-Provider-Client-Id} and
 * {@code X-Messaging-Provider-Client-Secret}.
 */
@Component
public class ClientCredentialsHeaderStrategy implements ProviderHeaderStrategy {
	
	@Override
	public boolean supports(String providerName) {
		return "SECUREPOST".equalsIgnoreCase(providerName);
	}
	
	@Override
	public void applyHeaders(HttpURLConnection conn, ProviderCredentials credentials) {
		String clientId = credentials.getClientId();
		if (clientId != null && !clientId.isEmpty()) {
			conn.setRequestProperty("X-Messaging-Provider-Client-Id", clientId);
		}
		String clientSecret = credentials.getClientSecret();
		if (clientSecret != null && !clientSecret.isEmpty()) {
			conn.setRequestProperty("X-Messaging-Provider-Client-Secret", clientSecret);
		}
	}
}
