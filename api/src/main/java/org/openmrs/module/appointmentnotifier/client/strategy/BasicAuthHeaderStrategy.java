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

import java.util.LinkedHashMap;
import java.util.Map;

import org.openmrs.module.appointmentnotifier.client.ProviderCredentials;
import org.openmrs.module.appointmentnotifier.client.ProviderHeaderStrategy;
import org.springframework.stereotype.Component;

/**
 * Handles LEGACYLINK: contributes only {@code username} and {@code password} to
 * {@code x-provider-config}.
 */
@Component
public class BasicAuthHeaderStrategy implements ProviderHeaderStrategy {
	
	@Override
	public boolean supports(String providerName) {
		return "LEGACYLINK".equalsIgnoreCase(providerName);
	}
	
	@Override
	public Map<String, String> providerConfig(ProviderCredentials credentials) {
		Map<String, String> config = new LinkedHashMap<>();
		String username = credentials.getUsername();
		if (username != null && !username.isEmpty()) {
			config.put("username", username);
		}
		String password = credentials.getPassword();
		if (password != null && !password.isEmpty()) {
			config.put("password", password);
		}
		return config;
	}
}
