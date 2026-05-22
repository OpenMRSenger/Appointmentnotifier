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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.openmrs.module.appointmentnotifier.client.ProviderCredentials;
import org.openmrs.module.appointmentnotifier.client.ProviderHeaderStrategy;
import org.springframework.stereotype.Component;

/**
 * Handles token-based providers (SWIFTSEND, ASYNCFLOW): contributes only {@code apiKey} to
 * {@code x-provider-config}, matching {@code SwiftSendConfig} / {@code AsyncFlowConfig}.
 */
@Component
public class TokenHeaderStrategy implements ProviderHeaderStrategy {

	private static final Set<String> SUPPORTED = new HashSet<>(Arrays.asList("SWIFTSEND", "ASYNCFLOW"));

	@Override
	public boolean supports(String providerName) {
		return providerName != null && SUPPORTED.contains(providerName.toUpperCase());
	}

	@Override
	public Map<String, String> providerConfig(ProviderCredentials credentials) {
		String apiKey = credentials.getApiKey();
		if (apiKey == null || apiKey.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<String, String> config = new LinkedHashMap<>();
		config.put("apiKey", apiKey);
		return config;
	}
}
